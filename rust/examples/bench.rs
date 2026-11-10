//! A rough measure, the same in the three languages: nanoseconds an operation, the best of several rounds.
//! `state`: get and put in a loop. `reader3`: an ask answered three handlers away. `choose`: a continuation taken
//! twice at every step of a tree. `binds`: a loop of nothing but binds. `cargo run --release --example bench`.

use std::hint::black_box;
use std::time::Instant;

use okay_effects::effects::{Choose, Reader, State, Writer};
use okay_effects::{Comp, pure, row};

fn count(n: i64) -> Comp<row![State<i64>], i64> {
    if n == 0 {
        return State::get();
    }
    State::get().flat_map(|s: i64| State::put(s + 1)).then(move || count(n - 1))
}

type Under3 = row![Writer<String>, State<String>, State<i64>, Reader<i64>];

fn asks(n: i64, sum: i64) -> Comp<Under3, i64> {
    if n == 0 { pure(sum) } else { Reader::ask().flat_map(move |x: i64| asks(n - 1, sum + x)) }
}

fn tree(depth: u32) -> Comp<row![Choose], i64> {
    if depth == 0 { pure(1) } else { Choose::among(vec![0, 1]).then(move || tree(depth - 1)) }
}

fn binds(n: i64, sum: i64) -> Comp<(), i64> {
    if n == 0 { pure(sum) } else { pure(1).flat_map(move |x: i64| binds(n - 1, sum + x)) }
}

fn measure<A>(name: &str, ops: u32, body: impl Fn() -> A) {
    let round = || {
        let start = Instant::now();
        black_box(body());
        start.elapsed()
    };
    let best = (0..7).map(|_| round()).min().unwrap_or_default();
    println!("{name:<8} {:7.1} ns/op", best.as_nanos() as f64 / ops as f64);
}

fn main() {
    let n = black_box(1_000_000);
    measure("state", 2 * n as u32, || State::run(0, count(n)).run());
    let under3 = || State::run(0, State::run(String::new(), Writer::<String>::run(asks(n, 0))));
    measure("reader3", n as u32, || Reader::run(1, under3()).run());
    measure("choose", 1 << 18, || Choose::all(tree(18)).map(|all| all.len()).run());
    measure("binds", n as u32, || binds(n, 0).run());
}
