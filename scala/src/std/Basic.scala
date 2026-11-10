package okay.effects.scala.std

import okay.effects.scala.*

/** STATE. An effect is a sealed family of operations — data; each says what answers it. */
sealed trait State[S]
object State:
  final case class Get[S]() extends State[S], Op[State[S], S]
  final case class Put[S](s: S) extends State[S], Op[State[S], Unit]

  def get[S]: S ! State[S] = perform(Get[S]())
  def put[S](s: S): Unit ! State[S] = perform(Put(s))
  def modify[S](f: S => S): Unit ! State[S] = get[S].flatMap(s => put(f(s)))

  /** the state kept by the handler, answered in place; each path of the program has its own */
  def run[S, A, R](s0: S)(p: A ! (State[S] & R)): (S, A) ! R =
    handle(new InPlace[State[S], S, A, (S, A), R]:
      def ret(s: S, a: A) = pure((s, a))
      def answer[X](state: Cell[S], op: State[S] & Op[State[S], X]): X = op match
        case Get() => state.value
        case Put(s) => state.value = s
    , s0)(p)

/** READER: a value of the surroundings. */
sealed trait Reader[V]
object Reader:
  final case class Ask[V]() extends Reader[V], Op[Reader[V], V]

  def ask[V]: V ! Reader[V] = perform(Ask[V]())

  def run[V, A, R](value: V)(p: A ! (Reader[V] & R)): A ! R =
    handle(new InPlace[Reader[V], Unit, A, A, R]:
      def ret(state: Unit, a: A) = pure(a)
      def answer[X](state: Cell[Unit], op: Reader[V] & Op[Reader[V], X]): X = op match
        case Ask() => value
    , ())(p)

  /** the program, its asks answered by the reader outside, through `f`: an interceptor */
  def local[V, A, R <: Reader[V]](f: V => V)(p: A ! R): A ! R =
    intercept(new Handler[Reader[V], A, A, R]:
      def ret(a: A) = pure(a)
      def on[X](op: Reader[V] & Op[Reader[V], X], k: X => A ! R) = op match
        case Ask() => ask[V].flatMap(v => k(f(v)))
    )(p)

/** WRITER: values told, collected in order. */
sealed trait Writer[W]
object Writer:
  final case class Tell[W](w: W) extends Writer[W], Op[Writer[W], Unit]

  def tell[W](w: W): Unit ! Writer[W] = perform(Tell(w))

  def run[W, A, R](p: A ! (Writer[W] & R)): (List[W], A) ! R =
    handle(new InPlace[Writer[W], List[W], A, (List[W], A), R]:
      def ret(told: List[W], a: A) = pure((told.reverse, a))
      def answer[X](told: Cell[List[W]], op: Writer[W] & Op[Writer[W], X]): X = op match
        case Tell(w) => told.value = w :: told.value
    , Nil)(p)

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

  /** the program, or what `rescue` makes of its failure */
  def recover[E, A, R](rescue: E => A ! R)(p: A ! (Throws[E] & R)): A ! R =
    handle(new Handler[Throws[E], A, A, R]:
      def ret(a: A) = pure(a)
      def on[X](op: Throws[E] & Op[Throws[E], X], k: X => A ! R) = op match
        case Raise(error) => rescue(error)
    )(p)

/** CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times. */
sealed trait Choose
object Choose:
  final case class Among[X](xs: List[X]) extends Choose, Op[Choose, X]

  def among[X](xs: X*): X ! Choose = perform(Among(xs.toList))
  /** no value: this path ends */
  def none: Nothing ! Choose = perform(Among(Nil))

  /** every value the program comes to */
  def all[A, R](p: A ! (Choose & R)): List[A] ! R =
    handle(new Handler[Choose, A, List[A], R]:
      def ret(a: A) = pure(List(a))
      def on[X](op: Choose & Op[Choose, X], k: X => List[A] ! R) = op match
        case Among(xs) => xs.foldLeft(pure(Nil): List[A] ! R)((all, x) => all.flatMap(as => k(x).map(as ++ _)))
    )(p)

  /** the first value the program comes to, the other paths not taken */
  def first[A, R](p: A ! (Choose & R)): Option[A] ! R =
    handle(new Handler[Choose, A, Option[A], R]:
      def ret(a: A) = pure(Some(a))
      def on[X](op: Choose & Op[Choose, X], k: X => Option[A] ! R) = op match
        case Among(xs) =>
          def from(left: List[X]): Option[A] ! R = left match
            case Nil => pure(None)
            case x :: rest => k(x).flatMap(found => if found.isDefined then pure(found) else from(rest))
          from(xs)
    )(p)

/** FRESH: a number never given before, in this run. */
sealed trait Fresh
object Fresh:
  case object Next extends Fresh, Op[Fresh, Long]

  def fresh: Long ! Fresh = perform(Next)

  def run[A, R](p: A ! (Fresh & R)): A ! R =
    handle(new InPlace[Fresh, Long, A, A, R]:
      def ret(next: Long, a: A) = pure(a)
      def answer[X](next: Cell[Long], op: Fresh & Op[Fresh, X]): X = op match
        case Next => next.value += 1; next.value - 1
    , 0L)(p)

/** CLOCK: the time, in milliseconds — the system's, or one a test sets. */
sealed trait Clock
object Clock:
  case object Now extends Clock, Op[Clock, Long]

  def now: Long ! Clock = perform(Now)

  def system[A, R](p: A ! (Clock & R)): A ! R = at(() => System.currentTimeMillis())(p)

  def at[A, R](time: () => Long)(p: A ! (Clock & R)): A ! R =
    handle(new InPlace[Clock, Unit, A, A, R]:
      def ret(state: Unit, a: A) = pure(a)
      def answer[X](state: Cell[Unit], op: Clock & Op[Clock, X]): X = op match
        case Now => time()
    , ())(p)
