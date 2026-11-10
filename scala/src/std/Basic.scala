package okay.effects.scala.std

import okay.effects.scala.*

/*
 * THE STANDARD EFFECTS: each an enum of its operations — data; each says what answers it — its words, its
 * handlers. A word finds its row from the evidence in scope — `def tick[R[_]: Has[State % Int]]` lets `State.get`
 * and `State.put(n)` say nothing more — or from what is expected.
 */

/** STATE of type `S`, read and written. */
enum State[S, X]:
  case Get[S]() extends State[S, S]
  case Put[S](s: S) extends State[S, Unit]

object State:
  def get[S, R[_]](using In[State % S, R]): S ! R = perform[State % S](Get())
  def put[S, R[_]](s: S)(using In[State % S, R]): Unit ! R = perform[State % S](Put(s))
  def modify[S, R[_]](f: S => S)(using In[State % S, R]): Unit ! R = get[S, R].flatMap(s => put(f(s)))

  /** the state kept by the handler, answered in place; each path of the program has its own */
  def run[S, R[_], Rest[_], A](init: S)(p: A ! R)(using Split[State % S, R, Rest]): (S, A) ! Rest =
    handle(p, Handler.stateful[State % S, Rest, A, (S, A), S](init)((s, a) => pure((s, a)))(
      [X] => (op: State[S, X], cell: Cell[S]) => op match
        case Get() => cell.get
        case Put(s) => cell.set(s)))

/** READER: a value of the surroundings. */
enum Reader[V, X]:
  case Ask[V]() extends Reader[V, V]

object Reader:
  def ask[V, R[_]](using In[Reader % V, R]): V ! R = perform[Reader % V](Ask())

  def run[V, R[_], Rest[_], A](value: V)(p: A ! R)(using Split[Reader % V, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Reader % V, Rest, A, A](pure)([X] => (op: Reader[V, X]) => op match
      case Ask() => value))

  /** the program, its asks answered by the reader outside, through `f`: an interceptor */
  def local[V, R[_], A](f: V => V)(p: A ! R)(using In[Reader % V, R]): A ! R =
    intercept(p, Handler.general[Reader % V, R, A, A](pure)([X] => (op: Reader[V, X], k: X => A ! R) => op match
      case Ask() => ask[V, R].flatMap(v => k(f(v)))))

/** WRITER: values told, collected in order. */
enum Writer[W, X]:
  case Tell[W](w: W) extends Writer[W, Unit]

object Writer:
  def tell[W, R[_]](w: W)(using In[Writer % W, R]): Unit ! R = perform[Writer % W](Tell(w))

  def run[W, R[_], Rest[_], A](p: A ! R)(using Split[Writer % W, R, Rest]): (List[W], A) ! Rest =
    handle(p, Handler.stateful[Writer % W, Rest, A, (List[W], A), List[W]](Nil)((told, a) => pure((told.reverse, a)))(
      [X] => (op: Writer[W, X], told: Cell[List[W]]) => op match
        case Tell(w) => told.set(w :: told.get)))

/** THROWS: the rest of the program is dropped. */
enum Throws[E, X]:
  case Raise[E](error: E) extends Throws[E, Nothing]

object Throws:
  def raise[E, R[_]](error: E)(using In[Throws % E, R]): Nothing ! R = perform[Throws % E](Raise(error))

  def run[E, R[_], Rest[_], A](p: A ! R)(using Split[Throws % E, R, Rest]): Either[E, A] ! Rest =
    handle(p, Handler.abort[Throws % E, Rest, A, Either[E, A]](a => pure(Right(a)))(
      [X] => (op: Throws[E, X]) => op match
        case Raise(error) => pure(Left(error))))

  /** the program, or what `rescue` makes of its failure */
  def recover[E, R[_], Rest[_], A](rescue: E => A ! Rest)(p: A ! R)(using Split[Throws % E, R, Rest]): A ! Rest =
    handle(p, Handler.abort[Throws % E, Rest, A, A](pure)([X] => (op: Throws[E, X]) => op match
      case Raise(error) => rescue(error)))

/** CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times. */
enum Choose[X]:
  case Among[X](xs: List[X]) extends Choose[X]

object Choose:
  def among[X, R[_]](xs: X*)(using In[Choose, R]): X ! R = perform[Choose](Among(xs.toList))
  /** no value: this path ends */
  def none[R[_]](using In[Choose, R]): Nothing ! R = perform[Choose](Among(Nil))

  /** every value the program comes to */
  def all[R[_], Rest[_], A](p: A ! R)(using Split[Choose, R, Rest]): List[A] ! Rest =
    handle(p, Handler.general[Choose, Rest, A, List[A]](a => pure(List(a)))(
      [X] => (op: Choose[X], k: X => List[A] ! Rest) => op match
        case Among(xs) =>
          xs.foldLeft(pure[List[A], Rest](Nil))((all, x) => all.flatMap(as => k(x).map(as ++ _)))))

  /** the first value the program comes to, the other paths not taken */
  def first[R[_], Rest[_], A](p: A ! R)(using Split[Choose, R, Rest]): Option[A] ! Rest =
    handle(p, Handler.general[Choose, Rest, A, Option[A]](a => pure(Some(a)))(
      [X] => (op: Choose[X], k: X => Option[A] ! Rest) => op match
        case Among(xs) =>
          def from(left: List[X]): Option[A] ! Rest = left match
            case Nil => pure(None)
            case x :: rest => k(x).flatMap(found => if found.isDefined then pure(found) else from(rest))
          from(xs)))

/** FRESH: a number never given before, in this run. */
enum Fresh[X]:
  case Next extends Fresh[Long]

object Fresh:
  def fresh[R[_]](using In[Fresh, R]): Long ! R = perform[Fresh](Next)

  def run[R[_], Rest[_], A](p: A ! R)(using Split[Fresh, R, Rest]): A ! Rest =
    handle(p, Handler.stateful[Fresh, Rest, A, A, Long](0L)((_, a) => pure(a))(
      [X] => (op: Fresh[X], next: Cell[Long]) => op match
        case Next => next.set(next.get + 1); next.get - 1))

/** CLOCK: the time, in milliseconds — the system's, or one a test sets. */
enum Clock[X]:
  case Now extends Clock[Long]

object Clock:
  def now[R[_]](using In[Clock, R]): Long ! R = perform[Clock](Now)

  def system[R[_], Rest[_], A](p: A ! R)(using Split[Clock, R, Rest]): A ! Rest =
    at(() => System.currentTimeMillis())(p)

  def at[R[_], Rest[_], A](time: () => Long)(p: A ! R)(using Split[Clock, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Clock, Rest, A, A](pure)([X] => (op: Clock[X]) => op match
      case Now => time()))
