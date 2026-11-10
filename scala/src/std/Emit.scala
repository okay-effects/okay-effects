package okay.effects.scala.std

import okay.effects.scala.*

/** EMIT: a stream of values, possibly endless. The producer is a program that sends; what a stream is made into —
 * a list, a loop, another stream, a lazy sequence pulled a value at a time — is the handler's choice. */
sealed trait Emit[T]
object Emit:
  final case class Send[T](value: T) extends Emit[T], Op[Emit[T], Unit]

  def send[T](value: T): Unit ! Emit[T] = perform(Send(value))

  /** every value of a sequence, which may be lazy and endless */
  def sendAll[T](values: IterableOnce[T]): Unit ! Emit[T] =
    def from(left: LazyList[T]): Unit ! Emit[T] =
      if left.isEmpty then pure(()) else send(left.head).flatMap(_ => from(left.tail))
    from(values.iterator.to(LazyList))

  /** the values sent, with the program's own */
  def collect[T, A, R](p: A ! (Emit[T] & R)): (List[T], A) ! R =
    handle(new InPlace[Emit[T], List[T], A, (List[T], A), R]:
      def ret(sent: List[T], a: A) = pure((sent.reverse, a))
      def answer[X](sent: Cell[List[T]], op: Emit[T] & Op[Emit[T], X]): X = op match
        case Send(value) => sent.value = value :: sent.value
    , Nil)(p)

  /** `f` run for each value, as it is sent */
  def each[T, A, R](f: T => Unit ! R)(p: A ! (Emit[T] & R)): A ! R =
    handle(new Handler[Emit[T], A, A, R]:
      def ret(a: A) = pure(a)
      def on[X](op: Emit[T] & Op[Emit[T], X], k: X => A ! R) = op match
        case Send(value) => f(value).flatMap(k)
    )(p)

  /** a stream of the values, each through `f` */
  def map[T, U, A, R](f: T => U)(p: A ! (Emit[T] & R)): A ! (Emit[U] & R) =
    handle(new Handler[Emit[T], A, A, Emit[U] & R]:
      def ret(a: A) = pure(a)
      def on[X](op: Emit[T] & Op[Emit[T], X], k: X => A ! (Emit[U] & R)) = op match
        case Send(value) => send(f(value)).flatMap(k)
    )(p)

  /** a stream of the values that `keep` */
  def filter[T, A, R](keep: T => Boolean)(p: A ! (Emit[T] & R)): A ! (Emit[T] & R) =
    handle(new Handler[Emit[T], A, A, Emit[T] & R]:
      def ret(a: A) = pure(a)
      def on[X](op: Emit[T] & Op[Emit[T], X], k: X => A ! (Emit[T] & R)) = op match
        case Send(value) => if keep(value) then send(value).flatMap(k) else k(())
    )(p)

  /** a stream of the first `n` values; the producer is not resumed after them */
  def take[T, A, R](n: Int)(p: A ! (Emit[T] & R)): Unit ! (Emit[T] & R) =
    type Left = Int => Unit ! (Emit[T] & R)
    handle(new Handler[Emit[T], A, Left, Emit[T] & R]:
      def ret(a: A) = pure(_ => pure(()))
      def on[X](op: Emit[T] & Op[Emit[T], X], k: X => Left ! (Emit[T] & R)) = op match
        case Send(value) => pure: left =>
          if left <= 0 then pure(())
          else send(value).flatMap(_ => if left == 1 then pure(()) else k(()).flatMap(_(left - 1)))
    )(p).flatMap(_(n))

  /** the values, pulled as they are needed: the producer runs no further than was asked */
  def pull[T, A](p: A ! Emit[T]): LazyList[T] =
    run(handle(new Handler[Emit[T], A, LazyList[T], Any]:
      def ret(a: A) = pure(LazyList.empty)
      def on[X](op: Emit[T] & Op[Emit[T], X], k: X => LazyList[T] ! Any) = op match
        case Send(value) => pure(value #:: run(k(())))
    )(p))

  /** two streams, value by value in pairs, as long as both last */
  def zip[T, U, A, B](a: A ! Emit[T], b: B ! Emit[U]): Unit ! Emit[(T, U)] = sendAll(pull(a).zip(pull(b)))

  /** two streams in turn, a value of each; then what is left of the longer */
  def merge[T, A, B](a: A ! Emit[T], b: B ! Emit[T]): Unit ! Emit[T] =
    def turns(first: LazyList[T], second: LazyList[T]): LazyList[T] =
      if first.isEmpty then second else first.head #:: turns(second, first.tail)
    sendAll(turns(pull(a), pull(b)))
