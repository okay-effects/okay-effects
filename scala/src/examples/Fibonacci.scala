package okay.effects.scala.examples

import okay.effects.scala.*, std.*

/** FIBONACCI AS EFFECTS: an endless generator, a stream made of it, recursion with no stack limit.
 *
 * The generator only sends; how many numbers are wanted is its handler's choice (`Emit.pull`, `Emit.take`). Every
 * recursion here returns a program — data — and the machine runs it in one loop, so no depth grows the JVM's stack.
 *
 * {{{
 * cd scala && scala-cli run . --server=false --main-class okay.effects.scala.examples.fibonacciDemo
 * }}}
 */
object Fibonacci:
  /** every Fibonacci number, in order, without end */
  def fib(a: BigInt = 0, b: BigInt = 1): Unit ! Emit % BigInt =
    Emit.send(a).flatMap(_ => fib(b, a + b))

  /** one step of a running sum: `State` ADDED to whatever row `R` the caller has — `R + State % BigInt` */
  def add[R[_]: Has[Emit % BigInt]](x: BigInt): Unit ! R + State % BigInt =
    for
      s <- State.get[BigInt]
      _ <- State.put(s + x)
      _ <- Emit.send(s + x)
    yield ()

  /** the running sums of `fib`, in any row with the stream: each number of `fib` through `add`, the sum starting at 0.
   * `State` is in no type outside: added for the step, taken away by `State.run` */
  def sums[R[_]: Has[Emit % BigInt]]: Unit ! R =
    State.run(BigInt(0))(Emit.each(add[R])(fib().into[R + State % BigInt + Emit % BigInt])).map(_ => ())

  /** the pair (F(n), F(n + 1)), by recursion that is not in tail position: the `map` waits for the call inside */
  def pair[R[_]](n: Int): (BigInt, BigInt) ! R =
    if n == 0 then pure((BigInt(0), BigInt(1)))
    else defer(pair[R](n - 1)).map((a, b) => (b, a + b))

@main def fibonacciDemo(): Unit =
  import Fibonacci.*
  println(s"fib:  ${Emit.pull(fib()).take(15).toList.mkString(", ")}")
  println(s"sums: ${Emit.pull(sums[Emit % BigInt]).take(15).toList.mkString(", ")}")
  type Logged[X] = (Emit % BigInt + Writer % String)[X]
  val logged: Unit ! Logged = Writer.tell("from a row of two").flatMap(_ => Emit.take(5)(sums[Logged]))
  println(s"sums in a row of two: ${run(Emit.collect(Writer.run(logged)))}")
  println(s"F(100000) by a depth-100000 recursion: ${run(pair[Pure](100_000))._1.bitLength} bits")
