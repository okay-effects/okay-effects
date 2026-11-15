package okay.effects.scala.examples

import okay.effects.scala.*, std.*
import Fibonacci.*

class Examples extends munit.FunSuite:
  test("fib: an endless generator, read as far as asked, in constant stack"):
    assertEquals(Emit.pull(fib()).take(10).toList, List[BigInt](0, 1, 1, 2, 3, 5, 8, 13, 21, 34))
    assertEquals(Emit.pull(fib()).iterator.drop(20_000).next(), run(pair[Pure](20_000))._1)

  test("sums: F(0) + ... + F(n) = F(n + 2) - 1, in a row of one and in a row of two"):
    val expected = Emit.pull(fib(1, 2)).take(20).toList.map(_ - 1)
    assertEquals(Emit.pull(sums[Emit % BigInt]).take(20).toList, expected)
    type Logged[X] = (Emit % BigInt + Writer % String)[X]
    val logged: Unit ! Logged = Writer.tell("start").flatMap(_ => Emit.take(5)(sums[Logged]))
    assertEquals(run(Emit.collect(Writer.run(logged))), (expected.take(5), (List("start"), ())))

  test("pair: a recursion 200000 deep, not in tail position, in constant stack"):
    assertEquals(run(pair[Pure](200_000))._1.bitLength, 138_848)
