package okay.effects.scala.std

import okay.effects.scala.*

/** EMIT: a stream of values, possibly endless. The producer is a program that sends; what a stream is made into —
 * a list, a loop, another stream, a lazy sequence pulled a value at a time — is the handler's choice. */
enum Emit[T, X]:
  case Send[T](value: T) extends Emit[T, Unit]

object Emit:
  def send[T](value: T): Word[Emit % T, Unit] = word(Send(value))

  /** every value of a sequence, which may be lazy and endless */
  def sendAll[T, R[_]](values: IterableOnce[T])(using In[Emit % T, R]): Unit ! R =
    def from(left: LazyList[T]): Unit ! R =
      if left.isEmpty then unit else send(left.head).flatMap(_ => from(left.tail))
    from(values.iterator.to(LazyList))

  /** the values sent, with the program's own */
  def collect[T, R[_], Rest[_], A](p: A ! R)(using Split[Emit % T, R, Rest]): (List[T], A) ! Rest =
    handle(p, Handler.stateful[Emit % T, Rest, A, (List[T], A), List[T]](Nil)((sent, a) => pure((sent.reverse, a)))(
      [X] => (op: Emit[T, X], sent: Cell[List[T]]) => op match
        case Send(value) => sent.set(value :: sent.get)))

  /** `f` run for each value, as it is sent */
  def each[T, R[_], Rest[_], A](f: T => Unit ! Rest)(p: A ! R)(using Split[Emit % T, R, Rest]): A ! Rest =
    handle(p, Handler.general[Emit % T, Rest, A, A](pure)([X] => (op: Emit[T, X], k: X => A ! Rest) => op match
      case Send(value) => f(value).flatMap(k)))

  /** a stream of the values, each through `f`: a new stream. Its row `S` is `R` with `Emit % U` for `Emit % T`:
   * the one expected, or with nothing said the new stream last. The program is put into the row of both streams,
   * and the old one is handled there. */
  def map[T, U, R[_], Rest[_], S[_], A](f: T => U)(p: A ! R)(
      using from: Split[Emit % T, R, Rest], to: Split[Emit % U, S, Rest]): A ! S =
    each[T, S + Emit % T, S, A](value => send(f(value)))(p.into(using Sub.exchanged(from, to)))

  /** a stream of the values that `keep`: an interceptor — those kept are sent on, to the stream's handler */
  def filter[T, R[_], A](keep: T => Boolean)(p: A ! R)(using In[Emit % T, R]): A ! R =
    intercept(p, Handler.general[Emit % T, R, A, A](pure)([X] => (op: Emit[T, X], k: X => A ! R) => op match
      case Send(value) => if keep(value) then send(value).flatMap(k) else k(())))

  /** a stream of the first `n` values; the producer is not resumed after them */
  def take[T, R[_], A](n: Int)(p: A ! R)(using In[Emit % T, R]): Unit ! R =
    type Left = Int => Unit ! R
    intercept(p, Handler.general[Emit % T, R, A, Left](_ => pure(_ => unit))(
      [X] => (op: Emit[T, X], k: X => Left ! R) => op match
        case Send(value) => pure: left =>
          if left <= 0 then unit
          else send(value).flatMap(_ => if left == 1 then unit else k(()).flatMap(_(left - 1)))))
      .flatMap(_(n))

  /** the values, pulled as they are needed: the producer runs no further than was asked */
  def pull[T, A](p: A ! Emit % T): LazyList[T] =
    run(handle(p, Handler.general[Emit % T, Pure, A, LazyList[T]](_ => pure(LazyList.empty))(
      [X] => (op: Emit[T, X], k: X => LazyList[T] ! Pure) => op match
        case Send(value) => pure(value #:: run(k(()))))))

  /** two streams, value by value in pairs, as long as both last */
  def zip[T, U, A, B, R[_]](a: A ! Emit % T, b: B ! Emit % U)(using In[Emit % (T, U), R]): Unit ! R =
    sendAll(pull(a).zip(pull(b)))

  /** two streams in turn, a value of each; then what is left of the longer */
  def merge[T, A, B, R[_]](a: A ! Emit % T, b: B ! Emit % T)(using In[Emit % T, R]): Unit ! R =
    def turns(first: LazyList[T], second: LazyList[T]): LazyList[T] =
      if first.isEmpty then second else first.head #:: turns(second, first.tail)
    sendAll(turns(pull(a), pull(b)))
