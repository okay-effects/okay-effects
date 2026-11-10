package okay.algebraic.scala3

import okay.algebraic.scala3.std.*

sealed trait Account
case class Deposit(amount: Int) extends Account, Op[Account, Int]

/** What the model promises, each promise a test. */
class Properties extends munit.FunSuite:

  type Counter = State[Int]

  def count(n: Int): Int ! Counter =
    if n == 0 then State.get else State.get[Int].flatMap(s => State.put(s + 1)).flatMap(_ => count(n - 1))

  test("a row: handlers take their effects one by one, a program runs when none is left"):
    val p: Int ! (State[Int] + Writer[String] + Throws[String]) =
      for
        s <- State.get[Int]
        _ <- Writer.tell(s"was $s")
        _ <- State.put(s + 1)
        _ <- if s > 100 then Throws.raise("too big") else pure(())
      yield s * 2
    assertEquals(run(Throws.run(Writer.run(State.run(1)(p)))), Right((List("was 1"), (2, 2))))

  test("the order of handlers is the meaning: state kept or lost at a failure"):
    val p: Unit ! (State[Int] + Throws[String]) = State.put(5).flatMap(_ => Throws.raise("no"))
    assertEquals(run(State.run(0)(Throws.run(p))), (5, Left("no")))
    assertEquals(run(Throws.run(State.run(0)(p))), Left("no"))

  test("constant stack: a million operations"):
    assertEquals(run(State.run(0)(count(1_000_000))), (1_000_000, 1_000_000))

  test("constant stack: a hundred thousand binds nested to the left"):
    val p = (1 to 100_000).foldLeft(State.get[Int])((p, _) => p.flatMap(s => State.put(s + 1).map(_ => s + 1)))
    assertEquals(run(State.run(0)(p)), (100_000, 100_000))

  test("mutual tail recursion without end: a million calls"):
    def even(n: Int): Boolean ! Any = if n == 0 then pure(true) else defer(odd(n - 1))
    def odd(n: Int): Boolean ! Any = if n == 0 then pure(false) else defer(even(n - 1))
    assert(run(even(1_000_000)))

  test("a continuation taken many times: every path of a choice"):
    val p: (Int, Int) ! Choose = for x <- Choose.among(1, 2, 3); y <- Choose.among(10, 20) yield (x, y)
    assertEquals(run(Choose.all(p)), List((1, 10), (1, 20), (2, 10), (2, 20), (3, 10), (3, 20)))

  test("many times, with state: each path its own state, or one state through all"):
    val p: Int ! (State[Int] + Choose) =
      for x <- Choose.among(1, 2); s <- State.get[Int]; _ <- State.put(s + x) yield s + x
    assertEquals(run(Choose.all(State.run(0)(p))), List((1, 1), (2, 2)))
    assertEquals(run(State.run(0)(Choose.all(p))), (3, List(1, 3)))

  test("an endless stream, pulled"):
    def from(n: Int): Nothing ! Emit[Int] = Emit.send(n).flatMap(_ => from(n + 1))
    assertEquals(Emit.stream(from(0)).take(3).toList, List(0, 1, 2))
    assertEquals(Emit.stream(from(0)).drop(1_000_000).head, 1_000_000)

  test("asynchrony: the handler returns, the answer resumes the program later"):
    var waiting = List.empty[() => Unit]
    def later(n: Int): Int ! Async = Async.await(resume => waiting ::= (() => resume(n)))
    var result = Option.empty[Int]
    Async.start(for a <- later(1); b <- later(2) yield a + b)(a => result = Some(a))
    assertEquals(result, None)
    while waiting.nonEmpty do
      val next = waiting.head
      waiting = waiting.tail
      next()
    assertEquals(result, Some(3))

  test("interception and audit: an operation seen, changed, and asked again"):
    var seen = List.empty[String]
    def audit[A, R <: State[Int]](p: A ! R): A ! R =
      okay.algebraic.scala3.intercept(new Handler[State[Int], A, A, R]:
        def ret(a: A) = pure(a)
        def on[X](op: State[Int] & Op[State[Int], X], k: X => A ! R) =
          seen ::= op.toString
          op match
            case State.Put(s) if s > 2 => perform(State.Put(2)).flatMap(k)
            case _ => perform(op).flatMap(k)
      )(p)
    assertEquals(run(State.run(0)(audit(count(5)))), (2, 2))
    assertEquals(seen.count(_.startsWith("Put")), 5)

  test("delegation: an effect answered by the operations of another"):
    def onState[A, R](p: A ! (Account & R)): A ! (State[Int] & R) =
      handle(new Handler[Account, A, A, State[Int] & R]:
        def ret(a: A) = pure(a)
        def on[X](op: Account & Op[Account, X], k: X => A ! (State[Int] & R)) = op match
          case Deposit(amount) => State.get[Int].flatMap(s => State.put(s + amount).flatMap(_ => k(s + amount)))
      )(p)
    val p = perform(Deposit(10)).flatMap(_ => perform(Deposit(5)))
    assertEquals(run(State.run(100)(onState(p))), (115, 115))

  test("no handler: an operation that reached the top is refused by name"):
    val open = State.get[Int].asInstanceOf[Int ! Any]
    assert(intercept[NoHandler](run(open)).op == State.Get())
