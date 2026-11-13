# okay-simple

Repo: <https://github.com/okay-effects/okay-simple> (`docs/getting-started.md`, `docs/tour.md` — every block of the
tour is compiled and asserted by `sbt docsCheck/test`). A shared interface, package `okay.effects`, with several
implementations to run on (`okay.effects.machine.Machine`, `okay.effects.free.Free`). Scala 3 and 2.13.

```scala
resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
libraryDependencies ++= Seq(
  "io.github.okay-effects" %% "okay-effects"         % "0.1.1",   // the interface and the standard effects
  "io.github.okay-effects" %% "okay-effects-machine" % "0.1.1"    // an implementation to run on
)
```

Effect, handler, program, run (from `docs/tour.md` §3):

```scala
import okay.effects.*
import okay.effects.std.*

enum Counter[X]:
  case Tick extends Counter[Unit]
  case Peek extends Counter[Int]

def tick[R[_]: Has[Counter]]: Unit ! R = perform[Counter](Counter.Tick)
def peek[R[_]: Has[Counter]]: Int ! R = perform[Counter](Counter.Peek)

def counting[R[_], Rest[_], A](p: A ! R)(using Split[Counter, R, Rest]): (Int, A) ! Rest =
  handle(p, 0, new StatefulTailHandler[Counter, Int, Rest, A, (Int, A)]:
    def ret(n: Int, a: A) = pure((n, a))
    def answer[X](cell: Cell[Int], op: Counter[X]): X = op match
      case Counter.Tick => cell.set(cell.get + 1)
      case Counter.Peek => cell.get)

assert(run(counting(tick[Counter].andThen(tick[Counter]).andThen(peek[Counter]))) == (2, 2))
```

Standard effects (from getting-started; Scala 3.9 syntax — on 3.3 LTS write `R[_]: Has[A]: Has[B]` and
`type App[X] = (...)[X]`):

```scala
def greet[R[_]: {Has[Reader % String], Has[State % Int]}]: String ! R =
  for name <- ask[String, R]; n <- get[Int, R]; _ <- put[Int, R](n + 1) yield s"hello, $name (#${n + 1})"
type App = Reader % String + State % Int
assert(run(state(0)(reader("Ann")(greet[App]))) == (1, "hello, Ann (#1)"))
```

Multi-shot: a general `Handler` gets `k: X => B ! Rest`, callable any number of times (tour §4):

```scala
def outcomes[R[_], Rest[_], A](p: A ! R)(using Split[Coin, R, Rest]): List[A] ! Rest =
  handle(p, new Handler[Coin, Rest, A, List[A]]:
    def ret(a: A) = pure(List(a))
    def op[X](op: Coin[X], k: X => List[A] ! Rest) = op match
      case Coin.Flip => k(true).flatMap(t => k(false).map(t ++ _)))
```

To stay independent of the implementation, take `fx: Runner` and pass `Machine` or `Free` at the entry.
