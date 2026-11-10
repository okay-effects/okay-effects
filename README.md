# okay-effects

Algebraic effects and handlers, the same small model in three languages: **Scala 3**, **Java 25** and **Rust**.
One idea, written three times as plainly as each language allows, and checked to be one: the three kernels come to
the same answers on a thousand random programs, and a program in any of them can be handled by any other.

This is the reference of the [okay-effects](https://okay-effects.github.io/) family, not its fastest member: read it
to see what the model is. The kernels tuned for speed are okay-scala, okay-direct (Java) and okay-rust.

## The model

Four definitions (the whole of it is in [SPEC.md](SPEC.md)):

```
Op X      an operation: a question as data, answered by an X          an effect = a sealed family of operations
Comp A    = Done a | Ask op | Bind comp f                             a computation: only data, nothing runs
Handler   = { ret : A -> Comp B ;  on : (op, k) -> Comp B }           k: the rest of the program, a value
handle    : Handler -> Comp A -> Comp B                               the fold; other operations pass outside
run       : Comp A -> A                                               when no operation is left to ask
```

A program asks; it does not know who answers. A handler answers the operations of one effect and takes that effect
out of the program's row; what it does not know passes through it to the next handler. When the row is empty the
program runs. Everything the model promises follows from two facts — a computation is data, and one loop (the
*machine*) runs it:

| promise | where it comes from |
|---|---|
| constant stack, any program | the machine is a loop; what waits is a list of frames on the heap |
| mutual tail recursion without end | `defer`: a call in tail position is the next piece of data |
| endless streams | the rest of the program after `send` is a value the consumer keeps and pulls |
| asynchrony | a handler may keep `k` and return; whoever has the answer resumes it later |
| continuations taken many times | `k` is immutable data: resuming it twice is using a value twice |
| interception, audit, delegation | a handler may ask the operation again outside, or ask another effect's |

Each line of the table is a test, the same test in the three languages.

## One program, three languages

Scala 3 — the row is in the type, and the compiler takes effects out of it:

```scala
import okay.algebraic.scala3.*, std.*

val p: Int ! (State[Int] + Choose) =
  for x <- Choose.among(1, 2); s <- State.get[Int]; _ <- State.put(s + x) yield s + x

run(Choose.all(State.run(0)(p)))   // List((1, 1), (2, 2)) — each path its own state
run(State.run(0)(Choose.all(p)))   // (3, List(1, 3))      — one state through all
```

Java 25 — sealed interfaces, records, `switch`:

```java
Comp<Integer> p = Choose.among(List.of(1, 2)).flatMap(x ->
    State.<Integer>get().flatMap(s -> State.put(s + x).map(done -> s + x)));

Choose.all(State.run(0, p)).run();   // [Final[state=1, value=1], Final[state=2, value=2]]
State.run(0, Choose.all(p)).run();   // Final[state=3, value=[1, 3]]
```

Rust — an operation is a type, a handler a list of typed clauses:

```rust
let p = || among(vec![1i64, 2]).flat_map(|x| get::<i64>().flat_map(move |s| put(s + x).map(move |_| s + x)));

all::<i64, _>(state(0i64, p())).run();   // Ok([(1, 1), (2, 2)])
state(0i64, all::<i64, _>(p())).run();   // Ok((3, [1, 3]))
```

An effect is declared the same way everywhere — operations as data, each naming its answer:

```scala
sealed trait State[S]
final case class Get[S]() extends State[S], Op[State[S], S]
final case class Put[S](s: S) extends State[S], Op[State[S], Unit]
```
```java
public sealed interface State<S> {
  record Get<S>() implements State<S>, Op<S> {}
  record Put<S>(S value) implements State<S>, Op<Void> {}
}
```
```rust
pub struct Get<S>(PhantomData<S>);   impl<S: Clone + 'static> Op for Get<S> { type Answer = S; }
pub struct Put<S>(pub S);            impl<S: Clone + 'static> Op for Put<S> { type Answer = (); }
```

and a handler is a pattern match over them (Scala; `k` is the rest of the program):

```scala
def all[A, R](p: A ! (Choose & R)): List[A] ! R =
  handle(new Handler[Choose, A, List[A], R]:
    def ret(a: A) = pure(List(a))
    def on[X](op: Choose & Op[Choose, X], k: X => List[A] ! R) = op match
      case Among(xs) => xs.foldLeft(pure(Nil): List[A] ! R)((all, x) => all.flatMap(as => k(x).map(as ++ _)))
  )(p)
```

## Compatible: with each other, and with the family

- **The same answers.** Each kernel runs the family's TCK — okay-kernel's thousand random programs (handlers of
  logs, states, failures and choices nested in any order, each effect under two identities) — and comes to the
  reference machine's digest, `-8623788747993822161`: the same number as okay-kernel, okay-java,
  okay-rust's machine and okay-zig.
- **One wire.** A program runs in one process and is handled in another: only the operation (a name and JSON
  arguments) and its answer cross; a continuation stays where it was made and is known outside by a number, so the
  other side may continue it twice. The messages are the okay wire's `program` / `continue` / `forget`
  ([wire/transcript.txt](wire/transcript.txt) holds the very lines of okay's `wire.txt`). `./test.sh` runs every
  host with every worker: nine pairs, one answer.

```
host (Java handlers)                        worker (a Rust program)
> {"id":1,"op":"program","run":1,"fn":"t:pairs","args":[]}
                                          < {"id":1,"ok":{"perform":"choose","args":[[1,2]],"k":1}}
> {"id":2,"op":"continue","run":1,"k":1,"answer":1}
                                          < {"id":2,"ok":{"done":10}}
> {"id":3,"op":"continue","run":1,"k":1,"answer":2}       the same k, again
                                          < {"id":3,"ok":{"done":20}}
```

## What each language can and cannot say

| | Scala 3 | Java 25 | Rust |
|---|---|---|---|
| the row of effects | in the type (`A ! (State[Int] + Choose)`), checked by the compiler | at run time: `NoHandler` | at run time: `Err(NoHandler)` |
| an operation's answer type | checked (GADT match) | checked (the operation is the witness: `k.resume(get, s)`) | checked (`Op::Answer`) |
| a handler's cases | exhaustive `match` over the sealed family | exhaustive `switch` over the sealed interface | one typed clause per operation |
| casts in the kernel | 2, in one function | the machine's loop, and 3 more | values travel as `Rc<dyn Any>` |
| continuations many times | yes | yes | yes (answers are `Clone`; one thread: `Rc`) |

At run time an effect is the class (the type, in Rust) of its operations. Two states in one program are two effect
types — or one effect whose operations carry an identity, as the TCK's do.

## Layout and tests

```
SPEC.md              the model, its laws, the machine, the wire
scala/               Scala 3 (scala-cli): src/Effects.scala is the kernel (~90 lines), src/Std.scala the effects
java/                Java 25 (javac, no build tool): src/okay/algebraic/{Op,Comp,Rest,Step,Handler,K}.java
rust/                Rust (cargo, no dependencies): src/lib.rs is the kernel, src/effects.rs the effects
*/…/wire             JSON, the worker, the host's view of a far side, the demonstration
wire/                the transcript and the demonstration's expected output
test.sh              everything: three test suites, three workers against the transcript, nine host–worker pairs
```

`./test.sh` needs scala-cli, a JDK 25 or newer, and cargo.

## Not here (yet)

- Speed. The machine allocates a node per bind and walks handlers one by one; that is the price of being readable.
- The rest of the okay wire: `call`, held objects, streams, CBOR frames, the value escapes for big integers.
  A far side's failure is an exception (a panic, in Rust) on the host.
- Published artifacts. The names — package `okay.algebraic`, crate `okay-algebraic` — avoid the ones taken in the
  family (`okay.effects` is okay-simple's) and are open to change.
