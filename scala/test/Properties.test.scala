package okay.effects.scala

import okay.effects.scala.std.*
import scala.concurrent.Await
import scala.concurrent.duration.*

enum Account[X]:
  case Deposit(amount: Int) extends Account[Int]

/** What the model promises, each promise a test. */
class Properties extends munit.FunSuite:

  type Counter[X] = (State % Int)[X]
  type Chosen[X] = (State % Int + Choose)[X]
  type Twice[X] = (State % Int + State % Int)[X]

  def count[R[_]: Has[State % Int]](n: Int): Int ! R =
    if n == 0 then State.get else State.get.flatMap(s => State.put(s + 1)).flatMap(_ => count(n - 1))

  /** two states of one type in a row: one changed and read through each evidence */
  def both[R[_]](using inner: In[State % Int, R], outer: In[State % Int, R]): (Int, Int) ! R =
    for
      _ <- State.modify[Int, R](_ + 1)(using inner)
      _ <- State.modify[Int, R](_ * 10)(using outer)
      a <- State.get(using inner)
      b <- State.get(using outer)
    yield (a, b)

  test("a row: handlers take their effects one by one, a program runs when none is left"):
    def p[R[_]: Has[State % Int] : Has[Writer % String] : Has[Throws % String]]: Int ! R =
      for
        s <- State.get
        _ <- Writer.tell(s"was $s")
        _ <- State.put(s + 1)
        _ <- if s > 100 then Throws.raise("too big") else unit
      yield s * 2
    type App[X] = (State % Int + Writer % String + Throws % String)[X]
    assertEquals(run(Throws.run(Writer.run(State.run(1)(p[App])))), Right((List("was 1"), (2, 2))))

  test("the order of handlers is the meaning: state kept or lost at a failure"):
    def p[R[_]: Has[State % Int] : Has[Throws % String]]: Unit ! R = State.put(5).flatMap(_ => Throws.raise("no"))
    type Row[X] = (State % Int + Throws % String)[X]
    assertEquals(run(State.run(0)(Throws.run(p[Row]))), (5, Left("no")))
    assertEquals(run(Throws.run(State.run(0)(p[Row]))), Left("no"))

  test("constant stack: a million operations"):
    assertEquals(run(State.run(0)(count[Counter](1_000_000))), (1_000_000, 1_000_000))

  test("constant stack: a hundred thousand binds nested to the left"):
    val p = (1 to 100_000).foldLeft(State.get[Int, Counter]): (p, _) =>
      p.flatMap(s => State.put(s + 1).map(_ => s + 1))
    assertEquals(run(State.run(0)(p)), (100_000, 100_000))

  test("mutual tail recursion without end: a million calls"):
    def even(n: Int): Boolean ! Pure = if n == 0 then pure(true) else defer(odd(n - 1))
    def odd(n: Int): Boolean ! Pure = if n == 0 then pure(false) else defer(even(n - 1))
    assert(run(even(1_000_000)))

  test("a continuation taken many times: every path of a choice"):
    val p: (Int, Int) ! Choose = for x <- Choose.among(1, 2, 3); y <- Choose.among(10, 20) yield (x, y)
    assertEquals(run(Choose.all(p)), List((1, 10), (1, 20), (2, 10), (2, 20), (3, 10), (3, 20)))

  test("many times, with state: each path its own state, or one state through all"):
    def p[R[_]: Has[State % Int] : Has[Choose]]: Int ! R =
      for x <- Choose.among(1, 2); s <- State.get; _ <- State.put(s + x) yield s + x
    assertEquals(run(Choose.all(State.run(0)(p[Chosen]))), List((1, 1), (2, 2)))
    assertEquals(run(State.run(0)(Choose.all(p[Chosen]))), (3, List(1, 3)))

  test("an endless stream, pulled"):
    def from(n: Int): Nothing ! Emit % Int = Emit.send(n).flatMap(_ => from(n + 1))
    assertEquals(Emit.pull(from(0)).take(3).toList, List(0, 1, 2))
    assertEquals(Emit.pull(from(0)).drop(1_000_000).head, 1_000_000)

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
    def audit[R[_]: Has[State % Int], A](p: A ! R): A ! R =
      okay.effects.scala.intercept(p, Handler.general[State % Int, R, A, A](pure)(
        [X] => (op: State[Int, X], k: X => A ! R) =>
          seen ::= op.toString
          op match
            case State.Put(s) if s > 2 => State.put(2).flatMap(k)
            case _ => perform[State % Int](op).flatMap(k)))
    assertEquals(run(State.run(0)(audit(count[Counter](5)))), (2, 2))
    assertEquals(seen.count(_.startsWith("Put")), 5)

  test("delegation: an effect answered by the operations of another"):
    def onState[R[_], Rest[_], A](p: A ! R)(using Split[Account, R, Rest], In[State % Int, Rest]): A ! Rest =
      handle(p, Handler.general[Account, Rest, A, A](pure)([X] => (op: Account[X], k: X => A ! Rest) => op match
        case Account.Deposit(amount) => State.get.flatMap(s => State.put(s + amount).flatMap(_ => k(s + amount)))))
    def p[R[_]: Has[Account]]: Int ! R =
      perform[Account](Account.Deposit(10)).flatMap(_ => perform[Account](Account.Deposit(5)))
    type Bank[X] = (State % Int + Account)[X]
    assertEquals(run(State.run(100)(onState(p[Bank]))), (115, 115))

  test("reader: a value of the surroundings, and changed for a part of the program"):
    def p[R[_]: Has[Reader % Int]]: (Int, Int) ! R =
      for a <- Reader.ask; b <- Reader.local[Int, R, Int](_ * 10)(Reader.ask) yield (a, b)
    assertEquals(run(Reader.run(2)(p[Reader % Int])), (2, 20))

  test("a failure recovered; the first of the choices; fresh numbers; a clock that a test sets"):
    val failing: Int ! Throws % String = Throws.raise("no")
    assertEquals(run(Throws.recover((e: String) => pure[Int, Pure](e.length))(failing)), 2)
    val odd = Choose.among(2, 4, 5, 7).flatMap(x => if x % 2 == 0 then Choose.none else pure(x))
    assertEquals(run(Choose.first(odd)), Some(5))
    assertEquals(run(Choose.all(odd)), List(5, 7))
    assertEquals(run(Fresh.run(for a <- Fresh.fresh; b <- Fresh.fresh yield (a, b))), (0L, 1L))
    assertEquals(run(Clock.at(() => 42L)(Clock.now)), 42L)

  test("streams: collected, looped over, mapped, filtered, cut short, zipped, merged"):
    def from[R[_]: Has[Emit % Int]](n: Int): Nothing ! R = Emit.send(n).flatMap(_ => from(n + 1))
    def three[R[_]: Has[Emit % Int]]: Unit ! R = Emit.sendAll(List(1, 2, 3))
    assertEquals(run(Emit.collect(three[Emit % Int])), (List(1, 2, 3), ()))
    type Summed[X] = (State % Int + Emit % Int)[X]
    val summed = Emit.each((x: Int) => State.modify[Int, Counter](_ + x))(three[Summed])
    assertEquals(run(State.run(0)(summed))._1, 6)
    // a stream made into another: the row has both, and the values go from the last to the one before it
    type Streams[X] = (Emit % Int + Emit % Int)[X]
    val even = Emit.filter((x: Int) => x % 2 == 0)(from[Streams](0))
    val evens = Emit.take(3)(Emit.map((x: Int) => x * 2)(even))
    assertEquals(run(Emit.collect(evens))._1, List(0, 4, 8))
    val pairs = Emit.zip[Int, Int, Nothing, Unit, Emit % (Int, Int)](from(0), three)
    assertEquals(Emit.pull(pairs).toList, List((0, 1), (1, 2), (2, 3)))
    val merged = Emit.merge[Int, Unit, Nothing, Emit % Int](three, from(10))
    assertEquals(Emit.pull(merged).take(5).toList, List(1, 10, 2, 11, 3))

  test("asynchrony: both at once, the first of two, all, a sleep, a future"):
    def after(millis: Long, n: Int): Int ! Async = Async.sleep(millis).map(_ => n)
    assertEquals(Async.runBlocking(Async.both(after(30, 1), after(10, 2))), (1, 2))
    assertEquals(Async.runBlocking(Async.race(after(200, 1), after(10, 2))), 2)
    assertEquals(Async.runBlocking(Async.all(List(after(20, 1), after(10, 2), after(1, 3)))), List(1, 2, 3))
    assertEquals(Await.result(Async.toFuture(after(5, 7).map(_ + 1)), 5.seconds), 8)

  test("asynchrony: a million answers given at once take no stack"):
    def loop(n: Int, sum: Int): Int ! Async =
      if n == 0 then pure(sum) else Async.await[Int, Async](resume => resume(1)).flatMap(x => loop(n - 1, sum + x))
    assertEquals(Async.runBlocking(loop(1_000_000, 0)), 1_000_000)

  test("an effect with no handler, and a handler with a case missing, do not compile"):
    assert(compileErrors("run(State.get[Int, Counter])").nonEmpty)
    assert(compileErrors("run(State.run(0)(Choose.among(1, 2)))").nonEmpty)
    val handled = "run(Choose.all(State.run(0)(State.get[Int, Chosen].flatMap(_ => Choose.among(1, 2)))))"
    assert(compileErrors(handled).isEmpty)

  test("no handler: an operation that reached the top is refused by name"):
    val open = State.get[Int, Counter].asInstanceOf[Int ! Pure]
    assert(intercept[NoHandler](run(open)).op == State.Get())

  // ---- the row is ordered, and a handler is found by the effect's place in it

  test("an effect with no handler is a compile error that names the effect and the row"):
    val unhandled = compileErrors("run(count[Pure](3))")
    assert(unhandled.contains("the effect State % Int is not in the row Pure"), unhandled)
    val another = compileErrors("run(State.run(0)(Choose.among(1, 2)))")
    assert(another.contains("the effect State % Int is not in the row Choose"), another)
    val left = compileErrors("run(State.run(0)(count[Chosen](3)))")
    assert(left.contains("the row State % Int + Choose without the effect State % Int is not Pure"), left)

  test("a handler of each shape, made by Handler's methods"):
    enum Ask[X]:
      case Name() extends Ask[String]
    def name[R[_]: Has[Ask]]: String ! R = perform[Ask](Ask.Name())
    val tail = Handler.tail[Ask, Pure, String, String](pure)([X] => (op: Ask[X]) => op match
      case Ask.Name() => "Ann")
    val general = Handler.general[Ask, Pure, String, List[String]](a => pure(List(a)))(
      [X] => (op: Ask[X], k: X => List[String] ! Pure) => op match
        case Ask.Name() => k("Ann").flatMap(a => k("Bob").map(a ++ _)))
    val abort = Handler.abort[Ask, Pure, String, String](pure)([X] => (op: Ask[X]) => op match
      case Ask.Name() => pure("nobody"))
    val stateful = Handler.stateful[Ask, Pure, String, (Int, String), Int](0)((n, a) => pure((n, a)))(
      [X] => (op: Ask[X], cell: Cell[Int]) => op match
        case Ask.Name() => cell.set(cell.get + 1); s"guest ${cell.get}")
    val twice = name[Ask].flatMap(a => name[Ask].map(b => s"$a, $b"))
    assertEquals(run(handle(twice, tail)), "Ann, Ann")
    assertEquals(run(handle(twice, general)), List("Ann, Ann", "Ann, Bob", "Bob, Ann", "Bob, Bob"))
    assertEquals(run(handle(twice, abort)), "nobody")
    assertEquals(run(handle(twice, stateful)), (2, "guest 1, guest 2"))

  test("one effect twice in a row, of two types: each reader asked through its own evidence"):
    def p[R[_]: Has[Reader % Int] : Has[Reader % String]]: String ! R =
      for n <- Reader.ask[Int, R]; s <- Reader.ask[String, R] yield s * n
    type Readers[X] = (Reader % Int + Reader % String)[X]
    assertEquals(run(Reader.run(2)(Reader.run("ab")(p[Readers]))), "abab")
    assertEquals(run(Reader.run("ab")(Reader.run(2)(p[Readers]))), "abab")

  test("one effect twice, of one type: the last place is found, the other is named; each has its state"):
    // found: the last place, the one the innermost handler takes
    assertEquals(run(State.run(1)(State.run(2)(count[Twice](3)))), (1, (5, 5)))
    // named: `Place.last`, and `Place.init`, the one before it
    val p = both[Twice](using Place.last, Place.init)
    assertEquals(run(State.run(1)(State.run(2)(p))), (10, (3, (3, 10))))
    assertEquals(run(State.run(1)(State.run(2)(both[Twice](using Place.init, Place.last)))), (2, (20, (2, 20))))

  test("one effect twice, with a choice: each place's state is its path's own, or one through all"):
    type Row[X] = (State % Int + Choose + State % Int)[X]
    def p[R[_]: Has[Choose]](using inner: In[State % Int, R], outer: In[State % Int, R]): Int ! R =
      for
        x <- Choose.among(1, 2)
        _ <- State.modify[Int, R](_ + x)(using inner)
        _ <- State.modify[Int, R](_ + x)(using outer)
        a <- State.get(using inner)
        b <- State.get(using outer)
      yield a * 10 + b
    val q = p[Row](using summon, Place.last, Place.init)
    assertEquals(run(State.run(0)(Choose.all(State.run(0)(q)))), (3, List((1, 11), (2, 23))))

  test("an interceptor between two handlers of one effect answers its own place only"):
    var seen = List.empty[String]
    def audit[R[_], A](p: A ! R)(using at: In[State % Int, R]): A ! R =
      okay.effects.scala.intercept(p, Handler.general[State % Int, R, A, A](pure)(
        [X] => (op: State[Int, X], k: X => A ! R) =>
          seen ::= op.toString
          perform[State % Int](op).flatMap(k)))
    val p = both[Twice](using Place.last, Place.init)
    // between the handlers: what the inner one answers does not come here, the outer state's operations do
    assertEquals(run(State.run(1)(audit(State.run(2)(p)))), (10, (3, (3, 10))))
    assertEquals(seen.reverse, List("Get()", "Put(10)", "Get()"))
    // inside both, at the outer state's place: the inner state's operations pass it, unseen
    seen = Nil
    assertEquals(run(State.run(1)(State.run(2)(audit(p)(using Place.init)))), (10, (3, (3, 10))))
    assertEquals(seen.reverse, List("Get()", "Put(10)", "Get()"))
    // and at the inner state's place
    seen = Nil
    assertEquals(run(State.run(1)(State.run(2)(audit(p)))), (10, (3, (3, 10))))
    assertEquals(seen.reverse, List("Get()", "Put(3)", "Get()"))

  test("an operation no handler answers stops the run with its place: the effect it is of is known by it"):
    def p[R[_]: Has[Reader % Int] : Has[Reader % String]]: String ! R =
      for n <- Reader.ask[Int, R]; s <- Reader.ask[String, R] yield s * n
    type Readers[X] = (Reader % Int + Reader % String)[X]
    def answered(at: Step[Readers, String]): String = at match
      case Step.Done(value) => value
      case asked: Step.Asked[Readers, ?, ?, String] =>
        asked.of[Reader % Int].map(the => number(the.op, the.k))
          .orElse(asked.of[Reader % String].map(the => text(the.op, the.k)))
          .get
    def number[X](op: Reader[Int, X], k: X => String ! Readers): String = op match
      case Reader.Ask() => answered(step(k(3)))
    def text[X](op: Reader[String, X], k: X => String ! Readers): String = op match
      case Reader.Ask() => answered(step(k("a")))
    assertEquals(answered(step(p[Readers])), "aaa")
