//! The demonstration every language of this repository has, the same: a module served by a worker, and a host
//! that uses every feature of the wire on it, a line for each. Any host with any worker prints the same lines
//! (wire/expected.txt; the module is described in wire/README.md).
//! `demo worker`, `demo host <the worker's command>`.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fmt::Display;
use std::io::{stdin, stdout};
use std::rc::Rc;

use okay_effects::effects::{Async, Emit, Resume, Throws};
use okay_effects::json::{Json, Ref};
use okay_effects::wire::escapes::enc;
use okay_effects::wire::{Asking, Condition, Failing, Far, Fed, Foreign, Module, Remote, Row, Streaming, Thing};
use okay_effects::wire::{call, serve};
use okay_effects::{Comp, Handler, Has, K, Program, defer, pure};

// -- THE MODULE the worker serves; what it remembers is the worker's, for as long as it lives

fn ints(values: &[i64]) -> Vec<Json> {
    values.iter().map(|n| Json::Int(*n)).collect()
}

fn times(n: i64, x: Json) -> Json {
    match x {
        Json::Int(value) => Json::Int(value * n),
        Json::Dec(value) => Json::Dec(value * n as f64),
        other => other,
    }
}

fn total(items: Vec<Json>, i: usize, sum: i64) -> Comp<Json, Row> {
    if i == items.len() {
        return pure(Json::Int(sum)).boxed();
    }
    let price = call("price_of", vec![items[i].clone()]);
    let priced = move |price| match price {
        Json::Int(price) => total(items, i + 1, sum + price),
        other => Throws::raise(Condition::new("TypeError", format!("a price: {other}"))).boxed(),
    };
    price.flat_map(priced).boxed()
}

fn count(n: Json, done: i64) -> Comp<Json, Row> {
    if n == Json::Int(done) {
        return pure(n).boxed();
    }
    call("tick", vec![]).then(move || count(n, done + 1)).boxed()
}

/// each value is counted when it has gone out, not while the function waits for credit
fn numbers(next: i64, last: i64, sent: Rc<Cell<i64>>) -> Comp<Json, Row> {
    if next > last {
        return pure(Json::Null).boxed();
    }
    let counted = move || {
        sent.set(sent.get() + 1);
        numbers(next + 1, last, sent)
    };
    Emit::send(Json::Int(next)).then(counted).boxed()
}

fn dedup(seen: Rc<Vec<Json>>) -> Comp<Json, Row> {
    let fed = move |value| match value {
        None => pure(Json::Null).boxed(),
        Some(value) if seen.contains(&value) => dedup(seen),
        Some(value) => {
            let seen = Rc::new([seen.as_slice(), std::slice::from_ref(&value)].concat());
            Emit::send(value).then(move || dedup(seen)).boxed()
        }
    };
    Fed::next().flat_map(fed).boxed()
}

struct Counter {
    n: i64,
}

impl Thing for Counter {
    fn kind(&self) -> &str {
        "t.Counter"
    }

    fn method(&mut self, name: &str, args: &[Json]) -> Option<Json> {
        let ("inc", [Json::Int(by)]) = (name, args) else { return None };
        self.n += by;
        Some(Json::Int(self.n))
    }

    fn attr(&self, name: &str) -> Option<Json> {
        (name == "n").then_some(Json::Int(self.n))
    }
}

/// a function of the module, from its cases; any other arguments are refused by name
fn function<P: Program<Json, Row>>(
    module: &mut Module,
    name: &'static str,
    cases: impl Fn(&[Json]) -> Option<P> + 'static,
) {
    let refused = move || Throws::raise(Condition::new("TypeError", format!("{name}: not its arguments"))).boxed();
    module.functions.insert(name, Box::new(move |args| cases(args).map_or_else(refused, Program::boxed)));
}

fn module() -> Module {
    let mut module = Module::default();
    function(&mut module, "t:add", |args| match args {
        [Json::Int(a), Json::Int(b)] => Some(pure(Json::Int(a + b))),
        _ => None,
    });
    function(&mut module, "t:echo", |args| match args {
        [value] => Some(pure(value.clone())),
        _ => None,
    });
    function(&mut module, "t:sum", |args| match args {
        [Json::Arr(values)] => {
            Some(pure(Json::Int(values.iter().map(|value| if let Json::Int(n) = value { *n } else { 0 }).sum())))
        }
        _ => None,
    });
    function(&mut module, "t:boom", |args| args.is_empty().then(|| Throws::raise(Condition::new("ValueError", "no"))));
    function(&mut module, "t:pairs", |args| {
        args.is_empty().then(|| call("choose", vec![Json::Arr(ints(&[1, 2]))]).map(|x| times(10, x)))
    });
    function(&mut module, "t:quote", |args| match args {
        [sku] => Some(call("price_of", vec![sku.clone()]).map(|x| times(2, x))),
        _ => None,
    });
    function(&mut module, "shop:total", |items| Some(total(items.to_vec(), 0, 0)));
    function(&mut module, "t:count", |args| match args {
        [n] => Some(count(n.clone(), 0)),
        _ => None,
    });

    // how many values the last `t:numbers` has sent
    let sent = Rc::new(Cell::new(0));
    let counted = sent.clone();
    function(&mut module, "t:numbers", move |args| match args {
        [Json::Int(n)] => {
            counted.set(0);
            Some(numbers(1, *n, counted.clone()))
        }
        _ => None,
    });
    function(&mut module, "t:sent", move |args| {
        let sent = sent.clone();
        args.is_empty().then(|| defer(move || pure(Json::Int(sent.get()))))
    });
    function(&mut module, "t:dedup", |args| args.is_empty().then(|| dedup(Rc::default())));

    // those who wait, by the name of what they wait for, in the order they came
    let waiting: Rc<RefCell<HashMap<String, Vec<Resume<Json>>>>> = Rc::default();
    let gates = waiting.clone();
    function(&mut module, "t:wait", move |args| match args {
        [Json::Str(name)] => {
            let (waiting, name) = (waiting.clone(), name.clone());
            Some(Async::wait(move |resume| waiting.borrow_mut().entry(name).or_default().push(resume)))
        }
        _ => None,
    });
    // each of those woken is answered before this call itself
    function(&mut module, "t:open", move |args| match args {
        [Json::Str(name)] => {
            let woken = gates.borrow_mut().remove(name).unwrap_or_default();
            woken.iter().for_each(|resume| resume(Json::str(name)));
            Some(pure(Json::Int(woken.len() as i64)))
        }
        _ => None,
    });

    let counter = |args: &[Json]| match args {
        [Json::Int(n)] => Ok(Box::new(Counter { n: *n }) as Box<dyn Thing>),
        _ => Err(Condition::new("TypeError", "t:counter: not its arguments")),
    };
    module.classes.insert("t:counter", Box::new(counter));
    module
}

// -- THE HOST

/// This side's answers: every choice taken, a price looked up, a tick counted.
fn answered<R: Has<Foreign, I>, I>(p: impl Program<Json, R>) -> impl Program<Vec<Json>, R::Rest> {
    let on = |op, k: K<Vec<Json>, R::Rest>| match op {
        Foreign::Call(name, args, r) => match (name.as_str(), args.as_slice()) {
            ("choose", [Json::Arr(values)]) => values.iter().fold(pure(Vec::new()).boxed(), |found, x| {
                let (k, x) = (k.clone(), x.clone());
                found.flat_map(move |found: Vec<Json>| k.resume(r, x).map(move |more| [found, more].concat())).boxed()
            }),
            ("price_of", [Json::Str(item)]) => k.resume(r, Json::Int(if item == "tea" { 3 } else { 2 })).boxed(),
            ("tick", _) => k.resume(r, Json::Null).boxed(),
            (name, _) => panic!("no answer for {name}"),
        },
    };
    Handler::new(|a| pure(vec![a]), on).handle(p)
}

/// the program's value on a far side, or the far side's failure
fn on<A: Display + 'static>(far: &Remote, p: impl Program<A, Failing>) -> String {
    match far.run(Throws::run(p)) {
        Ok(value) => value.to_string(),
        Err(Condition { kind, message }) => format!("{kind}: {message}"),
    }
}

fn all(p: impl Program<Json, Asking>) -> impl Program<Json, Failing> {
    answered(p).map(Json::Arr)
}

fn sent(p: impl Program<(), Streaming>) -> impl Program<Json, Failing> {
    Emit::collect(p).map(|(values, _)| Json::Arr(values))
}

/// THE HOST'S SCRIPT: a line for each feature, on three far sides: over JSON lines, over CBOR and DEFLATE, with a
/// secret.
fn script(command: &[String]) -> Result<Vec<String>, Condition> {
    let mut lines = Vec::new();
    let far = Remote::start(command, false, false, None)?;
    lines.push(format!("pairs {}", on(&far, all(Far::program("t:pairs", vec![], &[])))));
    let items = vec![Json::str("tea"), Json::str("milk")];
    lines.push(format!("total {}", on(&far, all(Far::program("shop:total", items, &[])))));
    lines.push(format!("count {}", on(&far, all(Far::program("t:count", ints(&[10000]), &[])))));
    lines.push(format!("call {}", on(&far, Far::call("t:add", ints(&[2, 3])))));

    // an object kept on the far side: its method, its attribute; released, it is refused by name
    let counter: Ref = far.run(Throws::run(Far::hold::<Failing, _>("t:counter", ints(&[10]))))?;
    let (kind, at) = (counter.kind.clone(), counter.clone());
    let used = Far::method(&counter, "inc", ints(&[5]))
        .flat_map(move |after: Json| Far::attr(&at, "n").map(move |n: Json| format!("{after} {n}")));
    lines.push(format!("held {kind} {}", on(&far, used)));
    let at = counter.clone();
    let gone = on(&far, Far::release(&counter).then(move || Far::attr(&at, "n")));
    lines.push(format!("released {}", gone.split(':').next().unwrap_or_default()));

    // a value kept: its ref, given to a call, is the value again
    let value = Far::hold("t:echo", vec![Json::Arr(ints(&[1, 2, 3]))]).flat_map(|list: Ref| {
        Far::call("t:sum", vec![Json::Ref(list.clone())]).flat_map(move |sum: Json| {
            let kind = list.kind.clone();
            Far::release(&list).map(move |_| format!("{kind} {sum}"))
        })
    });
    lines.push(format!("value {}", on(&far, value)));
    lines.push(format!("condition {}", on(&far, Far::call("t:boom", vec![]))));
    let a = Json::obj([("a", Json::Int(1))]);
    let odd = vec![Json::Int(9007199254740993), Json::Dec(f64::NAN), Json::Dec(3.0), Json::Dec(0.5), a];
    let shape = |value: Json| match enc(&value) {
        Ok(shape) => pure(shape).boxed(),
        Err(c) => Throws::raise(c).boxed(),
    };
    lines.push(format!("escapes {}", on(&far, Far::call("t:echo", vec![Json::Arr(odd)]).flat_map(shape))));

    lines.push(format!("stream {}", on(&far, sent(Far::stream("t:numbers", ints(&[5]), 2, None)))));
    // three taken of a hundred: the far side was two ahead, and was stopped there
    let three = Emit::take(3, Far::stream("t:numbers", ints(&[100]), 2, None));
    lines.push(format!("cancel {} {}", on(&far, sent(three)), on(&far, Far::call("t:sent", vec![]))));
    let fed = Emit::send_all(ints(&[3, 1, 3, 2, 1]));
    lines.push(format!("duplex {}", on(&far, sent(Far::stream("t:dedup", vec![], 2, Some(fed))))));

    // the second call is answered while the first still waits
    let mux = Far::start("t:wait", vec![Json::str("g")]).flat_map(|waits: i64| {
        Far::start("t:open", vec![Json::str("g")]).flat_map(move |opens: i64| {
            Far::result(opens).flat_map(move |opened: Json| {
                Far::result(waits).map(move |waited: Json| Json::Arr(vec![opened.clone(), waited]))
            })
        })
    });
    lines.push(format!("mux {}", on(&far, mux)));
    drop(far);

    let framed = Remote::start(command, true, true, None)?;
    let three = Far::call("t:add", ints(&[2, 3])).flat_map(|sum: Json| {
        all(Far::program("t:pairs", vec![], &[])).flat_map(move |pairs: Json| {
            let sum = sum.clone();
            sent(Far::stream("t:numbers", ints(&[3]), 1, None)).map(move |numbers| format!("{sum} {pairs} {numbers}"))
        })
    });
    lines.push(format!("cbor+deflate {}", on(&framed, three)));
    drop(framed);

    let proven = Remote::start(command, false, false, Some("a secret of the demonstration"))?;
    lines.push(format!("auth {}", on(&proven, Far::call("t:add", ints(&[2, 3])))));
    Ok(lines)
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("worker") if args.len() == 1 => {
            serve(module(), stdin().lock(), stdout().lock(), std::env::var("OKAY_WIRE_SECRET").ok())
        }
        Some("host") if args.len() > 1 => match script(&args[1..]) {
            Ok(lines) => lines.iter().for_each(|line| println!("{line}")),
            Err(Condition { kind, message }) => {
                eprintln!("{kind}: {message}");
                std::process::exit(1)
            }
        },
        _ => {
            eprintln!("usage: demo worker | demo host <the worker's command>");
            std::process::exit(2)
        }
    }
}
