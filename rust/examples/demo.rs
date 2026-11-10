//! The demonstration every language of this repository has, the same: three programs served by a worker, and a host
//! that runs a worker's programs with its own handlers. Any host with any worker prints the same lines.
//! `demo worker`, `demo host <the worker's command>`.

use std::io::{stdin, stdout};

use okay_algebraic::json::Json;
use okay_algebraic::wire::{Call, Programs, Remote, call, serve};
use okay_algebraic::{Comp, Handler, pure};

fn int(value: &Json) -> i64 {
    if let Json::Int(n) = value { *n } else { panic!("a whole number: {value}") }
}

fn total(items: Vec<Json>, i: usize, sum: i64) -> Comp<Json> {
    if i == items.len() {
        return pure(Json::Int(sum));
    }
    call("price_of", vec![items[i].clone()]).flat_map(move |price| total(items.clone(), i + 1, sum + int(&price)))
}

fn count(n: i64, done: i64) -> Comp<Json> {
    if done == n { pure(Json::Int(n)) } else { call("tick", vec![]).then(move || count(n, done + 1)) }
}

fn programs() -> Programs {
    let mut programs = Programs::new();
    programs.insert(
        "t:pairs",
        Box::new(|_| {
            call("choose", vec![Json::Arr(vec![Json::Int(1), Json::Int(2)])]).map(|x| Json::Int(int(&x) * 10))
        }),
    );
    programs.insert("shop:total", Box::new(|items| total(items.to_vec(), 0, 0)));
    programs.insert("t:count", Box::new(|args| count(int(&args[0]), 0)));
    programs
}

/// This side's answers: every choice taken, a price looked up, a tick counted.
fn answered(p: Comp<Json>) -> Comp<Vec<Json>> {
    Handler::new(|a: Json| pure(vec![a]))
        .on(|c: &Call, k| match (c.name.as_str(), c.args.as_slice()) {
            ("choose", [Json::Arr(values)]) => values.iter().fold(pure(Vec::new()), |found, x| {
                let (k, x) = (k.clone(), x.clone());
                found.flat_map(move |found: Vec<Json>| {
                    k.resume(x.clone()).map(move |more| [found.clone(), more].concat())
                })
            }),
            ("price_of", [Json::Str(item)]) => k.resume(Json::Int(if item == "tea" { 3 } else { 2 })),
            ("tick", _) => k.resume(Json::Null),
            (name, _) => panic!("no answer for {name}"),
        })
        .handle(p)
}

fn main() -> std::io::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("worker") => serve(&programs(), stdin().lock(), stdout().lock()),
        Some("host") if args.len() > 1 => {
            let far = Remote::start(&args[1..])?;
            let show = |name: &str, p: Comp<Json>| {
                println!("{name} {}", Json::Arr(answered(p).run().expect("every operation answered")));
            };
            show("pairs", far.program("t:pairs", vec![]));
            show("total", far.program("shop:total", vec![Json::str("tea"), Json::str("milk")]));
            show("count", far.program("t:count", vec![Json::Int(10000)]));
            Ok(())
        }
        _ => {
            eprintln!("usage: demo worker | demo host <the worker's command>");
            std::process::exit(2)
        }
    }
}
