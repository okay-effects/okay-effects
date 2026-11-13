# okay-scala

Repo: <https://github.com/okay-effects/okay-scala> (README.md, `docs/getting-started.md`). Scala 3 only (from 3.3),
JVM / Scala.js / Native; one CEK machine, rows as types, multi-shot `k` as an immutable value.

```scala
scalaVersion := "3.3.8"
resolvers += "okay-scala" at "https://okay-effects.github.io/okay-scala/maven"
libraryDependencies += "io.github.okay-effects" %% "okay-scala" % "0.1.1"   // %%% on Scala.js / Native
```

Effect, program, handler, run (from `core/src/test/scala/okay/scala3/GettingStartedSuite.scala`):

```scala
import okay.scala3.*
import okay.scala3.std.*

enum Accounts[X]:                                   // the effect: operations as data, each naming its answer
  case Balance(who: String) extends Accounts[Int]
  case Deposit(who: String, amount: Int) extends Accounts[Unit]

object Accounts:
  def balance[R[_]](who: String)(using In[Accounts, R]): Int ! R = perform[Accounts](Balance(who))
  def deposit[R[_]](who: String, n: Int)(using In[Accounts, R]): Unit ! R = perform[Accounts](Deposit(who, n))

  // a handler: takes Accounts out of the row R, leaving Rest
  def inMemory[R[_], Rest[_], A](start: Map[String, Int])(p: A ! R)(using Split[Accounts, R, Rest])
      : (Map[String, Int], A) ! Rest =
    handle(p, Handler.stateful[Accounts, Rest, A, (Map[String, Int], A), Map[String, Int]](start)(
      (m, a) => pure((m, a)))(
      [X] => (op: Accounts[X], cell: Cell[Map[String, Int]]) => op match
        case Balance(who) => cell.get.getOrElse(who, 0)
        case Deposit(who, n) => cell.set(cell.get.updated(who, cell.get.getOrElse(who, 0) + n))))

// the program: any row that has Accounts and Fail
def payday[R[_]: Has[Accounts] : Has[Fail % String]]: Int ! R =
  for
    _ <- Accounts.deposit("ann", 100)
    b <- Accounts.balance("ann")
    _ <- if b > 1000 then Fail.raise("too rich") else unit
  yield b

type App[X] = (Accounts + Fail % String)[X]
run(Fail.run(Accounts.inMemory(Map("ann" -> 50))(payday[App])))   // Right((Map(ann -> 150),150))
```

Handler shapes: `Handler.tail` (answers in place), `Handler.stateful` (in place with a `Cell`), `Handler.general`
(gets `k`, resume any number of times), `Handler.control` (gets `k`, may unwind it), `Handler.abort`.

Multi-shot (from `CoreSuite.scala`), `k` resumed twice per operation:

```scala
val general = Handler.general[Ask, Pure, String, List[String]](a => pure(List(a)))(
  [X] => (op: Ask[X], k: X => List[String] ! Pure) => op match
    case Ask.Name() => k("Ann").flatMap(a => k("Bob").map(a ++ _)))
run(handle(twice, general))   // List("Ann, Ann", "Ann, Bob", "Bob, Ann", "Bob, Bob")
```

Standard effects in `okay.scala3.std`: `State`, `Reader`, `Writer`, `Fail`, `Choose` (`among`, `flip`; run by
`Choose.all` / `Choose.first`). Handler order matters: `State.run(0)(Choose.all(p))` threads one state through
all paths, `Choose.all(State.run(0)(p))` gives each path its own.

Interceptors and sensitive effects with the same model: see [reference.md](reference.md) (the okay-effects Scala
kernel) and the okay-scala README.
