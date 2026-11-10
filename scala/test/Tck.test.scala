package okay.effects.scala

import okay.effects.scala.std.Choose
import scala.util.Random

/** THE FAMILY'S TCK (okay-kernel, tck/Tck.scala): a thousand random programs — handlers of logs, states, failures and
 * choices nested in any order, each effect under two identities — and the digest of their outcomes. The digest is the
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

  // the effects: an identity `e` in the operation — a handler answers its own, and asks any other again outside
  sealed trait Log
  final case class Tell(e: Int, n: Int) extends Log, Op[Log, Unit]
  sealed trait Cell
  final case class Get(e: Int) extends Cell, Op[Cell, Int]
  final case class Put(e: Int, n: Int) extends Cell, Op[Cell, Unit]
  sealed trait Fail
  final case class Raise(e: Int, n: Int) extends Fail, Op[Fail, Int]

  type Row = Log & Cell & Fail & Choose

  def writer(e: Int)(p: Int ! Row): Int ! Row =
    intercept(new Handler[Log, Int, (Int, Int), Row]:
      override def mine(op: Log) = op match { case Tell(`e`, _) => true; case _ => false }
      def ret(a: Int) = pure((0, a))
      def on[X](op: Log & Op[Log, X], k: X => (Int, Int) ! Row) = op match
        case Tell(_, n) => k(()).map((sum, a) => (sum + n, a))
    )(p).map((sum, a) => a * 100 + sum)

  /** the TCK's state is a cell made where the handler is installed: one for every resumption taken past it —
   * not the state of `std.State`, which each path has its own */
  def state(e: Int, s0: Int)(p: Int ! Row): Int ! Row =
    var s = s0
    intercept(new InPlace[Cell, Unit, Int, Int, Row]:
      override def mine(op: Cell) = op match { case Get(`e`) | Put(`e`, _) => true; case _ => false }
      def ret(state: Unit, a: Int) = pure(a * 10 + s)
      def answer[X](state: okay.effects.scala.Cell[Unit], op: Cell & Op[Cell, X]): X = op match
        case Get(_) => s
        case Put(_, n) => s = n
    , ())(p)

  def throws(e: Int)(p: Int ! Row): Int ! Row =
    intercept(new Handler[Fail, Int, Int, Row]:
      override def mine(op: Fail) = op match { case Raise(`e`, _) => true; case _ => false }
      def ret(a: Int) = pure(a)
      def on[X](op: Fail & Op[Fail, X], k: X => Int ! Row) = op match
        case Raise(_, n) => pure(-1 - n)
    )(p)

  def build(t: T): Int ! Row = t match
    case T.Pure(n) => pure(n)
    case T.Tell(e, n) => perform(Tell(e, n)).map(_ => n)
    case T.Get(e) => perform(Get(e))
    case T.Put(e, n) => perform(Put(e, n)).map(_ => n)
    case T.Among(ns) => perform(Choose.Among(ns))
    case T.Raise(e, n) => perform(Raise(e, n))
    case T.Seq(a, b) => build(a).flatMap(_ => build(b))
    case T.Add(a, b) => build(a).flatMap(i => build(b).map(j => i + j))
    case T.WriterH(e, body) => writer(e)(build(body))
    case T.StateH(e, s0, body) => state(e, s0)(build(body))
    case T.ThrowsH(e, body) => throws(e)(build(body))
    case T.ChooseH(body) => Choose.all(build(body)).map(_.sum)

  def outcome(t: T): Int = step(build(t)) match
    case Step.Done(value) => value
    case Step.Asked(_, _) => -1000000 // no handler

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
