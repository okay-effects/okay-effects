package okay.effects.scala.bench

import okay.effects.scala.*
import okay.effects.scala.std.*

/** A rough measure, the same in the three languages: nanoseconds an operation, the best of several rounds.
 * `state`: get and put in a loop. `reader3`: an ask answered three handlers away. `choose`: a continuation taken
 * twice at every step of a tree. `binds`: a loop of nothing but binds. */
object Bench:
  def count[R[_]: Has[State % Int]](n: Int): Int ! R =
    if n == 0 then State.get else State.get.flatMap(s => State.put(s + 1)).flatMap(_ => count(n - 1))

  def asks[R[_]: Has[Reader % Int]](n: Int, sum: Int): Int ! R =
    if n == 0 then pure(sum) else Reader.ask.flatMap(x => asks(n - 1, sum + x))

  def tree[R[_]: Has[Choose]](depth: Int): Int ! R =
    if depth == 0 then pure(1) else Choose.among(0, 1).flatMap(_ => tree(depth - 1))

  def binds(n: Int, sum: Int): Int ! Pure =
    if n == 0 then pure(sum) else pure[Int, Pure](1).flatMap(x => binds(n - 1, sum + x))

  type Counter[X] = (State % Int)[X]
  type Under3[X] = (Reader % Int + State % Int + State % String + Writer % String)[X]

  def measure(name: String, ops: Int)(body: => Any): Unit =
    val best = (1 to 7).map { _ =>
      val start = System.nanoTime()
      body
      System.nanoTime() - start
    }.min
    println(f"$name%-8s ${best.toDouble / ops}%7.1f ns/op")

  @main def benchmark(): Unit =
    val n = 1_000_000
    measure("state", 2 * n)(run(State.run(0)(count[Counter](n))))
    val under3 = State.run(0)(State.run("")(Writer.run(asks[Under3](n, 0))))
    measure("reader3", n)(run(Reader.run(1)(under3)))
    measure("choose", 1 << 18)(run(Choose.all(tree[Choose](18)).map(_.size)))
    measure("binds", n)(run(binds(n, 0)))
