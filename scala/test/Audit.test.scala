package okay.effects.scala

import okay.effects.scala.std.*

/** a sensitive effect: handled only under its audit */
enum Pay[X]:
  case Transfer(to: String, amount: Int) extends Pay[String]
  case Balance() extends Pay[Int]

object Pay:
  given Sensitive[Pay] = Sensitive()
  def transfer(to: String, amount: Int) = word(Transfer(to, amount))
  def balance = word(Balance())

/** an effect whose handler leaves at a deposit below nothing */
enum Ledger[X]:
  case Deposit(amount: Int) extends Ledger[Int]

object Ledger:
  def deposit(amount: Int) = word(Deposit(amount))

/** Control and audit: observe, the trail, sensitive effects. The same tests, named alike, are in Java's and Rust's
 * `Properties`. A trail's lines are written by the auditor; the same lines in the three languages. */
class Audits extends munit.FunSuite:

  /** a line recorded to the trail */
  def told[R[_]](line: String)(using In[Audit, R]): Unit ! R = Audit.record(line).into[R]

  /** the state's operations, each recorded when it is asked and when it is answered: `who` says which auditor */
  def recording[R[_]: Has[State % Int] : Has[Audit], A](p: A ! R, who: String = ""): A ! R =
    around[State % Int, R, A](p)([X] => (op: State[Int, X], proceed: X ! R) => op match
      case State.Get() => told(s"${who}get").flatMap(_ => proceed.flatMap(x => told(s"${who}get → $x").map(_ => x)))
      case State.Put(s) =>
        told(s"${who}put $s").flatMap(_ => proceed.flatMap(x => told(s"${who}put $s → done").map(_ => x))))

  def counted[R[_]: Has[State % Int]]: Int ! R =
    for s <- State.get; _ <- State.put(s + 1); t <- State.get yield t

  test("an observer changes nothing"):
    var seen = List.empty[String]
    def p[R[_]: Has[State % Int] : Has[Choose]]: Int ! R =
      for x <- Choose.among(1, 2); s <- State.get; _ <- State.put(s + x) yield s + x
    type App[X] = (State % Int + Choose)[X]
    val observed =
      observe[State % Int, App, Int](p[App])([X] => (op: State[Int, X]) => pure[Unit, App] { seen ::= op.toString })
    assertEquals(run(State.run(0)(Choose.all(observed))), run(State.run(0)(Choose.all(p[App]))))
    assertEquals(run(Choose.all(State.run(0)(observed))), List((1, 1), (2, 2)))
    assertEquals(seen.reverse.take(4), List("Get()", "Put(1)", "Get()", "Put(3)"))

  test("the trail of a one-path program is its operations in order"):
    type App[X] = (Audit + State % Int)[X]
    assertEquals(run(Audit.trail(State.run(5)(recording(counted[App])))),
      (List("get", "get → 5", "put 6", "put 6 → done", "get", "get → 6"), (6, 6)))

  test("the trail under a choice: one trail through all paths, or each path its own"):
    def p[R[_]: Has[State % Int] : Has[Choose]]: Int ! R =
      for x <- Choose.among(1, 2); s <- State.get; _ <- State.put(s + x) yield s + x
    type Through[X] = (Audit + State % Int + Choose)[X]
    assertEquals(run(Audit.trail(State.run(0)(Choose.all(recording(p[Through]))))),
      (List("get", "get → 0", "put 1", "put 1 → done", "get", "get → 1", "put 3", "put 3 → done"),
        (3, List(1, 3))))
    type Each[X] = (Choose + Audit + State % Int)[X]
    assertEquals(run(Choose.all(Audit.trail(State.run(0)(recording(p[Each]))))), List(
      (List("get", "get → 0", "put 1", "put 1 → done"), (1, 1)),
      (List("get", "get → 0", "put 2", "put 2 → done"), (2, 2))))
    // an answer is recorded in the rest of whoever answers it: given twice, it is recorded twice
    type Picks[X] = (Audit + Choose)[X]
    val picked = around[Choose, Picks, Int](Choose.among(0, 1).into[Picks])([X] => (op: Choose[X], proceed: X ! Picks) =>
      told[Picks]("among").flatMap(_ => proceed.flatMap(x => told[Picks](s"→ $x").map(_ => x))))
    assertEquals(run(Audit.trail(Choose.all(picked))), (List("among", "→ 0", "→ 1"), List(0, 1)))

  test("an auditor that refuses"):
    type App[X] = (Audit + State % Int + Throws % String)[X]
    def refusing[A](p: A ! App): A ! App =
      around[State % Int, App, A](p)([X] => (op: State[Int, X], proceed: X ! App) => op match
        case State.Put(s) if s > 2 => told(s"put $s refused").flatMap(_ => Throws.raise(s"put $s refused"))
        case State.Put(s) => told(s"put $s").flatMap(_ => proceed)
        case State.Get() => proceed)
    val p: Int ! App = for _ <- State.put(1); _ <- State.put(5); s <- State.get[Int] yield s
    assertEquals(run(Audit.trail(State.run(0)(Throws.run(refusing(p))))),
      (List("put 1", "put 5 refused"), (1, Left("put 5 refused"))))
    // refused, the program is left: its finalizer runs, and its operation passes the auditor
    assertEquals(run(Audit.trail(State.run(0)(Throws.run(refusing(p.ensuring(State.put(2).into[App])))))),
      (List("put 1", "put 5 refused", "put 2"), (2, Left("put 5 refused"))))

  test("an auditor that changes an operation"):
    type App[X] = (Audit + State % Int)[X]
    def capped[A](p: A ! App): A ! App =
      around[State % Int, App, A](p)([X] => (op: State[Int, X], proceed: X ! App) => op match
        case State.Put(s) if s > 2 => told(s"put $s as 2").flatMap(_ => State.put(2).into[App])
        case State.Put(s) => told(s"put $s").flatMap(_ => proceed)
        case State.Get() => proceed)
    val p: Int ! App = for _ <- State.put(1); _ <- State.put(5); s <- State.get[Int] yield s
    assertEquals(run(Audit.trail(State.run(0)(capped(p)))), (List("put 1", "put 5 as 2"), (2, 2)))

  test("two auditors of one effect, inner and outer"):
    type App[X] = (Audit + State % Int)[X]
    val p: Int ! App = for s <- State.get[Int]; _ <- State.put(s + 1) yield s
    assertEquals(run(Audit.trail(State.run(0)(recording(recording(p, "inner "), "outer ")))), (List(
      "inner get", "outer get", "outer get → 0", "inner get → 0",
      "inner put 1", "outer put 1", "outer put 1 → done", "inner put 1 → done"), (1, 0)))

  test("an audit between two handlers of one effect"):
    // two states of one type: the inner one at the last place, the outer one named
    type App[X] = (Audit + State % Int + State % Int)[X]
    val outer = Place.init[State % Int, Audit + State % Int, State % Int, Audit](using Place.last)
    val p: (Int, Int) ! App = for
      _ <- State.put(1)
      _ <- State.put(10).into[App](using outer)
      a <- State.get[Int]
      b <- State.get[Int].into[App](using outer)
    yield (a, b)
    // between them: what the inner one answers does not come here, the outer one's operations do
    assertEquals(run(Audit.trail(State.run(0)(recording(State.run(0)(p))))),
      (List("put 10", "put 10 → done", "get", "get → 10"), (10, (1, (1, 10)))))

  // ---- a sensitive effect

  def bank[R[_], A](start: Int): Handler[Pay, R, A, (Int, A)] =
    Handler.stateful[Pay, R, A, (Int, A), Int](start)((s, a) => pure((s, a)))(
      [X] => (op: Pay[X], cell: Cell[Int]) => op match
        case Pay.Transfer(to, amount) => cell.set(cell.get - amount); s"ok:$to:$amount"
        case Pay.Balance() => cell.get)

  /** every operation passed on, recorded when asked and when answered */
  def recorded[T[_], A](using In[Audit, T]): Interceptor[Pay, T, A, A] = new Interceptor[Pay, T, A, A]:
    def ret(a: A) = pure(a)
    def apply[X](op: Pay[X], k: Continuation[X, T, A], pass: Pass[Pay, T]): A ! T =
      val asked = op match
        case Pay.Transfer(to, amount) => s"transfer $amount to $to"
        case Pay.Balance() => "balance"
      told(asked).flatMap(_ => k.after(pass(op).flatMap(x => told(s"$asked → $x").map(_ => x))))

  def payments[R[_]: Has[Pay]]: Int ! R =
    for _ <- Pay.transfer("a", 100); b <- Pay.balance; _ <- Pay.transfer("b", 50) yield b

  type Outside[X] = (Audit + Audited[Pay])[X]

  test("sensitive: handled under its audit it runs, the trail complete"):
    val audited = audit(recorded[Outside, Int])(payments[Outside + Pay])
    assertEquals(run(Audit.trail(handleAudited(audited, bank[Audit, Int](1000)))), (List(
      "transfer 100 to a", "transfer 100 to a → ok:a:100", "balance", "balance → 900",
      "transfer 50 to b", "transfer 50 to b → ok:b:50"), (850, 900)))

  test("an audited body asks past its sensitive place"):
    // the audit and its handler are one place of the row: what the body asks of another place passes both
    val passing = new Interceptor[Pay, Outside, Int, Int]:
      def ret(a: Int) = pure(a)
      def apply[X](op: Pay[X], k: Continuation[X, Outside, Int], pass: Pass[Pay, Outside]) = k.after(pass(op))
    val body: Int ! Outside + Pay = Pay.balance.flatMap(b => Audit.record(s"after $b").map(_ => b))
    assertEquals(run(Audit.trail(handleAudited(audit(passing)(body), bank[Audit, Int](7)))),
      (List("after 7"), (7, 7)))

  test("sensitive: unaudited, the compiler refuses it"):
    def refused(errors: String, what: String): Unit = assert(errors.contains(what), errors)
    // handled, intercepted or stepped as a plain effect
    refused(compileErrors("handle(payments[Pay], bank[Pure, Int](10))"), "the effect Pay is sensitive")
    refused(compileErrors("""okay.effects.scala.intercept(payments[Pay], Handler.general[Pay, Pay, Int, Int](pure)(
      [X] => (op: Pay[X], k: X => Int ! Pay) => perform[Pay](op).flatMap(k)))"""), "the effect Pay is sensitive")
    refused(compileErrors("""step(payments[Pay]) match
      case asked: Step.Asked[Pay, ?, ?, Int] => asked.of[Pay]
      case _ => None"""), "the effect Pay is sensitive")
    // handled under its audit with no audit before it
    refused(compileErrors("handleAudited(payments[Pay], bank[Pure, Int](10))"), "is not in the row")
    // asked outside its audit, where the row has Audited[Pay]
    refused(compileErrors("payments[Outside]"), "is not in the row")
    // a handler generic in its effect: the effect is not known there, so the caller is asked
    refused(compileErrors("""def any[E[_], R[_], Rest[_], A](p: A ! R, h: Handler[E, Rest, A, A])(
      using Split[E, R, Rest]): A ! Rest = handle(p, h)"""), "is not known here")
    // and with the evidence asked of its caller, the caller with Pay is refused
    refused(compileErrors("""def any[E[_], R[_], Rest[_], A](p: A ! R, h: Handler[E, Rest, A, (Int, A)])(
      using Split[E, R, Rest])(using Plain[E]): (Int, A) ! Rest = handle(p, h)
      any(payments[Pay], bank[Pure, Int](10))"""), "the effect Pay is sensitive")
    // what does compile: the audit, then its handler
    assert(compileErrors("handleAudited(audit(recorded[Outside, Int])(payments[Outside + Pay]), bank[Audit, Int](1))")
      .isEmpty)

  test("audit with ensuring: a finalizer's operations are in the trail; one that ends it is asked, not answered"):
    type App[X] = (Audit + State % Int + Throws % String)[X]
    val failing: Int ! App = State.put(1).flatMap(_ => Throws.raise("no"))
    val p = failing.ensuring(State.get[Int].flatMap(s => State.put(s + 10)).into[App])
    assertEquals(run(Audit.trail(State.run(0)(Throws.run(recording(p))))),
      (List("put 1", "put 1 → done", "get", "get → 1", "put 11", "put 11 → done"), (11, Left("no"))))
    // the ledger leaves at a deposit below nothing: the finalizer's deposit asks a handler that has left
    type Books[X] = (Audit + Ledger)[X]
    def recordingLedger[A](p: A ! Books): A ! Books =
      around[Ledger, Books, A](p)([X] => (op: Ledger[X], proceed: X ! Books) => op match
        case Ledger.Deposit(n) =>
          told(s"deposit $n").flatMap(_ => proceed.flatMap(x => told(s"deposit $n → $x").map(_ => x))))
    def ledger[A](p: A ! Books): Int ! Audit =
      handle(p, Handler.control[Ledger, Audit, A, Int](_ => pure(0))(
        [X] => (op: Ledger[X], k: Continuation[X, Audit, Int]) => op match
          case Ledger.Deposit(n) if n < 0 => k.unwind.map(_ => -1)
          case Ledger.Deposit(n) => k(n)))
    val deposits: Int ! Books = Ledger.deposit(1).flatMap(_ => Ledger.deposit(-1))
    val q = deposits.ensuring(Ledger.deposit(5).into[Books])
    assertEquals(run(Audit.trail(ledger(recordingLedger(q)))),
      (List("deposit 1", "deposit 1 → 1", "deposit -1", "deposit 5"), -1))

  test("constant stack: a hundred thousand audited operations"):
    def balances[R[_]: Has[Pay]](n: Int, sum: Int): Int ! R =
      if n == 0 then pure(sum) else Pay.balance.flatMap(b => balances(n - 1, sum + b))
    val passing = new Interceptor[Pay, Audited[Pay], Int, Int]:
      def ret(a: Int) = pure(a)
      def apply[X](op: Pay[X], k: Continuation[X, Audited[Pay], Int], pass: Pass[Pay, Audited[Pay]]) =
        k.after(pass(op))
    val audited = audit(passing)(balances[Audited[Pay] + Pay](100_000, 0))
    assertEquals(run(handleAudited(audited, bank[Pure, Int](1))), (1, 100_000))
