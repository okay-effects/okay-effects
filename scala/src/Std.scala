package okay.algebraic.scala3.std

import okay.algebraic.scala3.*

/** STATE. An effect is a sealed family of operations — data; each says what answers it. */
sealed trait State[S]
object State:
  final case class Get[S]() extends State[S], Op[State[S], S]
  final case class Put[S](s: S) extends State[S], Op[State[S], Unit]

  def get[S]: S ! State[S] = perform(Get[S]())
  def put[S](s: S): Unit ! State[S] = perform(Put(s))

  /** the state passed along: a program's answer is a function of the state it is resumed in */
  def run[S, A, R](s0: S)(p: A ! (State[S] & R)): (S, A) ! R =
    val h = new Handler[State[S], A, S => (S, A) ! R, R]:
      def ret(a: A) = pure(s => pure((s, a)))
      def on[X](op: State[S] & Op[State[S], X], k: X => (S => (S, A) ! R) ! R) = op match
        case Get() => pure(s => k(s).flatMap(_(s)))
        case Put(s) => pure(_ => k(()).flatMap(_(s)))
    handle(h)(p).flatMap(_(s0))

/** WRITER: values told, collected in order. */
sealed trait Writer[W]
object Writer:
  final case class Tell[W](w: W) extends Writer[W], Op[Writer[W], Unit]

  def tell[W](w: W): Unit ! Writer[W] = perform(Tell(w))

  def run[W, A, R](p: A ! (Writer[W] & R)): (List[W], A) ! R =
    handle(new Handler[Writer[W], A, (List[W], A), R]:
      def ret(a: A) = pure((Nil, a))
      def on[X](op: Writer[W] & Op[Writer[W], X], k: X => (List[W], A) ! R) = op match
        case Tell(w) => k(()).map((ws, a) => (w :: ws, a))
    )(p)

/** THROWS: the rest of the program is dropped. */
sealed trait Throws[E]
object Throws:
  final case class Raise[E](error: E) extends Throws[E], Op[Throws[E], Nothing]

  def raise[E](error: E): Nothing ! Throws[E] = perform(Raise(error))

  def run[E, A, R](p: A ! (Throws[E] & R)): Either[E, A] ! R =
    handle(new Handler[Throws[E], A, Either[E, A], R]:
      def ret(a: A) = pure(Right(a))
      def on[X](op: Throws[E] & Op[Throws[E], X], k: X => Either[E, A] ! R) = op match
        case Raise(error) => pure(Left(error))
    )(p)

/** CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times. */
sealed trait Choose
object Choose:
  final case class Among[X](xs: List[X]) extends Choose, Op[Choose, X]

  def among[X](xs: X*): X ! Choose = perform(Among(xs.toList))

  /** every value the program comes to */
  def all[A, R](p: A ! (Choose & R)): List[A] ! R =
    handle(new Handler[Choose, A, List[A], R]:
      def ret(a: A) = pure(List(a))
      def on[X](op: Choose & Op[Choose, X], k: X => List[A] ! R) = op match
        case Among(xs) => xs.foldLeft(pure(Nil): List[A] ! R)((all, x) => all.flatMap(as => k(x).map(as ++ _)))
    )(p)

/** EMIT: a stream, possibly endless — the handler takes one value and keeps the rest for later. */
sealed trait Emit[T]
object Emit:
  final case class Send[T](value: T) extends Emit[T], Op[Emit[T], Unit]

  def send[T](value: T): Unit ! Emit[T] = perform(Send(value))

  /** the values sent, pulled as they are needed */
  def stream[T, A](p: A ! Emit[T]): LazyList[T] =
    run(handle(new Handler[Emit[T], A, LazyList[T], Any]:
      def ret(a: A) = pure(LazyList.empty)
      def on[X](op: Emit[T] & Op[Emit[T], X], k: X => LazyList[T] ! Any) = op match
        case Send(value) => pure(value #:: run(k(())))
    )(p))

/** ASYNC: the answer comes later — the handler keeps the rest and returns; who has the answer resumes it. */
sealed trait Async
object Async:
  final case class Await[X](register: (X => Unit) => Unit) extends Async, Op[Async, X]

  def await[X](register: (X => Unit) => Unit): X ! Async = perform(Await(register))

  /** the program started; `done` is called with its value, now or later */
  def start[A](p: A ! Async)(done: A => Unit): Unit =
    run(handle(new Handler[Async, A, Unit, Any]:
      def ret(a: A) = pure(done(a))
      def on[X](op: Async & Op[Async, X], k: X => Unit ! Any) = op match
        case Await(register) => pure(register(x => run(k(x))))
    )(p))
