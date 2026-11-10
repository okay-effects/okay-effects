//! THE OPERATION THAT CROSSES a process: a name and arguments in JSON, answered in JSON. A program written over it
//! runs in one process, in one language; its handlers may be in another. Only this data crosses — a continuation
//! stays where it was made, known outside by a number. The messages are the okay wire's (SPEC.md, "The wire").

use std::cell::RefCell;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::rc::Rc;

use crate::json::Json;
use crate::{Asked, Comp, Op, Step, defer, perform, pure};

pub struct Call {
    pub name: String,
    pub args: Vec<Json>,
}

impl Op for Call {
    type Answer = Json;
}

pub fn call(name: &str, args: Vec<Json>) -> Comp<Json> {
    perform(Call { name: name.to_string(), args })
}

pub const SHIM: i64 = 9;

pub type Programs = HashMap<&'static str, Box<dyn Fn(&[Json]) -> Comp<Json>>>;

fn condition(kind: &str, message: String) -> Json {
    Json::obj([("condition", Json::obj([("kind", Json::str(kind)), ("message", Json::Str(message))]))])
}

/// THE FAR SIDE: programs by name, run a step at a time for a host; their continuations kept until forgotten.
pub fn serve(programs: &Programs, input: impl BufRead, mut output: impl Write) -> std::io::Result<()> {
    let mut kept: HashMap<(i64, i64), Asked<Json>> = HashMap::new();
    let mut counter = 0;

    let mut node = |kept: &mut HashMap<(i64, i64), Asked<Json>>, run: i64, p: Comp<Json>| match p.step() {
        Step::Done(value) => Json::obj([("ok", Json::obj([("done", value)]))]),
        Step::Asked(asked) => match asked.op::<Call>() {
            Some(call) => {
                counter += 1;
                let perform = [
                    ("perform", Json::str(&call.name)),
                    ("args", Json::Arr(call.args.clone())),
                    ("k", Json::Int(counter)),
                ];
                kept.insert((run, counter), asked);
                Json::obj([("ok", Json::obj(perform))])
            }
            None => condition("NoHandler", format!("an operation with no handler: {}", asked.name())),
        },
    };

    let hello = Json::obj([
        ("shim", Json::Int(SHIM)),
        ("rust", Json::str("okay-algebraic 0.1.0")),
        ("speaks", Json::obj([("format", Json::Arr(vec![Json::str("json")]))])),
    ]);
    writeln!(output, "{hello}")?;
    for line in input.lines() {
        let line = line?;
        if line.is_empty() {
            continue;
        }
        let reply = match Json::parse(&line) {
            Err(message) => condition("ValueError", message),
            Ok(m) => {
                let reply = match (m.get("op"), m.get("run"), m.get("fn"), m.get("k")) {
                    (Json::Str(op), Json::Int(run), Json::Str(name), _) if op == "program" => {
                        let args = if let Json::Arr(values) = m.get("args") { values.as_slice() } else { &[] };
                        match programs.get(name.as_str()) {
                            Some(program) => node(&mut kept, *run, program(args)),
                            None => condition("LookupError", format!("no program {name}")),
                        }
                    }
                    (Json::Str(op), Json::Int(run), _, Json::Int(k)) if op == "continue" => {
                        match kept.get(&(*run, *k)).map(|asked| asked.resume::<Call>(m.get("answer").clone())) {
                            Some(resumed) => node(&mut kept, *run, resumed),
                            None => condition("LookupError", format!("no continuation {k} of run {run}")),
                        }
                    }
                    (Json::Str(op), Json::Int(run), _, _) if op == "forget" => {
                        kept.retain(|key, _| key.0 != *run);
                        Json::obj([("ok", Json::Null)])
                    }
                    (op, _, _, _) => condition("LookupError", format!("no operation {op}")),
                };
                let Json::Obj(mut fields) = reply else { unreachable!("a reply is an object") };
                fields.insert(0, ("id".to_string(), m.get("id").clone()));
                Json::Obj(fields)
            }
        };
        writeln!(output, "{reply}")?;
        output.flush()?;
    }
    Ok(())
}

struct Far {
    process: Child,
    input: BufReader<ChildStdout>,
    output: ChildStdin,
    ids: i64,
    runs: i64,
}

impl Far {
    fn read(&mut self) -> Json {
        let mut line = String::new();
        self.input.read_line(&mut line).expect("the far side's answer");
        Json::parse(&line).expect("the far side's answer in JSON")
    }

    fn ask(&mut self, op: &str, fields: Vec<(&str, Json)>) -> Json {
        self.ids += 1;
        let mut all = vec![("id".to_string(), Json::Int(self.ids)), ("op".to_string(), Json::str(op))];
        all.extend(fields.into_iter().map(|(name, value)| (name.to_string(), value)));
        writeln!(self.output, "{}", Json::Obj(all)).expect("the far side listening");
        let reply = self.read();
        match reply.get("condition") {
            Json::Null => reply.get("ok").clone(),
            failed => panic!("the far side failed: {}: {}", failed.get("kind"), failed.get("message")),
        }
    }
}

impl Drop for Far {
    fn drop(&mut self) {
        let _ = self.process.kill();
        let _ = self.process.wait();
    }
}

/// THE HOST'S VIEW of a far side: a process speaking the wire on its standard input and output. A program of it is
/// a program here — what it asks is asked here as a `Call`, and answered by this side's handlers.
#[derive(Clone)]
pub struct Remote(Rc<RefCell<Far>>);

impl Remote {
    pub fn start(command: &[String]) -> std::io::Result<Remote> {
        let mut process =
            Command::new(&command[0]).args(&command[1..]).stdin(Stdio::piped()).stdout(Stdio::piped()).spawn()?;
        let input = BufReader::new(process.stdout.take().expect("the far side's output"));
        let output = process.stdin.take().expect("the far side's input");
        let mut far = Far { process, input, output, ids: 0, runs: 0 };
        let hello = far.read();
        assert_eq!(hello.get("shim"), &Json::Int(SHIM), "the far side said {hello}");
        Ok(Remote(Rc::new(RefCell::new(far))))
    }

    /// The far side's program `name`, as a program of this side.
    pub fn program(&self, name: &str, args: Vec<Json>) -> Comp<Json> {
        let (far, name) = (self.clone(), name.to_string());
        defer(move || {
            let run = {
                let mut far = far.0.borrow_mut();
                far.runs += 1;
                Json::Int(far.runs)
            };
            let fields = vec![("run", run.clone()), ("fn", Json::str(&name)), ("args", Json::Arr(args.clone()))];
            let ok = far.0.borrow_mut().ask("program", fields);
            far.node(run, ok)
        })
    }

    fn node(&self, run: Json, ok: Json) -> Comp<Json> {
        let (Json::Str(name), Json::Arr(args)) = (ok.get("perform"), ok.get("args")) else {
            return pure(ok.get("done").clone());
        };
        let (far, k) = (self.clone(), ok.get("k").clone());
        call(name, args.clone()).flat_map(move |answer| {
            let fields = vec![("run", run.clone()), ("k", k.clone()), ("answer", answer)];
            let ok = far.0.borrow_mut().ask("continue", fields);
            far.node(run.clone(), ok)
        })
    }
}
