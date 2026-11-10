package okay.effects.scala

import okay.effects.scala.std.Choose
import scala.util.Random

/** THE FAMILY'S TCK (okay-kernel, tck/Tck.scala): a thousand random programs — handlers of logs, states, failures and
 * choices nested in any order, each effect twice in the row — and the digest of their outcomes. The digest is the
 * reference machine's; the Java and the Rust kernels of this repository come to the same number. */
object Tck:
  enum T:
    case Pure(n: Int)
    case Tell(e: Int, n: Int)
    case Get(e: Int)
    case Put(e: Int, n: Int)
    case Among(ns: List[Int])
    case Raise(e: Int, n: Int)
    case Seq(a: T, b: T)
    case Add(a: T, b: T)
    case WriterH(e: Int, body: T)
    case StateH(e: Int, s0: Int, body: T)
    case ThrowsH(e: Int, body: T)
    case ChooseH(body: T)

  def gen(r: Random, depth: Int): T =
    if depth <= 0 || r.nextInt(4) == 0 then
      r.nextInt(6) match
        case 0 => T.Pure(r.nextInt(10))
        case 1 => T.Tell(r.nextInt(2), r.nextInt(10))
        case 2 => T.Get(r.nextInt(2))
        case 3 => T.Put(r.nextInt(2), r.nextInt(10))
        case 4 => T.Among(List.fill(1 + r.nextInt(2))(r.nextInt(10)))
        case _ => T.Raise(r.nextInt(2), r.nextInt(10))
    else
      r.nextInt(6) match
        case 0 => T.Seq(gen(r, depth - 1), gen(r, depth - 1))
        case 1 => T.Add(gen(r, depth - 1), gen(r, depth - 1))
        case 2 => T.WriterH(r.nextInt(2), gen(r, depth - 1))
        case 3 => T.StateH(r.nextInt(2), r.nextInt(10), gen(r, depth - 1))
        case 4 => T.ThrowsH(r.nextInt(2), gen(r, depth - 1))
        case _ => T.ChooseH(gen(r, depth - 1))

  enum Log[X]:
    case Tell(n: Int) extends Log[Unit]
  enum Cell[X]:
    case Get extends Cell[Int]
    case Put(n: Int) extends Cell[Unit]
  enum Fail[X]:
    case Raise(n: Int) extends Fail[Int]

  /** every effect twice, and the choice. A handler is an interceptor of one place: it answers the operations of
   * its place, and those of the effect's other place pass it — to the nearest handler of that one. */
  type Row[X] = (Log + Log + Cell + Cell + Fail + Fail + Choose)[X]

  /** THE FIRST PLACE of an effect in a row, where the compiler's own search finds the last */
  final class First[E[_], R[_], Rest[_]](val at: Place[E, R, Rest])
  object First extends FirstHere:
    given further[E[_], R[_], G[_], Rest[_]](using first: First[E, R, Rest]): First[E, R + G, Rest + G] =
      First(Place.init(using first.at))
  trait FirstHere:
    given last[R[_], E[_]]: First[E, R + E, R] = First(Place.last)
    given only[E[_]]: First[E, E, Pure] = First(Place.only)

  /** the two places of an effect: its two identities, 0 and 1 */
  def places[E[_]](using first: First[E, Row, ?], last: In[E, Row]): List[In[E, Row]] = List(first.at, last)

  val (logs, cells, fails) = (places[Log], places[Cell], places[Fail])

  def writer(at: In[Log, Row])(p: Int ! Row): Int ! Row =
    intercept(p, Handler.general[Log, Row, Int, (Int, Int)](a => pure((0, a)))(
      [X] => (op: Log[X], k: X => (Int, Int) ! Row) => op match
        case Log.Tell(n) => k(()).map((sum, a) => (sum + n, a))))(using at).map((sum, a) => a * 100 + sum)

  /** the TCK's state is a cell made where the handler is installed: one for every resumption taken past it —
   * not the state of `std.State`, which each path has its own */
  def state(at: In[Cell, Row], s0: Int)(p: Int ! Row): Int ! Row =
    var s = s0
    intercept(p, Handler.tail[Cell, Row, Int, Int](a => pure(a * 10 + s))([X] => (op: Cell[X]) => op match
      case Cell.Get => s
      case Cell.Put(n) => s = n))(using at)

  def throws(at: In[Fail, Row])(p: Int ! Row): Int ! Row =
    intercept(p, Handler.abort[Fail, Row, Int, Int](pure)([X] => (op: Fail[X]) => op match
      case Fail.Raise(n) => pure(-1 - n)))(using at)

  /** every path of the choices — `Choose.all`, the choice kept in the row */
  def choose(p: Int ! Row): Int ! Row =
    intercept(p, Handler.general[Choose, Row, Int, List[Int]](a => pure(List(a)))(
      [X] => (op: Choose[X], k: X => List[Int] ! Row) => op match
        case Choose.Among(xs) =>
          xs.foldLeft(pure[List[Int], Row](Nil))((all, x) => all.flatMap(as => k(x).map(as ++ _))))).map(_.sum)

  def build(t: T): Int ! Row = t match
    case T.Pure(n) => pure(n)
    case T.Tell(e, n) => perform[Log](Log.Tell(n))(using logs(e)).map(_ => n)
    case T.Get(e) => perform[Cell](Cell.Get)(using cells(e))
    case T.Put(e, n) => perform[Cell](Cell.Put(n))(using cells(e)).map(_ => n)
    case T.Among(ns) => perform[Choose](Choose.Among(ns))
    case T.Raise(e, n) => perform[Fail](Fail.Raise(n))(using fails(e))
    case T.Seq(a, b) => build(a).flatMap(_ => build(b))
    case T.Add(a, b) => build(a).flatMap(i => build(b).map(j => i + j))
    case T.WriterH(e, body) => writer(logs(e))(build(body))
    case T.StateH(e, s0, body) => state(cells(e), s0)(build(body))
    case T.ThrowsH(e, body) => throws(fails(e))(build(body))
    case T.ChooseH(body) => choose(build(body))

  def outcome(t: T): Int = step(build(t)) match
    case Step.Done(value) => value
    case Step.Asked(_, _, _) => -1000000 // no handler

  def digest(n: Int = 1000, seed: Long = 20261010L): Long =
    val r = Random(seed)
    (1 to n).foldLeft(0L): (d, _) =>
      val body = gen(r, 6)
      val t = T.ChooseH(T.ThrowsH(0, T.ThrowsH(1, T.StateH(0, 0, T.StateH(1, 0, T.WriterH(0, T.WriterH(1, body)))))))
      d * 31 + outcome(t)

  val Digest: Long = -8623788747993822161L

class TckTest extends munit.FunSuite:
  test("a thousand random programs: the reference machine's digest"):
    assertEquals(Tck.digest(), Tck.Digest)
