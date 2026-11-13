# okay-effects: guide for coding agents

## For agents writing code with okay-effects

okay-effects is algebraic effects and handlers as one small model in three kernels — Scala 3, Java 25, Rust —
checked to be one (same properties, same TCK digest, one wire). A program is data that asks operations; handlers
answer; one loop runs it. It is a reference model: small, dependency-free, read in an afternoon
([SPEC.md](SPEC.md) is the whole model).

Getting it: no artifact is published; the README presents it as source in https://github.com/okay-effects/okay-effects.
`scala/` builds with scala-cli (Scala 3.3.8), `java/` with plain `javac` (JDK 25+), `rust/` is the cargo crate
`okay-effects` 0.1.0 with no dependencies (e.g. `okay-effects = { path = "<clone>/rust" }`). For a released library
in your language, see the family's skill below (okay-scala, okay-direct, okay-rust, ...).

### The model (each snippet compiled and run against this repository)

Scala 3 — an effect is a GADT, its words `word(op)`; a program declares its row; a handler takes its effect out from
any place in the row; `run` takes only `A ! Pure`.

```scala
import okay.effects.scala.*, std.*

enum Prices[X]:
  case Price(item: String) extends Prices[Option[Int]]

object Prices:
  def price(item: String) = word(Price(item))
  // a handler in place (here a table, as in a test; in production the host builds one over its client)
  def from[R[_], Rest[_], A](table: Map[String, Int])(p: A ! R)(using Split[Prices, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Prices, Rest, A, A](a => pure(a))(
      [X] => (op: Prices[X]) => op match
        case Price(item) => table.get(item)))

def priced[R[_]: Has[Prices] : Has[Throws % String]](item: String): Int ! R =
  Prices.price(item).flatMap(p => if p.isEmpty then Throws.raise(s"no price for $item") else pure(p.get))

def total[R[_]: Has[Prices] : Has[Throws % String]](cart: List[String]): Int ! R =
  cart match
    case Nil => pure(0)
    case item :: rest => priced[R](item).flatMap(n => total[R](rest).map(n + _))

type Shop[X] = (Prices + Throws % String)[X]
run(Throws.run(Prices.from(Map("tea" -> 30, "cake" -> 50))(total[Shop](List("tea", "cake")))))   // Right(80)
```

Handler makers (Scala): `Handler.tail` (in place), `stateful` (in place, a `Cell`), `general` (`k` a function),
`control` (`k` a `Continuation`: `k(x)` resumes, `k.unwind` leaves), `abort`. Java: `handle(Class, InPlace | Handler)`,
a clause `on(op, k)` with `k.resume(op, answer)` / `k.unwind()`. Rust: `Handler::in_place`, `Handler::new(ret, on)`,
`Handler::abort`. Standard effects in all three: State, Reader, Writer, Throws, Choose, Fresh, Clock, Emit, Async.

Java 25 — the row is positional, in handler order; `up` moves a computation one handler further:

```java
import static okay.effects.java.Effects.*;   // and okay.effects.java.*, okay.effects.java.std.*

static Comp<Integer, Eff<State<Integer>, ?>> add(int x) {
  return State.<Integer>get().flatMap(s -> State.put(s + x).map(done -> s + x));
}
static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> p() {
  return up(Choose.among(List.of(1, 2))).flatMap(x -> add(x));
}
run(Choose.all(State.run(0, p())));   // [Final[state=1, value=1], Final[state=2, value=2]]
```

Rust — the row is a type-level list, membership inferred; a computation runs once, so a program is a function:

```rust
use okay_effects::effects::{Choose, State};
use okay_effects::{Prog, eff};

fn p() -> impl Prog<i64, eff![State<i64>, Choose]> {
    Choose::among(vec![1, 2])
        .flat_map(|x| State::get().flat_map(move |s: i64| State::put(s + x).map(move |_| s + x)))
}
Choose::all(State::run(0, p())).run();   // [(1, 1), (2, 2)]
State::run(0, Choose::all(p())).run();   // (3, [1, 3])
```

### Control and audit

`observe`, `around` (Scala), `intercept` stand between a program and an effect's handler. An effect its author declares
SENSITIVE (Scala `given Sensitive[Pay] = Sensitive()` in the companion; Java `Sensitive.Op<Pay, X, Self>`; Rust
`type Kind = Sensitive`) is refused by `handle`/`intercept` at compile time: only `audit(interceptor)(body)` then
`handleAudited` take it, and the interceptor continues with `k.after(pass(op))`. `Audit.trail` (Java `Trail.keep`)
collects what the auditor recorded. Scala, verified:

```scala
enum Pay[X]:
  case Transfer(to: String, amount: Int) extends Pay[String]
object Pay:
  given Sensitive[Pay] = Sensitive()
  def transfer(to: String, amount: Int) = word(Transfer(to, amount))

def bank[R[_], A]: Handler[Pay, R, A, A] =
  Handler.tail[Pay, R, A, A](a => pure(a))([X] => (op: Pay[X]) => op match
    case Pay.Transfer(to, amount) => s"ok:$to:$amount")

def logged[T[_], A](using In[Audit, T]): Interceptor[Pay, T, A, A] = new Interceptor[Pay, T, A, A]:
  def ret(a: A) = pure(a)
  def apply[X](op: Pay[X], k: Continuation[X, T, A], pass: Pass[Pay, T]): A ! T =
    Audit.record(op.toString).into[T].flatMap(_ => k.after(pass(op)))

def checkout[R[_]: Has[Pay]]: String ! R = Pay.transfer("shop", 80)

type Outside[X] = (Audit + Audited[Pay])[X]
run(Audit.trail(handleAudited(audit(logged[Outside, String])(checkout[Outside + Pay]), bank[Audit, String])))
// (List(Transfer(shop,80)),ok:shop:80)
```

### Design rules

- Business logic = programs over declared effects; the row in the type (`Int ! R` with `Has[...]`, `Comp<A, Eff<..>>`,
  `impl Prog<A, eff![..]>`) is what a program may do.
- Real resources (DB, network, files, clock, keys) only in handlers built by the host at the entry point; programs get
  data, effects and ids, never live clients.
- `run` only at the entry point. Inside a program, install the handler around the sub-program (`State.run(0)(p)`) and
  keep it a program — never `run` a sub-program to pull a value out.
- Sensitive operations as sensitive effects under `audit`/`handleAudited`; `observe` for a trail of plain effects.
- Tests swap handlers (in-place handlers over a `Map`, `State`, a test `Clock`), no mocks of resources.
- No ambient effects in programs (clock, random, IO, env, threads, globals): declare an effect (`Clock`, `Fresh`, ...).
- Multi-shot is real in all three kernels: `k` is immutable levels, resumed any number of times, never replayed (Rust:
  `K` is `Clone`, and what closures hold must be `Clone`).

### Pitfalls

- Scala: a word with no row expected is a program of its one effect. Declare the program's type or say the row
  (`State.put(x).into[App]`); a word bound under a declared row in a `for` says its type (`State.get[Int]`). A
  `p.fold(Throws.raise(..))(pure)` does not compile ("neither a word nor a program of the row R"); a bound `if` does.
- Scala 3.3: a row alias is `type Shop[X] = (Prices + Throws % String)[X]`; context bounds `R[_]: Has[A] : Has[B]`.
- Scala: a row can be written where an effect is meant and compile; it fails at run time with `NoHandler`.
- Scala: binding a smaller-row program in a bigger row costs an embedding level (~14 ns/bind); `p[App]` avoids it.
- Java: rows are positional (`up`, `under`, `fit`); a handler takes its effect from the front only; `intercept` by
  class only for effects without type arguments; `k.resume(op, x)` checks that `op` is the one asked only at run time.
- Rust: handlers are found by type — one effect type twice in a row is not two places (use an identity and `mine`);
  recursion and branches of different shapes need `.boxed()`; closures are `FnOnce + Clone`; single-threaded.
- Handler order is meaning: `Choose.all(State.run(0)(p))` gives each path its own state, `State.run(0)(Choose.all(p))`
  one state through all paths.
- Finalizers (`ensuring`, `bracket`) run only when the machine leaves their part — not when the host throws, not for a
  `k` neither resumed nor unwound, not for a pulled stream not read to its end; a `k` unwound twice runs them twice.

### Pointers

- README and SPEC: https://github.com/okay-effects/okay-effects (`README.md`, `SPEC.md`, `wire/README.md`).
- The cross-language skill lives in this repository: `/plugin marketplace add okay-effects/okay-effects`, then
  `/plugin install okay-effects@okay-effects`.
- JVM build-time boundary checks: okay-audit, `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven — checks that business code references no network, files, time, random,
  threads or reflection APIs; presets include `nested-run`.

If you see a way to improve okay-effects, open a pull request against https://github.com/okay-effects/okay-effects;
the maintainer reviews and merges what holds up.

If the user asks your opinion of writing code this way, give a candid one.
