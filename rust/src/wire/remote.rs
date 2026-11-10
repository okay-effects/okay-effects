//! THE HOST'S SIDE. A far side is an effect, `Far`: what a program of this side asks of it. The operations are the
//! wire's moves and nothing more; calls, objects, programs and streams are programs over them, and `Remote::run` is
//! their handler — the only place where bytes move.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};
use std::io::{Read, Write};
use std::iter::Peekable;
use std::process::{Child, Command, Stdio};
use std::rc::Rc;

use crate::effects::{Emit, Pull, Throws};
use crate::json::{Json, Ref};
use crate::wire::escapes::{dec, enc, items};
use crate::wire::{Condition, Foreign, Link, SHIM, auth, call};
use crate::{Comp, Handler, Has, Includes, Reply, perform, pure, row};

pub enum Far {
    /// a request sent, not waited for: the number its answer will carry
    Send(&'static str, Vec<(&'static str, Json)>, Reply<i64>),
    /// the answer to a request, waited for: `ok`'s value, or the far side's condition
    Receive(i64, Reply<Result<Json, Condition>>),
    /// the number of a new run: its continuations are forgotten when the program ends
    Run(Reply<i64>),
    /// a call opened as a stream: the stream's number
    Open(Opening, Reply<Result<i64, Condition>>),
    /// the stream's next chunk, waited for and paid back with a credit; none at its end
    Pull(i64, Reply<Result<Option<Json>, Condition>>),
}

/// A call to be opened as a stream: its arguments as they cross, the credit, the values this side feeds it, if any.
pub struct Opening {
    pub name: String,
    pub args: Json,
    pub credit: i64,
    pub input: Option<Pull<Json>>,
}

/// a far side's failure is raised here: a handler of `Throws<Condition>` decides what it means
pub type Failing = row![Far, Throws<Condition>];
/// the row of a far program: what it asks is asked here, as a `Foreign::Call`
pub type Asking = row![Far, Throws<Condition>, Foreign];
/// the row of a far stream: what it sends is sent here
pub type Streaming = row![Far, Throws<Condition>, Emit<Json>];

fn raised<R: Has<Throws<Condition>, I>, I, A: 'static>(result: Result<A, Condition>) -> Comp<R, A> {
    match result {
        Ok(a) => pure(a),
        Err(c) => Throws::raise(c),
    }
}

/// the answer to a request, when it has come
fn ask(op: &'static str, fields: Vec<(&'static str, Json)>) -> Comp<Failing, Json> {
    perform(|r| Far::Send(op, fields, r)).flat_map(|id| perform(move |r| Far::Receive(id, r))).flat_map(raised)
}

/// the arguments as they cross
fn crossing(args: &[Json]) -> Result<Json, Condition> {
    Ok(Json::Arr(args.iter().map(enc).collect::<Result<_, _>>()?))
}

/// a call, by a function's name or the address of something held: its answer, as a value
fn called(address: Json, args: Vec<Json>, held: bool) -> Comp<Failing, Json> {
    let asked = move |args| {
        let held = held.then_some(("held", Json::Bool(true)));
        ask("call", [("fn", address.clone()), ("args", args)].into_iter().chain(held).collect())
    };
    raised(crossing(&args)).flat_map(asked).flat_map(|ok| raised(dec(&ok)))
}

fn member(at: &Ref, what: &str, name: &str) -> Json {
    Json::obj([("ref", Json::Int(at.id)), (what, Json::str(name))])
}

impl Far {
    /// The far function's answer.
    pub fn call<R: Includes<Failing, Is>, Is>(name: &str, args: Vec<Json>) -> Comp<R, Json> {
        Far::start::<Failing, _>(name, args).flat_map(Far::result).embed()
    }

    /// A call begun and not waited for: on a far side that speaks `mux`, others may be answered before it.
    pub fn start<R: Includes<Failing, Is>, Is>(name: &str, args: Vec<Json>) -> Comp<R, i64> {
        let name = Json::str(name);
        let sent = move |args| perform(|r| Far::Send("call", vec![("fn", name.clone()), ("args", args)], r));
        raised::<Failing, _, _>(crossing(&args)).flat_map(sent).embed()
    }

    /// The answer of a call begun.
    pub fn result<R: Includes<Failing, Is>, Is>(id: i64) -> Comp<R, Json> {
        let answer: Comp<Failing, Json> = perform(move |r| Far::Receive(id, r)).flat_map(raised);
        answer.flat_map(|ok| raised(dec(&ok))).embed()
    }

    /// The far function's answer kept on the far side: a ref to it.
    pub fn hold<R: Includes<Failing, Is>, Is>(name: &str, args: Vec<Json>) -> Comp<R, Ref> {
        let kept = |answer| match answer {
            Json::Ref(kept) => pure(kept),
            other => Throws::raise(Condition::new("WireError", format!("a ref was expected: {other}"))),
        };
        called(Json::str(name), args, true).flat_map(kept).embed()
    }

    /// A method of an object held.
    pub fn method<R: Includes<Failing, Is>, Is>(at: &Ref, name: &str, args: Vec<Json>) -> Comp<R, Json> {
        called(member(at, "method", name), args, false).embed()
    }

    /// An attribute of an object held.
    pub fn attr<R: Includes<Failing, Is>, Is>(at: &Ref, name: &str) -> Comp<R, Json> {
        called(member(at, "attr", name), Vec::new(), false).embed()
    }

    /// What the ref names is dropped on the far side.
    pub fn release<R: Includes<Failing, Is>, Is>(at: &Ref) -> Comp<R, ()> {
        ask("release", vec![("ref", Json::Int(at.id))]).map(|_| ()).embed()
    }

    /// THE FAR PROGRAM `name`, as a program of this side: what it asks is asked here as a `Foreign::Call`, and
    /// answered by this side's handlers — once, many times, or never. `callbacks` names what this side offers,
    /// for a far side that checks.
    pub fn program<R: Includes<Asking, Is>, Is>(name: &str, args: Vec<Json>, callbacks: &[&str]) -> Comp<R, Json> {
        // a node of the run: the far program's value, or what it asks and the number of its rest
        fn node(run: i64, ok: Json) -> Comp<Asking, Json> {
            let Json::Str(name) = ok.get("perform") else { return raised(dec(ok.get("done"))) };
            let (name, k) = (name.clone(), ok.get("k").clone());
            let answered = move |answer| {
                let k = k.clone();
                let continued =
                    move |answer| ask("continue", vec![("run", Json::Int(run)), ("k", k.clone()), ("answer", answer)]);
                raised::<Failing, _, _>(enc(&answer)).flat_map(continued).embed()
            };
            let args = items(ok.get("args")).iter().map(dec).collect::<Result<_, _>>();
            raised(args).flat_map(move |args| call(&name, args)).flat_map(answered).flat_map(move |ok| node(run, ok))
        }
        let name = Json::str(name);
        let offered: Vec<Json> = callbacks.iter().map(|name| Json::str(name)).collect();
        let began = move |(run, args)| {
            let offered = (!offered.is_empty()).then(|| ("callbacks", Json::Arr(offered.clone())));
            let fields = [("run", Json::Int(run)), ("fn", name.clone()), ("args", args)].into_iter().chain(offered);
            ask("program", fields.collect()).embed().flat_map(move |ok| node(run, ok))
        };
        let run: Comp<Asking, i64> = perform(Far::Run);
        run.flat_map(move |run| raised(crossing(&args)).map(move |args| (run, args))).flat_map(began).embed()
    }

    /// THE FAR FUNCTION'S STREAM, as a stream of this side: it sends what the far side sends. The far side runs
    /// at most `credit` chunks ahead of whoever takes them here. With an `input`, that stream is fed to the far
    /// function, under the far side's credit, while this one is pulled.
    pub fn stream<R: Includes<Streaming, Is>, Is>(
        name: &str,
        args: Vec<Json>,
        credit: i64,
        input: Option<Comp<row![Emit<Json>], ()>>,
    ) -> Comp<R, ()> {
        fn rest(stream: i64) -> Comp<Streaming, ()> {
            let sent = move |chunk| match chunk {
                Some(chunk) => raised(dec(&chunk)).flat_map(Emit::send).then(move || rest(stream)),
                None => pure(()),
            };
            perform(move |r| Far::Pull(stream, r)).flat_map(raised).flat_map(sent)
        }
        let opened: Comp<Streaming, i64> = match crossing(&args) {
            Ok(args) => {
                let opening = Opening { name: name.to_string(), args, credit, input: input.map(Emit::pull) };
                perform(|r| Far::Open(opening, r)).flat_map(raised)
            }
            Err(c) => Throws::raise(c),
        };
        opened.flat_map(rest).embed()
    }
}

/// values this side feeds a far function, and the credit the far side has given for them
struct Feed {
    values: Peekable<Pull<Json>>,
    credit: i64,
}

/// the far side as a process this side started: stopped when the wire is dropped
struct Process(Child);

impl Drop for Process {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// the host's end of a wire, and what is in flight on it
struct Wire {
    link: Link,
    process: Option<Process>,
    mux: bool,
    ids: i64,
    numbers: i64,
    gone: bool,
    awaited: HashSet<i64>,
    answers: HashMap<i64, Json>,
    runs: BTreeSet<i64>,
    streams: BTreeMap<i64, VecDeque<Json>>,
    feeds: BTreeMap<i64, Feed>,
}

fn lost() -> Condition {
    Condition::new("WorkerDied", "the far side's wire ended")
}

fn failure(message: &Json) -> Option<Condition> {
    let text = |tree: &Json| if let Json::Str(text) = tree { text.clone() } else { tree.to_string() };
    match message.get("condition") {
        Json::Null => None,
        c => Some(Condition::new(&text(c.get("kind")), text(c.get("message")))),
    }
}

impl Wire {
    /// a request written; its answer is kept only if somebody will wait for it
    fn send(&mut self, op: &str, fields: Vec<(&str, Json)>) -> i64 {
        self.ids += 1;
        let request = [("id", Json::Int(self.ids)), ("op", Json::str(op))].into_iter().chain(fields);
        if self.link.send(&Json::Obj(request.map(|(name, value)| (name.to_string(), value)).collect())).is_err() {
            self.gone = true;
        }
        self.ids
    }

    /// THE WIRE MOVES ONE STEP, while the program waits for something: a value is fed where the far side has
    /// given credit; otherwise a message is read, and put where it is waited for.
    fn turn(&mut self) -> Result<(), Condition> {
        // the first stream with a value to feed and credit for it, or with none left: that value, or the end
        let ready = self.feeds.iter_mut().find_map(|(&stream, feed)| {
            let ready = feed.values.peek().is_none() || feed.credit > 0;
            ready.then(|| {
                feed.credit -= 1;
                (stream, feed.values.next())
            })
        });
        match ready {
            Some((stream, Some(value))) => {
                self.send("chunk", vec![("stream", Json::Int(stream)), ("chunk", enc(&value)?)]);
                return Ok(());
            }
            Some((stream, None)) => {
                self.feeds.remove(&stream);
                self.send("end", vec![("stream", Json::Int(stream))]);
                return Ok(());
            }
            None => {}
        }
        let Ok(Some(message)) = self.link.receive() else {
            self.gone = true;
            return Ok(());
        };
        match (message.get("id"), message.get("stream"), message.get("credit")) {
            (Json::Int(id), _, _) => {
                if self.awaited.remove(id) {
                    self.answers.insert(*id, message);
                }
            }
            (_, Json::Int(stream), Json::Int(credit)) => {
                if let Some(feed) = self.feeds.get_mut(stream) {
                    feed.credit += credit;
                }
            }
            (_, Json::Int(stream), _) => {
                if let Some(messages) = self.streams.get_mut(stream) {
                    messages.push_back(message);
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn receive(&mut self, id: i64) -> Result<Json, Condition> {
        while !self.gone && !self.answers.contains_key(&id) {
            self.turn()?;
        }
        let answer = self.answers.remove(&id).ok_or_else(lost)?;
        failure(&answer).map_or_else(|| Ok(answer.get("ok").clone()), Err)
    }

    fn open(&mut self, call: Opening) -> Result<i64, Condition> {
        if !self.mux {
            let why = "the far side claims no mux: a stream needs answers matched by id";
            return Err(Condition::new("NotStreaming", why));
        }
        self.numbers += 1;
        let stream = self.numbers;
        // the stream's own number is the far side's; this side's input to the same call has the number negated
        let input = Json::obj([("id", Json::Int(-stream)), ("credit", Json::Int(call.credit))]);
        let (name, credit) = (Json::Str(call.name), Json::Int(call.credit));
        let fields = [("fn", name), ("args", call.args), ("stream", Json::Int(stream)), ("credit", credit)];
        self.streams.insert(stream, VecDeque::new());
        let id =
            self.send("call", fields.into_iter().chain(call.input.is_some().then_some(("input", input))).collect());
        self.awaited.insert(id);
        if let Err(c) = self.receive(id) {
            self.streams.remove(&stream);
            return Err(c);
        }
        if let Some(values) = call.input {
            self.feeds.insert(-stream, Feed { values: values.peekable(), credit: call.credit });
        }
        Ok(stream)
    }

    fn pull(&mut self, stream: i64) -> Result<Option<Json>, Condition> {
        if !self.streams.contains_key(&stream) {
            return Err(Condition::new("LookupError", format!("stream {stream} is not open")));
        }
        while !self.gone && self.streams[&stream].is_empty() {
            self.turn()?;
        }
        let message = self.streams.get_mut(&stream).and_then(VecDeque::pop_front);
        if let Some(message) = &message
            && message.get("end") != &Json::Bool(true)
            && failure(message).is_none()
        {
            // taken: the far side may send one more
            self.send("credit", vec![("stream", Json::Int(stream)), ("credit", Json::Int(1))]);
            return Ok(Some(message.get("chunk").clone()));
        }
        self.streams.remove(&stream);
        self.feeds.remove(&-stream);
        message.map_or_else(|| Err(lost()), |message| failure(&message).map_or(Ok(None), Err))
    }
}

/// THE HOST'S END of a wire to a far side.
pub struct Remote(Rc<RefCell<Wire>>);

impl Remote {
    /// Opening it is the handshake: the far side's hello, the proof of the `secret` if there is one, and
    /// `configure` when CBOR or DEFLATE is asked for; each mismatch is refused by name.
    pub fn open(
        input: impl Read + 'static,
        output: impl Write + 'static,
        cbor: bool,
        deflate: bool,
        secret: Option<&str>,
    ) -> Result<Remote, Condition> {
        let mut link = Link::new(input, output);
        let wire = |why: String| Condition::new("WireError", why);
        let hello = link.receive().map_err(wire)?.ok_or_else(|| wire("the far side said nothing".to_string()))?;
        if hello.get("fatal") != &Json::Null {
            return Err(wire(format!("the far side cannot run: {}", hello.get("fatal"))));
        }
        if hello.get("shim") != &Json::Int(SHIM) {
            return Err(Condition::new("Shim", format!("the far side said {hello}")));
        }
        authenticate(&mut link, &hello, secret)?;
        if cbor || deflate {
            configure(&mut link, &hello, cbor, deflate)?;
        }
        Ok(Remote(Rc::new(RefCell::new(Wire {
            link,
            process: None,
            mux: hello.get("speaks").get("mux") == &Json::Bool(true),
            ids: 0,
            numbers: 0,
            gone: false,
            awaited: HashSet::new(),
            answers: HashMap::new(),
            runs: BTreeSet::new(),
            streams: BTreeMap::new(),
            feeds: BTreeMap::new(),
        }))))
    }

    /// A far side started as a process, speaking the wire on its standard input and output; a `secret` is given
    /// to it as OKAY_WIRE_SECRET.
    pub fn start(command: &[String], cbor: bool, deflate: bool, secret: Option<&str>) -> Result<Remote, Condition> {
        let mut far = Command::new(&command[0]);
        far.args(&command[1..]).stdin(Stdio::piped()).stdout(Stdio::piped());
        if let Some(secret) = secret {
            far.env("OKAY_WIRE_SECRET", secret);
        }
        let started = far.spawn().map_err(|e| Condition::new("WireError", format!("{}: {e}", command[0])))?;
        let mut process = Process(started);
        let (input, output) = (process.0.stdout.take(), process.0.stdin.take());
        let (input, output) = (input.expect("the far side's output"), output.expect("the far side's input"));
        let remote = Remote::open(input, output, cbor, deflate, secret)?;
        remote.0.borrow_mut().process = Some(process);
        Ok(remote)
    }

    /// Whether the far side answers requests as they finish, not in the order they came.
    pub fn mux(&self) -> bool {
        self.0.borrow().mux
    }

    /// THE PROGRAM RUN to its value, its far operations done on this wire. When it ends, what it left open on the
    /// far side is closed: its runs forgotten, its streams cancelled. Refs live on, until released.
    pub fn run<A: 'static>(&self, p: Comp<row![Far], A>) -> A {
        let wire = self.0.clone();
        let answer = move |_: &mut (), op| {
            let mut wire = wire.borrow_mut();
            match op {
                Far::Send(name, fields, r) => {
                    let id = wire.send(name, fields);
                    wire.awaited.insert(id);
                    r.give(id)
                }
                Far::Receive(id, r) => r.give(wire.receive(id)),
                Far::Run(r) => {
                    wire.numbers += 1;
                    let run = wire.numbers;
                    wire.runs.insert(run);
                    r.give(run)
                }
                Far::Open(call, r) => r.give(wire.open(call)),
                Far::Pull(stream, r) => r.give(wire.pull(stream)),
            }
        };
        let value = Handler::in_place((), |_, a| pure(a), answer).handle(p).run();
        let mut wire = self.0.borrow_mut();
        for stream in std::mem::take(&mut wire.streams).into_keys() {
            wire.send("cancel", vec![("stream", Json::Int(stream))]);
        }
        for run in std::mem::take(&mut wire.runs) {
            wire.send("forget", vec![("run", Json::Int(run))]);
        }
        wire.feeds.clear();
        value
    }
}

fn authenticate(link: &mut Link, hello: &Json, secret: Option<&str>) -> Result<(), Condition> {
    let refused = |why: String| Err(Condition::new("PermissionError", why));
    match (hello.get("auth").get("nonce"), secret) {
        (Json::Str(far), Some(secret)) if hello.get("auth").get("scheme") == &Json::str("hmac-sha256") => {
            let mine = auth::nonce();
            let proof = Json::Str(auth::mac(secret, "client", far, &mine));
            let _ = link.send(&Json::obj([("op", Json::str("auth")), ("nonce", Json::str(&mine)), ("mac", proof)]));
            let answer = link.receive().ok().flatten().unwrap_or(Json::Null);
            match answer.get("ok").get("mac") {
                Json::Str(proof) if auth::same(proof, &auth::mac(secret, "server", far, &mine)) => Ok(()),
                _ => refused("the far side did not prove the secret".to_string()),
            }
        }
        (Json::Null, None) => Ok(()),
        (Json::Null, Some(_)) => {
            refused("this side has a secret and the far side asks for no authentication".to_string())
        }
        _ => refused(format!("the far side asks for authentication: {}", hello.get("auth"))),
    }
}

fn configure(link: &mut Link, hello: &Json, cbor: bool, deflate: bool) -> Result<(), Condition> {
    let speaks = |what: &str, name: &str| match items(hello.get("speaks").get(what)).contains(&Json::str(name)) {
        true => Ok(()),
        false => Err(Condition::new("WireError", format!("the far side does not speak the {what} {name}: {hello}"))),
    };
    if cbor {
        speaks("format", "cbor")?;
    }
    if deflate {
        speaks("compress", "deflate")?;
    }
    let format = Json::str(if cbor { "cbor" } else { "json" });
    let compress = Json::str(if deflate { "deflate" } else { "none" });
    let _ = link.send(&Json::obj([("op", Json::str("configure")), ("format", format), ("compress", compress)]));
    let answer = link.receive().ok().flatten().unwrap_or(Json::Null);
    if answer.get("ok") == &Json::Null {
        return Err(Condition::new("WireError", format!("configure was refused: {answer}")));
    }
    link.configure(cbor, deflate);
    Ok(())
}
