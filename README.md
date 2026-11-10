# okay-effects

Algebraic effects and handlers: one model, written in **Scala 3**, **Java 25** and **Rust**. A program is data that
asks operations; handlers answer them; one loop runs it. The three kernels are checked to be one: the same
properties as tests, the same digest on a thousand random programs, the same wire protocol, and every host of one
language working with every worker of another. Each has the same standard effects and the same benchmark.

It is part of the [okay-effects](https://okay-effects.github.io/) family: the random programs and the wire are the
family's.

## The model

The whole of it is in [SPEC.md](SPEC.md). In short:

```
operation     a question as data, naming the type of its answer        effect = a closed family of operations
computation   Done a | Ask op | Bind c f | Handle h c | Resume k x     only data: nothing runs until `step`
handler       of one effect; answers IN PLACE (from a state it keeps, nothing captured)
              or GENERALLY (given `k`, the rest of the program, as a value)
row           the effects a computation may still ask; a handler takes its effect out of the row
run           accepts only the empty row
```

The machine is one loop over a stack of *levels*, one per handler: the frames waiting inside it, the handler, its
state. An operation finds the nearest handler that accepts it. In place, the handler answers and the loop goes on.
Generally, the levels from the top down to the handler are taken off as `k`, an immutable value; resuming `k` puts
them back, any number of times.

| promise | where it comes from |
|---|---|
| constant stack, any program | the machine is a loop; what waits is frames on the heap |
| mutual tail recursion without end | `defer`: a call in tail position is the next piece of data |
| an operation answered with nothing captured | a handler in place: the answer comes from the state in its level |
| continuations taken many times | `k` is immutable levels, shared, never copied |
| each path its own state | a state in place lives in its level, and the level is part of `k` |
| endless streams | the rest of the program after `send` is a value the consumer keeps and pulls |
| asynchrony | a handler may keep `k` and return; whoever has the answer resumes it later |
| interception, audit, delegation | `intercept` keeps the effect in the row; a clause may ask any operation |
| no program with an effect left runs | the row is in the type of the computation, and `run` takes the empty row |

The lines are tests in `Properties` of each language, named alike. The answer in place is how the standard State,
Reader and Writer answer in all of them. The last line is checked by programs that must not compile, in Java
(`java/negative/`), Rust (doctests of `rust/src/lib.rs`) and Scala (a test in `Properties`).

## One program, three languages

A choice of 1 or 2, added to a state. Handlers in one order give each path its own state; in the other, one state
goes through all paths.

Scala 3 — the row is an intersection type; its order does not matter:

```scala
import okay.effects.scala.*, std.*

val p: Int ! (State[Int] + Choose) =
  for x <- Choose.among(1, 2); s <- State.get[Int]; _ <- State.put(s + x) yield s + x

run(Choose.all(State.run(0)(p)))   // List((1, 1), (2, 2))
run(State.run(0)(Choose.all(p)))   // (3, List(1, 3))
```

Java 25 — the row is a nested type, the result first, in the order of the handlers. `up` moves a computation one
handler further, so the program is written once for each order:

```java
import static okay.effects.java.Effects.*;   // and okay.effects.java.*, okay.effects.java.std.*

static Comp<Integer, Eff<State<Integer>, ?>> add(int x) {
  return State.<Integer>get().flatMap(s -> State.put(s + x).map(done -> s + x));
}
static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> p() {   // a state, and a choice under it
  return up(Choose.among(List.of(1, 2))).flatMap(x -> add(x));
}
static Comp<Integer, Eff<Choose, Eff<State<Integer>, ?>>> q() {   // a choice, and a state under it
  return Choose.among(List.of(1, 2)).flatMap(x -> up(add(x)));
}

run(Choose.all(State.run(0, p())));   // [Final[state=1, value=1], Final[state=2, value=2]]
run(State.run(0, Choose.all(q())));   // Final[state=3, value=[1, 3]]
```

Rust — the row is a list of types; where an effect is in it is inferred. A computation is run once, so the
program is a function:

```rust
use okay_effects::effects::{Choose, State};
use okay_effects::{Comp, row};

fn p() -> Comp<row![State<i64>, Choose], i64> {
    Choose::among(vec![1, 2])
        .flat_map(|x| State::get().flat_map(move |s: i64| State::put(s + x).map(move |_| s + x)))
}

Choose::all(State::run(0, p())).run();   // [(1, 1), (2, 2)]
State::run(0, Choose::all(p())).run();   // (3, [1, 3])
```

## An effect and its handler

An effect is its operations, as data; each names what answers it. This is `State` and its handler in place, as
the standard library of each language has it.

```scala
sealed trait State[S]
object State:
  final case class Get[S]() extends State[S], Op[State[S], S]
  final case class Put[S](s: S) extends State[S], Op[State[S], Unit]

  def run[S, A, R](s0: S)(p: A ! (State[S] & R)): (S, A) ! R =
    handle(new InPlace[State[S], S, A, (S, A), R]:
      def ret(s: S, a: A) = pure((s, a))
      def answer[X](state: Cell[S], op: State[S] & Op[State[S], X]): X = op match
        case Get() => state.value
        case Put(s) => state.value = s
    , s0)(p)
```

```java
public sealed interface State<S> {
  record Get<S>() implements State<S>, Op<State<S>, S> {}
  record Put<S>(S value) implements State<S>, Op<State<S>, Void> {}
  record Final<S, A>(S state, A value) {}

  static <S, A, R> Comp<Final<S, A>, R> run(S s0, Comp<A, ? super Eff<State<S>, R>> p) {
    return handle(State.class, new InPlace<State<S>, S, A, Final<S, A>, R>() {
      public Comp<Final<S, A>, R> ret(S s, A a) { return pure(new Final<>(s, a)); }
      public Cell.Answered answer(Cell<S> state, State<S> op) {
        return switch (op) {   // the operation is the witness of the answer's type
          case Get<S> get -> state.answer(get, state.value);
          case Put<S> put -> { state.value = put.value(); yield state.answer(put, null); }
        };
      }
    }, s0, p);
  }
}
```

```rust
pub enum State<S> {
    Get(Reply<S>),        // a `Reply<X>` is the witness that the operation is answered by an `X`
    Put(S, Reply<()>),
}

impl<S: Clone + 'static> State<S> {
    pub fn run<R: Has<Self, I>, I, A: 'static>(s0: S, p: Comp<R, A>) -> Comp<R::Rest, (S, A)> {
        let answer = |state: &mut S, op| match op {
            State::Get(r) => r.give(state.clone()),
            State::Put(s, r) => {
                *state = s;
                r.give(())
            }
        };
        Handler::in_place(s0, |s, a| pure((s, a)), answer).handle(p)
    }
}
```

A general handler is given `k`. This is `Choose.all` in Scala: the rest of the program resumed once for every value.

```scala
def all[A, R](p: A ! (Choose & R)): List[A] ! R =
  handle(new Handler[Choose, A, List[A], R]:
    def ret(a: A) = pure(List(a))
    def on[X](op: Choose & Op[Choose, X], k: X => List[A] ! R) = op match
      case Among(xs) => xs.foldLeft(pure(Nil): List[A] ! R)((all, x) => all.flatMap(as => k(x).map(as ++ _)))
  )(p)
```

In Java the clause is `Comp<B, R> on(F op, K<B, R> k)` and resumes with `k.resume(op, answer)`; in Rust it is a
closure `Fn(E, K<R, B>) -> Comp<R, B>` given to `Handler::new(ret, on)` and resumes with `k.resume(reply, answer)`.

The standard effects, in each language: State, Reader (with `local`), Writer, Throws (with `recover`), Choose
(`all`, `first`), Fresh, Clock, Emit (streams: collect, each, map, filter, take, pull, zip, merge) and Async
(callbacks and continuations: await — `wait` in Rust — sleep, start, both, all, race). On the JVM Async has
`toFuture`, `fromFuture` and `runBlocking` and sleeps on a timer thread; in Rust time is a `Timers` queue the
caller runs.

## Compatible: with each other, and with the family

- **The same answers.** Each kernel builds the family's TCK — a thousand random programs: handlers of logs, states,
  failures and choices nested in any order, each effect under two identities — and folds their outcomes into one
  number. All three come to `-8623788747993822161`, recorded in the tests as the digest of the family's reference
  machine (okay-kernel, `tck/Tck.scala`).
- **One wire.** A program runs in one process and is handled in another, over the okay wire: calls, held
  objects, value escapes, programs continued by number (the same continuation as often as the host likes),
  streams both ways under credit, CBOR and deflate frames, mux, auth. [wire/README.md](wire/README.md) is its
  specification as implemented here. Every worker is held to six transcripts, and every host is run with every
  worker: nine pairs, each printing [wire/expected.txt](wire/expected.txt).

## What each language can and cannot say

| | Scala 3 | Java 25 | Rust |
|---|---|---|---|
| the row | an intersection, `A ! (State[Int] + Choose)`; no order; inferred | a nested type, `Comp<A, Eff<State<Integer>, Eff<Choose, ?>>>`, empty row `Pure`; positional: `up`, `under`, `fit` | a list of types, `Comp<row![State<i64>, Choose], A>`; membership inferred (`Has<E, I>`); `embed`, `also` where it is not |
| an effect left unhandled | compile error: `run` takes `A ! Any` | compile error: `run` takes `Comp<A, ? super Pure>` | compile error: only `Comp<(), A>` has `run` |
| an answer of the wrong type | compile error (GADT match) | compile error (the operation is the witness: `k.resume(get, s)`) | compile error (`Reply<X>` gives only an `X`) |
| a handler missing a case | compile error (the exhaustivity warning, under `-Werror`) | compile error (`switch` over the sealed interface) | compile error (`match` over the enum) |
| casts and `unsafe` in the kernel | 11 `asInstanceOf`, all in `Machine.scala`; none in `Effects.scala` or `std` | `Machine.java` is raw and unchecked; one unchecked cast in `Effects`, behind `up`, `under`, `fit` | `unsafe` in `val.rs` and `pool.rs`; a value is taken out by its `TypeId` |
| a computation run many times | yes: immutable data | yes: immutable data | no: `Comp` is not `Clone`; build it by a function |
| a continuation resumed many times | yes | yes | yes (`K` is `Clone`; the state in place is `Clone`) |
| threads | `k` may be resumed from any thread (Async's timer does) | the same | one thread: `Rc`, a thread-local pool |

## Speed

The same benchmark in each language, four lanes: `state` (get and put in a loop), `reader3` (an ask answered three
handlers away), `choose` (a continuation taken twice at every step of a tree of depth 18), `binds` (a loop of
nothing but binds). Nanoseconds per operation, the best of 7 rounds, measured once on one Mac — a record of one
run, not a claim:

| | state | reader3 | choose | binds |
|---|---|---|---|---|
| Scala 3 | 14.1 | 16.0 | 106.8 | 6.2 |
| Java 25 | 11.6 | 13.4 | 81.8 | 6.3 |
| Rust | 21.4 | 18.1 | 190.8 | 13.3 |

Rust has no collector: its frames, continuations and boxed values come from a thread-local pool (`rust/src/pool.rs`).

## Layout, tests, benchmarks

```
SPEC.md     the model: data, the machine's rules, laws, rows, conformance
scala/      scala-cli. src/Effects.scala the typed surface, src/Machine.scala the loop, src/std the effects,
            src/wire the wire, src/bench; test/ (munit)
java/       javac, no build tool. src/okay/effects/java: Effects, Comp, Eff, Pure, Op, Handler, InPlace, K, Cell,
            Step, Machine; std/, wire/, bench/; test/; negative/ the programs javac must refuse
rust/       cargo, no dependencies. src/lib.rs the typed surface, machine.rs, val.rs, pool.rs, effects.rs and
            effects/, wire.rs and wire/; tests/; examples/ (bench, demo, transcripts)
wire/       README.md the protocol; the transcripts; expected.txt
test.sh     everything
```

`./test.sh` needs scala-cli, a JDK 25 or newer and cargo, and takes about a minute. It runs the three test suites
(Scala 43 tests; Java 18 properties, 25 wire tests and 4 programs refused by javac; Rust 64 tests, compile-fail
doctests among them), every worker against the 6 transcripts, and the nine host–worker pairs.

```
cd scala && scala-cli run . --server=false --main-class okay.effects.scala.bench.benchmark
java -cp java/out okay.effects.java.bench.Bench          # after ./test.sh, which compiles into java/out
cd rust && cargo run --release --example bench
```

## Limits

- **An effect is found by the class of its operations.** In Scala and Java the machine matches an operation to a
  handler by the erased class, so two handlers of one effect class with different type arguments are not told
  apart: the nearest answers. This Java program compiles and fails with `ClassCastException`:
  `run(Reader.run(1, Reader.run("s", up(Reader.<Integer>ask()).map(x -> x + 1))))`. The same can be written in
  Scala (`Int ! (Reader[Int] & Reader[String])`). Use two effect types, or operations that carry an identity and a
  handler with `mine`, as the TCK does. Rust matches by `TypeId`, which includes the type arguments.
- **Java rows are positional.** A program names its effects in the order of its handlers and is moved with `up`,
  `under` and `fit`. `perform` gives a row open only after its first effect; a row meant to be used inside longer
  rows is written open at every step: `Eff<State<Integer>, ? extends Eff<Choose, ?>>`.
- **Java checks the witness at run time.** `k.resume(op, x)` and `state.answer(op, x)` check the type of `x`
  against `op` at compile time, and that `op` is the operation asked only when they run
  (`IllegalArgumentException`).
- **Rust does not tie a `Reply` to its `k`.** A `Reply` kept from another operation is accepted by `k.resume`; if
  its type is not the one awaited, the program panics where the value is taken.
- **Rust**: a computation is run once; everything is of one thread; the `unsafe` code of `val.rs` and `pool.rs`
  has its argument in comments and has not been checked under Miri.
- **The wire** leaves out tables and data frames, direct-style far sides (`once`) and `continue` with a
  condition, `bytes`, `zlib`, `verify` and `describe`, TCP, TLS and in-process links (the list is at the end of
  [wire/README.md](wire/README.md)). The transport is a child process's standard input and output. What is
  checked is these three hosts and workers with each other and against the transcripts; no file here records a
  run against another implementation of the okay wire.
- **The digest is compared with a constant.** The reference machine it is said to come from is not in this
  repository.
- **Nothing is published** as an artifact, and this repository has no git remote.
