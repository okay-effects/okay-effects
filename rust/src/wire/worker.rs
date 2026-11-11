//! THE FAR SIDE: a module served to a host, over the wire. Every function is a program, and what it asks decides
//! what crosses:
//!
//!   Foreign::Call   a `perform` node — the host answers, and continues the program (as often as it likes)
//!   Emit::Send      a chunk of the call's stream, when the host has given credit; the program waits for it
//!   Fed::Next       a value of the stream the host feeds, waited for; taking one gives the host a credit
//!   Async::Await    the program waits for the callback, and other requests are served meanwhile
//!   Throws::Raise   the call's condition
//!
//! A program that waits is a continuation kept: no thread waits with it.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};
use std::io::{Read, Write};
use std::rc::Rc;

use crate::effects::{Async, Emit, Resume, Throws};
use crate::json::{Json, Ref};
use crate::wire::escapes::{dec, enc, items};
use crate::wire::{Condition, Foreign, Link, SHIM, auth};
use crate::{Asked, Comp, Handler, Has, K, Prog, Reply, Resumed, Step, eff, perform, pure};

/// THE STREAM A CALLER FEEDS into a function of the far side: the function takes a value at a time.
pub enum Fed {
    Next(Reply<Option<Json>>),
}

impl crate::Effect for Fed {
    type Kind = crate::Plain;
}

impl Fed {
    /// The caller's next value, waited for; none at the stream's end.
    pub fn next<R: Has<Self, I>, I>() -> impl Prog<Option<Json>, R> {
        perform(Fed::Next)
    }
}

/// what a function of a module may ask
pub type Row = eff![Foreign, Emit<Json>, Fed, Async, Throws<Condition>];
/// a function of a module: the program of a call, its type forgotten
pub type Function = Box<dyn Fn(&[Json]) -> Comp<Json, Row>>;

/// An object kept for the host: its methods and attributes, by name; none for a name it has not.
pub trait Thing {
    fn kind(&self) -> &str;
    fn method(&mut self, name: &str, args: &[Json]) -> Option<Json>;
    fn attr(&self, name: &str) -> Option<Json>;
}

/// a function that makes a `Thing`
pub type Class = Box<dyn Fn(&[Json]) -> Result<Box<dyn Thing>, Condition>>;

/// Functions by name, and classes.
#[derive(Default)]
pub struct Module {
    pub functions: HashMap<&'static str, Function>,
    pub classes: HashMap<&'static str, Class>,
}

/// The module served until the host closes the wire; with a `secret`, to a host that proves it has the same.
pub fn serve(module: Module, input: impl Read + 'static, output: impl Write + 'static, secret: Option<String>) {
    let worker = Rc::new(Worker {
        module,
        link: RefCell::new(Link::new(input, output)),
        secret,
        nonce: auth::nonce(),
        proven: Cell::default(),
        closing: Cell::default(),
        kept: RefCell::default(),
        counter: Cell::default(),
        held: RefCell::default(),
        refs: Cell::default(),
        streams: RefCell::default(),
        inputs: RefCell::default(),
    });
    worker.hello();
    while !worker.closing.get() {
        let received = worker.link.borrow_mut().receive();
        match received {
            Ok(Some(request)) => worker.serve(&request),
            Ok(None) => break,
            Err(why) => worker.reply(&Json::Null, failed(&Condition::new("ValueError", why))),
        }
    }
}

// -- A FUNCTION, AS THE WORKER RUNS IT. The machine shows an operation it stopped at only by reference, and an
// awaited answer's `register` is to be called once, by value. So `Async` is handled first, by a handler that
// gives the wait out as the program's value; the other four are met by stepping.

/// what a function asks the worker, a step at a time
type Asks = eff![Foreign, Emit<Json>, Fed, Throws<Condition>];

/// Where a function came to, when it asks nothing: its value, or a wait — to be given what goes on with the rest.
enum Came {
    Value(Json),
    Waits(Box<dyn FnOnce(Resume<Resumed<Came, Asks>>)>),
}

fn stepped(p: Comp<Json, Row>) -> Step<Came, Asks> {
    let on = |op, k: K<Came, Asks>| match op {
        Async::Await(register, r) => {
            pure(Came::Waits(Box::new(move |go| register(Rc::new(move |x| go(k.resume(r, x)))))))
        }
    };
    Handler::new(|value| pure(Came::Value(value)), on).handle(p).step()
}

/// a function stopped at an operation it waits to be answered, and what it answers to
type Parked = RefCell<Option<(Task, Asked<Came, Asks>)>>;

/// a stream the host feeds: the values come and not yet taken; its function waits for one
struct In {
    id: i64,
    values: RefCell<VecDeque<Json>>,
    ended: Cell<bool>,
    waiting: Parked,
}

/// a stream this side feeds: the credit left; its function waits for more
struct Out {
    id: i64,
    credit: Cell<i64>,
    input: Option<Rc<In>>,
    open: Cell<bool>,
    waiting: Parked,
}

/// what a running function answers to
#[derive(Clone)]
enum Task {
    /// a call: its value, or a ref to it when held
    Value(Json, bool),
    /// a step of a program, of a run: a node
    Program(Json, i64),
    /// a stream: chunks, then its end
    Stream(Rc<Out>),
}

enum Held {
    Value(Json),
    Thing(Box<dyn Thing>),
}

fn failed(c: &Condition) -> (&'static str, Json) {
    ("condition", Json::obj([("kind", Json::str(&c.kind)), ("message", Json::str(&c.message))]))
}

fn number(tree: &Json) -> Result<i64, Condition> {
    match tree {
        Json::Int(n) => Ok(*n),
        other => Err(Condition::new("ValueError", format!("a number was expected: {other}"))),
    }
}

fn not_held(id: i64) -> Condition {
    let why = format!("ref {id} is not held by this worker (released, or held by a process that is gone)");
    Condition::new("LookupError", why)
}

/// The worker's state is in cells, each borrowed for a moment: a function, while it runs, may call back in — a
/// callback that wakes another function is answered through this same worker.
struct Worker {
    module: Module,
    link: RefCell<Link>,
    secret: Option<String>,
    nonce: String,
    proven: Cell<bool>,
    closing: Cell<bool>,
    /// the programs stopped at a call, by run and `k`
    kept: RefCell<HashMap<(i64, i64), Asked<Came, Asks>>>,
    counter: Cell<i64>,
    held: RefCell<HashMap<i64, Held>>,
    refs: Cell<i64>,
    streams: RefCell<HashMap<i64, Rc<Out>>>,
    inputs: RefCell<HashMap<i64, Rc<In>>>,
}

impl Worker {
    fn hello(&self) {
        let speaks = [
            ("format", Json::Arr(vec![Json::str("json"), Json::str("cbor")])),
            ("compress", Json::Arr(vec![Json::str("deflate")])),
            ("mux", Json::Bool(true)),
        ];
        let mut hello = vec![("shim", Json::Int(SHIM)), ("python", Json::str("rust")), ("speaks", Json::obj(speaks))];
        if self.secret.is_some() {
            hello.push(("auth", Json::obj([("scheme", Json::str("hmac-sha256")), ("nonce", Json::str(&self.nonce))])));
        }
        self.send(Json::Obj(hello.into_iter().map(|(name, value)| (name.to_string(), value)).collect()));
    }

    /// one request served
    fn serve(self: &Rc<Self>, request: &Json) {
        let id = request.get("id");
        let served = if self.proven.get() || self.secret.is_none() {
            self.operation(id, request)
        } else {
            self.authenticate(id, request)
        };
        if let Err(c) = served {
            self.reply(id, failed(&c));
        }
    }

    fn operation(self: &Rc<Self>, id: &Json, request: &Json) -> Result<(), Condition> {
        let op = if let Json::Str(op) = request.get("op") { op.as_str() } else { "" };
        match op {
            "call" => self.call(id, request)?,
            "program" => {
                let task = Task::Program(id.clone(), number(request.get("run"))?);
                let function = self.function(request.get("fn"))?;
                self.advance(&task, stepped(function(&self.arguments(request)?)));
            }
            "continue" => self.resume(id, request)?,
            "forget" => {
                let run = number(request.get("run"))?;
                self.kept.borrow_mut().retain(|key, _| key.0 != run);
                self.reply(id, ("ok", Json::Null));
            }
            "release" => {
                let released = self.held.borrow_mut().remove(&number(request.get("ref"))?);
                drop(released);
                self.reply(id, ("ok", Json::Null));
            }
            "credit" => {
                let (stream, credit) = (number(request.get("stream"))?, number(request.get("credit"))?);
                self.reply(id, ("ok", Json::Null));
                let out = self.streams.borrow().get(&stream).cloned();
                if let Some(out) = out {
                    out.credit.set(out.credit.get() + credit);
                    self.wake(&out.waiting);
                }
            }
            "chunk" => {
                let value = self.resolved(&dec(request.get("chunk"))?)?;
                let input = self.inputs.borrow().get(&number(request.get("stream"))?).cloned();
                self.reply(id, ("ok", Json::Null));
                if let Some(input) = input {
                    input.values.borrow_mut().push_back(value);
                    self.wake(&input.waiting);
                }
            }
            "end" => {
                let input = self.inputs.borrow().get(&number(request.get("stream"))?).cloned();
                self.reply(id, ("ok", Json::Null));
                if let Some(input) = input {
                    input.ended.set(true);
                    self.wake(&input.waiting);
                }
            }
            "cancel" => {
                let out = self.streams.borrow().get(&number(request.get("stream"))?).cloned();
                if let Some(out) = out {
                    self.close(&out);
                }
                self.reply(id, ("ok", Json::Null));
            }
            "configure" => self.configure(id, request)?,
            _ => return Err(Condition::new("ValueError", format!("no operation {}", request.get("op")))),
        }
        Ok(())
    }

    // -- the answers

    fn send(&self, message: Json) {
        // a host that reads no more has gone
        if self.link.borrow_mut().send(&message).is_err() {
            self.closing.set(true);
        }
    }

    fn reply(&self, id: &Json, answer: (&str, Json)) {
        self.send(Json::obj([("id", id.clone()), answer]));
    }

    /// a call's value answered, or kept and a ref to it answered
    fn answer(&self, id: &Json, held: bool, value: Json) -> Result<(), Condition> {
        let ok = if held { self.hold(Held::Value(value)) } else { enc(&value)? };
        self.reply(id, ("ok", ok));
        Ok(())
    }

    // -- the request's parts

    fn function(&self, name: &Json) -> Result<&Function, Condition> {
        let Json::Str(name) = name else {
            return Err(Condition::new("ValueError", format!("a function's name was expected: {name}")));
        };
        let none = || Condition::new("LookupError", format!("no function {name}"));
        self.module.functions.get(name.as_str()).ok_or_else(none)
    }

    /// the arguments, as values
    fn arguments(&self, request: &Json) -> Result<Vec<Json>, Condition> {
        items(request.get("args")).iter().map(|arg| self.resolved(&dec(arg)?)).collect()
    }

    /// the value, with what each ref in it names: a ref crosses back as the value held
    fn resolved(&self, value: &Json) -> Result<Json, Condition> {
        Ok(match value {
            Json::Ref(Ref { id, .. }) => match self.held.borrow().get(id) {
                Some(Held::Value(value)) => value.clone(),
                Some(Held::Thing(thing)) => {
                    let why = format!("ref {id} is a {}: used by its methods, not given", thing.kind());
                    return Err(Condition::new("TypeError", why));
                }
                None => return Err(not_held(*id)),
            },
            Json::Arr(values) => Json::Arr(values.iter().map(|value| self.resolved(value)).collect::<Result<_, _>>()?),
            Json::Obj(fields) => {
                let resolved = |(name, field): &(String, Json)| Ok((name.clone(), self.resolved(field)?));
                Json::Obj(fields.iter().map(resolved).collect::<Result<_, _>>()?)
            }
            plain => plain.clone(),
        })
    }

    /// the value or the object kept, and the ref that names it — written as its escape
    fn hold(&self, what: Held) -> Json {
        let kind = match &what {
            Held::Thing(thing) => thing.kind(),
            Held::Value(Json::Arr(_)) => "list",
            Held::Value(Json::Obj(_)) => "dict",
            Held::Value(_) => "value",
        };
        let named = Ref { id: self.refs.get() + 1, kind: kind.to_string() };
        self.refs.set(named.id);
        self.held.borrow_mut().insert(named.id, what);
        Json::Ref(named)
    }

    // -- the operations

    fn call(self: &Rc<Self>, id: &Json, request: &Json) -> Result<(), Condition> {
        let args = self.arguments(request)?;
        let held = request.get("held") == &Json::Bool(true);
        match (request.get("fn"), request.get("stream")) {
            (name, Json::Int(stream)) => self.open(id, *stream, request, self.function(name)?(&args)),
            (Json::Str(name), _) if self.module.classes.contains_key(name.as_str()) => {
                if !held {
                    let why = format!("{name} makes an object: it crosses only held, as a ref");
                    return Err(Condition::new("TypeError", why));
                }
                let thing = self.module.classes[name.as_str()](&args)?;
                self.reply(id, ("ok", self.hold(Held::Thing(thing))));
            }
            (name @ Json::Str(_), _) => {
                self.advance(&Task::Value(id.clone(), held), stepped(self.function(name)?(&args)))
            }
            (at, _) => match (at.get("ref"), at.get("method"), at.get("attr")) {
                (Json::Int(at), Json::Str(name), _) => {
                    self.answer(id, held, self.member(*at, name, |thing| thing.method(name, &args))?)?
                }
                (Json::Int(at), _, Json::Str(name)) => {
                    self.answer(id, held, self.member(*at, name, |thing| thing.attr(name))?)?
                }
                _ => {
                    let why = format!("a call's address is a name, a method or an attribute: {at}");
                    return Err(Condition::new("ValueError", why));
                }
            },
        }
        Ok(())
    }

    /// a method's answer or an attribute, of an object held
    fn member(
        &self,
        at: i64,
        name: &str,
        find: impl FnOnce(&mut dyn Thing) -> Option<Json>,
    ) -> Result<Json, Condition> {
        match self.held.borrow_mut().get_mut(&at) {
            Some(Held::Thing(thing)) => {
                let none = format!("a {} has no {name}", thing.kind());
                find(thing.as_mut()).ok_or_else(|| Condition::new("LookupError", none))
            }
            Some(Held::Value(_)) => {
                Err(Condition::new("LookupError", format!("ref {at} is a value: it has no {name}")))
            }
            None => Err(not_held(at)),
        }
    }

    /// a call as a stream: answered at once, its chunks follow as the host gives credit
    fn open(self: &Rc<Self>, id: &Json, stream: i64, request: &Json, p: Comp<Json, Row>) {
        let input = match request.get("input").get("id") {
            Json::Int(fed) => Some(Rc::new(In {
                id: *fed,
                values: RefCell::default(),
                ended: Cell::default(),
                waiting: RefCell::default(),
            })),
            _ => None,
        };
        let credit = if let Json::Int(credit) = request.get("credit") { *credit } else { 1 };
        let (credit, open) = (Cell::new(credit), Cell::new(true));
        let out = Rc::new(Out { id: stream, credit, input, open, waiting: RefCell::default() });
        self.streams.borrow_mut().insert(stream, out.clone());
        if let Some(input) = &out.input {
            self.inputs.borrow_mut().insert(input.id, input.clone());
        }
        self.reply(id, ("ok", Json::Null));
        self.advance(&Task::Stream(out), stepped(p));
    }

    /// `continue`: the rest of a program kept, resumed with the host's answer — as often as the host asks
    fn resume(self: &Rc<Self>, id: &Json, request: &Json) -> Result<(), Condition> {
        let (run, k) = (number(request.get("run"))?, number(request.get("k"))?);
        if request.get("condition") != &Json::Null {
            let why = "a program as data is continued with an answer, not a condition";
            return Err(Condition::new("ValueError", why));
        }
        let rest = {
            let kept = self.kept.borrow();
            let Some(asked) = kept.get(&(run, k)) else {
                return Err(Condition::new("LookupError", format!("no continuation {k} of run {run}")));
            };
            let Some(Foreign::Call(_, _, r)) = asked.op() else { unreachable!("what is kept stopped at a call") };
            asked.resume(*r, self.resolved(&dec(request.get("answer"))?)?)
        };
        self.advance(&Task::Program(id.clone(), run), rest.step());
        Ok(())
    }

    fn configure(&self, id: &Json, request: &Json) -> Result<(), Condition> {
        let (format, compress) = (request.get("format"), request.get("compress"));
        let (cbor, deflate) = (format == &Json::str("cbor"), compress == &Json::str("deflate"));
        if !cbor && format != &Json::str("json") {
            let why = format!("this worker speaks the formats json, cbor; not {format}");
            return Err(Condition::new("ValueError", why));
        }
        if !deflate && compress != &Json::str("none") {
            let why = format!("this worker speaks the compressions none, deflate; not {compress}");
            return Err(Condition::new("ValueError", why));
        }
        // the answer goes as the request came; the next message is the first in the new way
        self.reply(id, ("ok", Json::obj([("format", format.clone()), ("compress", compress.clone())])));
        self.link.borrow_mut().configure(cbor, deflate);
        Ok(())
    }

    /// until the host has proved the secret, only its proof is heard; a wrong one closes the wire
    fn authenticate(&self, id: &Json, request: &Json) -> Result<(), Condition> {
        let secret = self.secret.as_deref().unwrap_or_default();
        let proves = |host: &str, mac: &str| {
            !host.is_empty() && auth::same(mac, &auth::mac(secret, "client", &self.nonce, host))
        };
        match (request.get("op"), request.get("nonce"), request.get("mac")) {
            (Json::Str(op), Json::Str(host), Json::Str(mac)) if op == "auth" && proves(host, mac) => {
                self.proven.set(true);
                let mine = auth::mac(secret, "server", &self.nonce, host);
                self.reply(id, ("ok", Json::obj([("mac", Json::Str(mine))])));
                Ok(())
            }
            (Json::Str(op), _, _) if op == "auth" => {
                self.closing.set(true);
                let why = "authentication refused: the mac does not prove this worker's secret";
                Err(Condition::new("PermissionError", why))
            }
            _ => Err(Condition::new("PermissionError", "this worker requires hmac-sha256 authentication first")),
        }
    }

    // -- a function, run

    /// the task's program run until it ends or waits: for the host's answer, a credit, a value, a callback
    fn advance(self: &Rc<Self>, task: &Task, mut at: Step<Came, Asks>) {
        loop {
            match self.turn(task, at) {
                Ok(Some(rest)) => at = rest.step(),
                Ok(None) => return,
                Err(c) => return self.fail(task, &c),
            }
        }
    }

    /// where the program came to: what goes to the host, and the rest of the program when it need not wait
    fn turn(self: &Rc<Self>, task: &Task, at: Step<Came, Asks>) -> Result<Option<Resumed<Came, Asks>>, Condition> {
        if matches!(task, Task::Stream(out) if !out.open.get()) {
            return Ok(None);
        }
        let asked = match at {
            Step::Asked(asked) => asked,
            Step::Done(Came::Waits(wait)) => {
                let (worker, task) = (Rc::downgrade(self), task.clone());
                wait(Rc::new(move |rest: Resumed<Came, Asks>| {
                    if let Some(worker) = worker.upgrade() {
                        worker.advance(&task, rest.step())
                    }
                }));
                return Ok(None);
            }
            Step::Done(Came::Value(value)) => {
                match task {
                    Task::Value(id, held) => self.answer(id, *held, value)?,
                    Task::Program(id, _) => self.reply(id, ("ok", Json::obj([("done", enc(&value)?)]))),
                    Task::Stream(out) => {
                        self.close(out);
                        self.send(Json::obj([("stream", Json::Int(out.id)), ("end", Json::Bool(true))]));
                    }
                }
                return Ok(None);
            }
        };
        if let Some(Throws::Raise(c, _)) = asked.op() {
            return Err(Condition::clone(c));
        }
        if let Some(Foreign::Call(name, args, _)) = asked.op() {
            let Task::Program(id, run) = task else {
                let why = format!("{name} is asked of the host: a call offers no callbacks, a program does");
                return Err(Condition::new("LookupError", why));
            };
            let k = self.counter.get() + 1;
            let args = args.iter().map(enc).collect::<Result<_, _>>()?;
            let node = [("perform", Json::str(name)), ("args", Json::Arr(args)), ("k", Json::Int(k))];
            self.counter.set(k);
            self.kept.borrow_mut().insert((*run, k), asked);
            self.reply(id, ("ok", Json::obj(node)));
            return Ok(None);
        }
        if let Some(Emit::Send(value, r)) = asked.op() {
            let Task::Stream(out) = task else {
                let why = "a value is sent to no stream: the call was not opened as one";
                return Err(Condition::new("LookupError", why));
            };
            if out.credit.get() == 0 {
                out.waiting.replace(Some((task.clone(), asked)));
                return Ok(None);
            }
            out.credit.set(out.credit.get() - 1);
            self.send(Json::obj([("stream", Json::Int(out.id)), ("chunk", enc(value)?)]));
            return Ok(Some(asked.resume(*r, ())));
        }
        let Some(Fed::Next(r)) = asked.op() else { unreachable!("the row has no other effect") };
        // a call that was given no stream to take from finds its end at once
        let input = if let Task::Stream(out) = task { out.input.as_ref() } else { None };
        let Some(input) = input else { return Ok(Some(asked.resume(*r, None))) };
        let taken = input.values.borrow_mut().pop_front();
        if taken.is_none() && !input.ended.get() {
            input.waiting.replace(Some((task.clone(), asked)));
            return Ok(None);
        }
        if taken.is_some() {
            // taken: the host may send one more
            self.send(Json::obj([("stream", Json::Int(input.id)), ("credit", Json::Int(1))]));
        }
        Ok(Some(asked.resume(*r, taken)))
    }

    /// a function that waited goes on
    fn wake(self: &Rc<Self>, waiting: &Parked) {
        let parked = waiting.take();
        if let Some((task, asked)) = parked {
            self.advance(&task, Step::Asked(asked));
        }
    }

    fn fail(&self, task: &Task, c: &Condition) {
        match task {
            Task::Value(id, _) | Task::Program(id, _) => self.reply(id, failed(c)),
            Task::Stream(out) => {
                if out.open.get() {
                    self.send(Json::obj([("stream", Json::Int(out.id)), failed(c)]));
                }
                self.close(out);
            }
        }
    }

    /// the stream is over, by its end, its failure or the host's cancel: its function is not resumed
    fn close(&self, out: &Out) {
        out.open.set(false);
        out.waiting.take();
        self.streams.borrow_mut().remove(&out.id);
        if let Some(input) = &out.input {
            input.waiting.take();
            self.inputs.borrow_mut().remove(&input.id);
        }
    }
}
