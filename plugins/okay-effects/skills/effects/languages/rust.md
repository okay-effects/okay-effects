# okay-rust

Repo: <https://github.com/okay-effects/okay-rust> (README.md, AGENTS.md, `machine/README.md`). Rust 2024 edition.
Three crates: `okay` (package `okay-rust`: one API, typed effects on `async`, plus `okay::resumable`),
`okay-resumable` (`#[resumable]`, multi-shot state machines on stable), `okay-machine` (the specification: programs
as data, `k` resumed any number of times, interceptors and sensitive effects).

Not on crates.io; depend on the git repository (the package is renamed to `okay` as the repo itself does):

```toml
[dependencies]
okay = { package = "okay-rust", git = "https://github.com/okay-effects/okay-rust" }
okay-resumable = { git = "https://github.com/okay-effects/okay-rust" }   # only for multi-shot functions
```

Effect, handler, program, run (from `tests/capabilities.rs`):

```rust
use okay::effect;
use okay::typed::*;

effect! { enum Ask: Plain { Ask() -> i64 = ask_i; } }      // `Sensitive` instead of `Plain` for audited effects

struct Is<T>(T);
impl Handles<Ask> for Is<i64> {
    fn handle(&self, op: Ask) -> Step<'_> {
        match op {
            Ask::Ask(r) => Step::Now(r.give(self.0)),      // Step::Later takes the rest; Step::Wait(fut) waits
        }
    }
}

let cx = Cx(Is(3i64), End);                                // the host builds the context of handlers
let got = run(async { ask_i(&cx).await * 10 });            // a program is an async block over the context
assert_eq!(got, Ok(30));
```

A program as a named function with its row in the signature (`examples/shop/src/lib.rs`):

```rust
okay::needs! {
    pub async fn checkout(cx: [Inventory, Payments, Notify], user: &str, item: &str, cents: i64) -> Receipt {
        let mut r = Receipt { paid: false, detail: format!("{item}: out of stock") };
        if reserve(cx, item.into()).await { r = charge(cx, user.into(), cents).await; }
        r
    }
}
```

Multi-shot (the `typed` async API is one-shot by design): `#[resumable]` turns a function into a `Clone` enum state
machine, so `k.clone()` resumes it many times without replay (`resumable/tests/resumable.rs`):

```rust
use okay::resumable::*;
use okay::Answer;
use okay_resumable::resumable;

okay::effect! { pub enum Choose: Plain { Among(n: usize) -> usize = among; } }
okay::ops! { pub enum ChooseOps { Choose } }

#[resumable(ChooseOps)]
fn pick(base: usize) -> usize {
    let a: usize = ask!(Choose::Among(3));
    let b: usize = ask!(Choose::Among(2));
    base + a * 10 + b
}
fn all<K: Rest>(mut k: K, answer: Answer, out: &mut Vec<K::Out>) where K::Op: OpOf<Choose> {
    match k.resume(answer) {
        Step::Done(a) => out.push(a),
        Step::Perform(op) => match op.op() {
            Ok(Choose::Among(n, r)) => for i in 0..n { all(k.clone(), r.give(i), out) },
            Err(_) => unreachable!(),
        },
    }
}
let mut out = vec![]; all(pick(100), unit(), &mut out);   // [100, 101, 110, 111, 120, 121]
```

Limits of `#[resumable]` (README): a variable living across a stop needs a type annotation; a stop inside an
expression, a `for` holding a stop, and type parameters are compile errors (`--features coroutines` on nightly lifts
the `for` limit). For programs as data with interceptors and audit, see `machine/README.md` and
[reference.md](reference.md) (the okay-effects Rust kernel has the same shapes).
