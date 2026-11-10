package okay.effects.scala

import okay.effects.scala.std.*
import scala.concurrent.Await
import scala.concurrent.duration.*

enum Account[X]:
  case Deposit(amount: Int) extends Account[Int]

/** an effect's words: its operations, not yet placed in a row */
object Account:
  def deposit(amount: Int) = word(Deposit(amount))

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
      a <- State.get.into(using inner)
      b <- State.get.into(using outer)
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
    val p = (1 to 100_000).foldLeft(State.get[Int].into[Counter]): (p, _) =>
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
    def later(n: Int): Int ! Async = Async.await[Int](resume => waiting ::= (() => resume(n)))
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
      for a <- Reader.ask; b <- Reader.local((_: Int) * 10)(Reader.ask) yield (a, b)
    assertEquals(run(Reader.run(2)(p[Reader % Int])), (2, 20))

  test("a failure recovered; the first of the choices; fresh numbers; a clock that a test sets"):
    val failing: Int ! Throws % String = Throws.raise("no")
    assertEquals(run(Throws.recover((e: String) => pure(e.length))(failing)), 2)
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
    val summed = Emit.each((x: Int) => State.modify((s: Int) => s + x))(three[Summed])
    assertEquals(run(State.run(0)(summed))._1, 6)
    // a stream made into another, in its place in the row
    val even = Emit.filter((x: Int) => x % 2 == 0)(from[Emit % Int](0))
    val evens = Emit.take(3)(Emit.map((x: Int) => x * 2)(even))
    assertEquals(run(Emit.collect(evens))._1, List(0, 4, 8))
    val pairs = Emit.zip(from(0), three)
    assertEquals(Emit.pull(pairs).toList, List((0, 1), (1, 2), (2, 3)))
    val merged = Emit.merge(three, from(10))
    assertEquals(Emit.pull(merged).take(5).toList, List(1, 10, 2, 11, 3))

  test("asynchrony: both at once, the first of two, all, a sleep, a future"):
    def after(millis: Long, n: Int): Int ! Async = Async.sleep(millis).map(_ => n)
    assertEquals(Async.runBlocking(Async.both(after(30, 1), after(10, 2))), (1, 2))
    assertEquals(Async.runBlocking(Async.race(after(200, 1), after(10, 2))), 2)
    assertEquals(Async.runBlocking(Async.all(List(after(20, 1), after(10, 2), after(1, 3)))), List(1, 2, 3))
    assertEquals(Await.result(Async.toFuture(after(5, 7).map(_ + 1)), 5.seconds), 8)

  test("asynchrony: a million answers given at once take no stack"):
    def loop(n: Int, sum: Int): Int ! Async =
      if n == 0 then pure(sum) else Async.await[Int](resume => resume(1)).flatMap(x => loop(n - 1, sum + x))
    assertEquals(Async.runBlocking(loop(1_000_000, 0)), 1_000_000)

  test("an effect with no handler, and a handler with a case missing, do not compile"):
    assert(compileErrors("run(State.get[Int].into[Counter])").nonEmpty)
    assert(compileErrors("run(State.run(0)(Choose.among(1, 2)))").nonEmpty)
    val handled = "run(Choose.all(State.run(0)(State.get[Int].flatMap(_ => Choose.among(1, 2).into[Chosen]))))"
    assert(compileErrors(handled).isEmpty)

  test("no handler: a row handled as if it were one effect is refused when an operation of it comes"):
    // rows and effects are of one kind, so this compiles: a handler of `Chosen`, the row, in a program of `Chosen`
    val p: Int ! Chosen = Choose.among(1, 2)
    val wrong = handle(p, Handler.abort[Chosen, Pure, Int, Int](pure)([X] => (_: Chosen[X]) => pure(0)))
    assert(intercept[NoHandler](run(wrong)).op == Choose.Among(List(1, 2)))

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
      for n <- Reader.ask[Int]; s <- Reader.ask[String] yield s * n
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
        a <- State.get.into(using inner)
        b <- State.get.into(using outer)
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
      for n <- Reader.ask[Int]; s <- Reader.ask[String] yield s * n
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

  // ---- a word says no row: the program it is used in does

  test("a program with a declared row: its words say no row"):
    val p: Int ! Chosen = for x <- Choose.among(1, 2); s <- State.get[Int]; _ <- State.put(s + x) yield s + x
    assertEquals(run(Choose.all(State.run(0)(p))), List((1, 1), (2, 2)))
    val q: Int ! Chosen = Choose.among(1, 2).map(_ + 1)
    assertEquals(run(Choose.all(State.run(0)(q))), List((0, 2), (0, 3)))
    // an effect of one's own: its words are made by `word`
    type Bank[X] = (State % Int + Account)[X]
    val paid: Int ! Bank = for a <- Account.deposit(10); b <- Account.deposit(5); _ <- State.put(a + b) yield b
    val counted = handle(paid, Handler.stateful[Account, State % Int, Int, Int, Int](0)((_, a) => pure(a))(
      [X] => (op: Account[X], sum: Cell[Int]) => op match
        case Account.Deposit(amount) => sum.set(sum.get + amount); sum.get))
    assertEquals(run(State.run(0)(counted)), (25, 15))

  test("a word where a program is expected: alone, in a branch, in a recursion, after `pure` and `unit`"):
    val alone: Int ! Chosen = Choose.among(1, 2)
    def down(n: Int): Int ! Chosen = if n == 0 then State.get else State.put(n).flatMap(_ => down(n - 1))
    def either(n: Int): Int ! Chosen = n match
      case 0 => Choose.among(1, 2)
      case _ => pure(n)
    val after: Int ! Chosen = for _ <- unit; x <- pure(2); y <- Choose.among(x, 3) yield y
    // a branch that is a word beside one that is a program, at the head of a bind
    val guarded: Int ! Chosen =
      for x <- Choose.among(1, 2, 3); _ <- if x == 2 then Choose.none[Unit] else unit yield x
    def all[A](p: A ! Chosen): List[A] = run(Choose.all(State.run(7)(p))).map(_._2)
    assertEquals(all(alone), List(1, 2))
    assertEquals(all(down(3)), List(1))
    assertEquals(all(either(0)) ++ all(either(5)), List(1, 2, 5))
    assertEquals(all(after), List(2, 3))
    assertEquals(all(guarded), List(1, 3))

  test("a program with no declared type: of one effect it is that effect's; of more, the row is said once"):
    assertEquals(run(Choose.all(Choose.among(1, 2).map(_ + 1))), List(2, 3))
    assertEquals(run(State.run(1)(for s <- State.get[Int]; _ <- State.put(s + 1) yield s)), (2, 1))
    val unsaid = compileErrors("run(Choose.all(State.run(0)(for x <- Choose.among(1, 2); _ <- State.put(x) yield x)))")
    assert(unsaid.contains("the effect Choose is not in the row State % Int. If this row was not said"), unsaid)
    val declared: Int ! Chosen = for x <- Choose.among(1, 2); _ <- State.put(x) yield x
    assertEquals(run(Choose.all(State.run(0)(declared))), List((1, 1), (2, 2)))
    // or the row is said on the last word: a bind takes its row from what follows it
    val said = for x <- Choose.among(1, 2); _ <- State.put(x).into[Chosen] yield x
    assertEquals(run(Choose.all(State.run(0)(said))), List((1, 1), (2, 2)))

  // ---- a row in a row

  test("a handler leaves the natural row: the first effect of two taken, the other is the row"):
    val p: Int ! Chosen = for x <- Choose.among(1, 2); _ <- State.put(x) yield x
    val choices: (Int, Int) ! Choose = State.run(0)(p)
    assertEquals(run(Choose.all(choices)), List((1, 1), (2, 2)))
    type Three[X] = (State % Int + Choose + Fresh)[X]
    val q: Long ! Three = for x <- Choose.among(1, 2); _ <- State.put(x); n <- Fresh.fresh yield n + x
    val rest: (Int, Long) ! Choose + Fresh = State.run(0)(q)
    assertEquals(run(Fresh.run(Choose.all(rest))), List((1, 1L), (2, 3L)))

  test("a program put into a bigger row: every effect of its row found there, in any order"):
    type Swapped[X] = (Choose + State % Int)[X]
    type Wide[X] = (Writer % String + Choose + Reader % Int + State % Int)[X]
    val p: Int ! Chosen = for x <- Choose.among(1, 2); s <- State.get[Int]; _ <- State.put(s + x) yield s + x
    // a continuation taken inside is resumed twice across the embedding: each path its own state, or one state
    assertEquals(run(Choose.all(State.run(0)(p.into[Swapped]))), List((1, 1), (2, 2)))
    assertEquals(run(State.run(0)(Choose.all(p.into[Swapped]))), (3, List(1, 3)))
    val wide: Int ! Wide = for r <- Reader.ask[Int]; x <- p; _ <- Writer.tell(s"$r $x") yield x * r
    val ran = run(Writer.run(Reader.run(10)(State.run(0)(Choose.all(wide)))))
    assertEquals(ran, (List("10 1", "10 3"), (3, List(10, 30))))
    // a handler inside the embedding takes its effect out there: the places after it are renamed still
    val inside: (Int, Int) ! Swapped = State.run(100)(p.into[Chosen + State % Int]).into[Swapped]
    assertEquals(run(State.run(0)(Choose.all(inside))), (0, List((101, 101), (102, 102))))
    // an operation no handler answers comes out at its place in the bigger row
    step(State.get[Int].into[Counter].map(_ + 1).into[Swapped]) match
      case asked: Step.Asked[Swapped, ?, ?, Int] => assert(asked.of[State % Int].isDefined && asked.of[Choose].isEmpty)
      case _ => fail("an operation was expected")

  test("an embedding into the same row, or of an operation alone, is nothing at run time"):
    val p: Int ! Choose = Choose.among(1, 2).map(_ + 1)
    val same: AnyRef = p.into[Choose]
    assert(same eq p)
    val one: Int ! Chosen = State.get
    assert(one.into[Choose + State % Int].isInstanceOf[Comp.Ask[?, ?, ?]])
    assertEquals(run(State.run(5)(Choose.all(one.into[Choose + State % Int]))), (5, List(5)))

  test("constant stack through an embedding: a million operations inside one, and a million embeddings"):
    type Swapped[X] = (Choose + State % Int)[X]
    assertEquals(run(State.run(0)(Choose.all(count[Chosen](1_000_000).into[Swapped]))), (1_000_000, List(1_000_000)))
    val tick: Unit ! Chosen = State.get[Int].flatMap(s => State.put(s + 1))
    def ticks(n: Int): Int ! Swapped = if n == 0 then State.get else tick.into[Swapped].flatMap(_ => ticks(n - 1))
    assertEquals(run(State.run(0)(Choose.all(ticks(1_000_000)))), (1_000_000, List(1_000_000)))

  test("a row not in a row is a compile error that names the missing effect"):
    val missing = compileErrors("(pure(1): Int ! Chosen).into[Choose + Reader % Int]")
    assert(missing.contains("the effect State % Int is not in the row Choose + Reader % Int"), missing)

  test("a stream mapped: a new stream in the row, wherever the old one was"):
    type Counted[X] = (Emit % Int + State % Int)[X]
    val p: Unit ! Counted = for _ <- Emit.send(1); _ <- State.put(1); _ <- Emit.send(2) yield ()
    val shown: Unit ! State % Int + Emit % String = Emit.map((x: Int) => s"<$x>")(p)
    assertEquals(run(State.run(0)(Emit.collect(shown))), (1, (List("<1>", "<2>"), ())))
    // the new stream where the row asked says: first here, as the old one was
    val first: Unit ! Emit % String + State % Int = Emit.map((x: Int) => s"<$x>")(p)
    assertEquals(run(Emit.collect(State.run(0)(first))), (List("<1>", "<2>"), (1, ())))

  test("asynchrony in a bigger row: both, all and the race are words"):
    type Timed[X] = (State % Int + Async)[X]
    def after(millis: Long, n: Int): Int ! Async = Async.sleep(millis).map(_ => n)
    val p: Int ! Timed =
      for
        pair <- Async.both(after(20, 1), after(5, 2))
        first <- Async.race(after(200, 10), after(5, 20))
        all <- Async.all(List(after(10, 100), after(1, 200)))
        _ <- State.put(pair._1 + pair._2 + first + all.sum)
        s <- State.get[Int]
      yield s
    assertEquals(Async.runBlocking(State.run(0)(p)), (323, 323))

  test("the family's names: a program written with okay-scala's `Fail` and `Yield` runs"):
    type App[X] = (Yield % Int + Fail % String)[X]
    def numbers(n: Int): Unit ! App =
      if n > 2 then Fail.raise("enough") else Yield.emit(n).flatMap(_ => numbers(n + 1))
    assertEquals(run(Fail.run(Yield.collect(numbers(1)))), Left("enough"))
    assertEquals(run(Yield.collect(Fail.run(numbers(1)))), (List(1, 2), Left("enough")))
    val seen = Yield.foreach(numbers(1))(n => if n > 1 then Fail.raise(s"saw $n") else unit)
    var said = ""
    run(Fail.recover(seen)((e: String) => pure { said = e }))
    assertEquals(said, "saw 2")
    val handler = Handler.abort[Fail % String, Pure, Int, Int](pure)([X] => (op: Fail[String, X]) => op match
      case Fail.Raise(e) => pure(e.length))
    assertEquals(run(handle(Fail.raise("four"), handler)), 4)
    assertEquals(run(Fresh.from(10)(for a <- Fresh.next; b <- Fresh.next yield a + b)), 21L)

  // ---- a program of a smaller row is put into the row where it is bound

  type App[X] = (Reader % Int + State % Int + Writer % String)[X]

  def tick[R[_]: Has[State % Int]]: Unit ! R = State.modify((s: Int) => s + 1)

  test("a program of a smaller row is bound in a declared row: at the head of a `for`, and later"):
    val p: Int ! App =
      for
        _ <- State.modify((s: Int) => s + 1)
        r <- Reader.ask[Int]
        _ <- tick
        s <- State.get[Int]
      yield r + s
    assertEquals(run(Reader.run(10)(State.run(0)(Writer.run(p)))), (2, (Nil, 12)))

  test("a program of a smaller row where one of a bigger row is expected: a result, an argument, a branch"):
    def twice(p: Unit ! App): Unit ! App = p.flatMap(_ => p)
    def bump(by: Int): Unit ! App = if by == 0 then unit else State.modify((s: Int) => s + by)
    val p: Int ! App = twice(tick).flatMap(_ => bump(10)).flatMap(_ => State.get)
    assertEquals(run(Reader.run(0)(State.run(0)(Writer.run(p))))._1, 12)
    // `map` alone: the row expected of it is bigger than the program's
    val mapped: String ! App = tick.map(_ => "done")
    assertEquals(run(Reader.run(0)(State.run(0)(Writer.run(mapped)))), (1, (Nil, "done")))

  test("a continuation taken inside a program put into a row where it is bound is resumed twice across it"):
    type Small[X] = (State % Int + Choose)[X]
    type Big[X] = (Choose + Writer % String + State % Int)[X]
    val inner: Int ! Small = for x <- Choose.among(1, 2); s <- State.get[Int]; _ <- State.put(s + x) yield s + x
    val p: Int ! Big = for a <- inner; _ <- Writer.tell(s"$a"); b <- inner yield a * 10 + b
    // each path its own state, or one state through all
    assertEquals(run(Choose.all(Writer.run(State.run(0)(p)))).map(_._2._2), List(12, 13, 23, 24))
    assertEquals(run(State.run(0)(Choose.all(Writer.run(p))))._1, 9)

  test("constant stack through a million programs put into a row where they are bound"):
    def ticks(n: Int): Int ! App = if n == 0 then State.get else tick.flatMap(_ => ticks(n - 1))
    assertEquals(run(Reader.run(0)(State.run(0)(Writer.run(ticks(1_000_000)))))._1, 1_000_000)

  test("three branches and more, words and programs of smaller rows among them, are bound"):
    def step(n: Int): Unit ! App =
      for
        s <- State.get[Int]
        _ <-
          if n < 0 then Writer.tell("negative")
          else if n == 0 then tick
          else if s > 100 then unit
          else State.put(s + n)
        r <- n match
          case 0 => Reader.ask[Int]
          case 1 => State.get[Int]
          case _ => pure(n)
        _ <- Writer.tell(s"$n: $r")
      yield ()
    val p: Unit ! App = step(-1).flatMap(_ => step(0)).flatMap(_ => step(1)).flatMap(_ => step(5))
    val told = List("negative", "-1: -1", "0: 7", "1: 2", "5: 5")
    assertEquals(run(Reader.run(7)(State.run(0)(Writer.run(p)))), (7, (told, ())))

  test("a word without its type: found from the evidence in scope, or where a program is expected; else it is said"):
    def bumped[R[_]: Has[State % Int] : Has[Reader % Int]]: Int ! R =
      for s <- State.get; r <- Reader.ask; _ <- State.put(s + r) yield s
    assertEquals(run(Reader.run(10)(State.run(1)(Writer.run(bumped[App])))), (11, (Nil, 1)))
    val read: Int ! App = State.get
    assertEquals(run(Reader.run(10)(State.run(1)(Writer.run(read))))._2._2, 1)
    // bound in a program of a declared row, with no evidence in scope, its type is said: the row comes to a bind
    // with the function it is bound to, too late to say what that function takes
    val said: Int ! App = for s <- State.get[Int]; _ <- State.put(s + 1) yield s
    assertEquals(run(Reader.run(10)(State.run(1)(Writer.run(said))))._1, 2)
    assert(compileErrors("val p: Int ! App = for s <- State.get; _ <- State.put(s + 1) yield s").nonEmpty)

  test("a row of four: handlers take their effects from the last place, the first, or between, in any order"):
    type Four[X] = (Reader % Int + Reader % String + State % Int + Writer % String)[X]
    val p: Int ! Four =
      for
        a <- Reader.ask[Int]; b <- Reader.ask[String]
        s <- State.get[Int]; _ <- State.put(s + a); _ <- Writer.tell(b * a)
      yield s
    assertEquals(run(Reader.run("x")(Reader.run(2)(State.run(1)(Writer.run(p))))), (3, (List("xx"), 1)))
    assertEquals(run(Writer.run(State.run(1)(Reader.run("x")(Reader.run(2)(p))))), (List("xx"), (3, 1)))
    assertEquals(run(Writer.run(Reader.run(2)(Reader.run("x")(State.run(1)(p))))), (List("xx"), (3, 1)))
    assertEquals(run(State.run(1)(Reader.run(2)(Writer.run(Reader.run("x")(p))))), (3, (List("xx"), 1)))
    assertEquals(run(Reader.run(2)(Writer.run(State.run(1)(Reader.run("x")(p))))), (List("xx"), (3, 1)))
    // and in a row where they are in another order, under a level that renames
    type Turned[X] = (Writer % String + State % Int + Reader % String + Reader % Int)[X]
    assertEquals(run(Reader.run("x")(Writer.run(Reader.run(2)(State.run(1)(p.into[Turned]))))), (List("xx"), (3, 1)))
    assertEquals(run(State.run(1)(Reader.run(2)(Reader.run("x")(Writer.run(p.into[Turned]))))), (3, (List("xx"), 1)))
