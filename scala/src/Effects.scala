package okay.effects.scala

import scala.reflect.ClassTag

/** An operation of the effect `E`: a question as data, answered by an `X`. */
trait Op[-E, X]

/** A computation of an `A` that may ask the operations of the row `E`. Only data: nothing runs until `step`. */
sealed abstract class Comp[-E, +A]:
  final def flatMap[F, B](f: A => Comp[F, B]): Comp[E & F, B] = new Comp.Bind(this, f)
  final def map[B](f: A => B): Comp[E, B] = new Comp.Bind(this, a => new Comp.Done(f(a)))

object Comp:
  /** a value */
  final class Done[A](val value: A) extends Comp[Any, A]
  /** a question: an operation, to be answered */
  final class Ask[E, X](val op: Op[E, X]) extends Comp[E, X]
  /** a computation, and what follows its value */
  final class Bind[E, X, A](val first: Comp[E, X], val next: X => Comp[E, A]) extends Comp[E, A]
  /** a computation under a handler, with the handler's state */
  private[scala] final class Handle[E, A](val mark: Machine.Mark, val state: Any, val body: Comp[?, ?])
      extends Comp[E, A]
  /** a continuation taken earlier, resumed with an answer */
  private[scala] final class Resume[E, A](val k: Machine.Pieces, val x: Any) extends Comp[E, A]

/** `A ! E`: an `A`, asking the effects of the row `E`. A row is an intersection: `State[Int] + Choose`. */
infix type ![+A, -E] = Comp[E, A]
infix type +[E, F] = E & F

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
enum Step[-E, +A]:
  case Done[A](value: A) extends Step[Any, A]
  case Asked[E, X, A](op: Op[E, X], k: X => A ! E) extends Step[E, A]

/** An operation that reached the top: its effect has no handler. */
final class NoHandler(val op: Op[?, ?]) extends RuntimeException("an operation with no handler: " + op)

/** THE GENERAL HANDLER of the effect `F`, in a program of an `A`: a `B`, asking what is left, `R`. A clause is
 * given the rest of the program, `k`, as a value: to resume once, many times, later, or never. */
trait Handler[F, A, B, R]:
  def ret(a: A): B ! R
  def on[X](op: F & Op[F, X], k: X => B ! R): B ! R
  /** of the operations of `F`, those this handler answers; any other passes outside */
  def mine(op: F): Boolean = true

/** THE HANDLER IN PLACE: every operation answered at once, from a state the handler keeps — nothing is captured,
 * the program goes on. The state belongs to the path: a continuation resumed twice starts twice from the state it
 * was taken with. */
trait InPlace[F, S, A, B, R]:
  def ret(state: S, a: A): B ! R
  def answer[X](state: Cell[S], op: F & Op[F, X]): X
  def mine(op: F): Boolean = true

/** A handler's state, while a clause answers. */
final class Cell[S](var value: S)

def pure[A](a: A): A ! Any = new Comp.Done(a)

def perform[E, X](op: Op[E, X]): X ! E = new Comp.Ask(op)

/** a computation built when it is reached: a call in tail position takes no stack */
def defer[E, A](p: => A ! E): A ! E = new Comp.Bind(Machine.unit, _ => p)

/** the operations of `F` answered by `h`, every other one passing outside; `F` leaves the row */
def handle[F, R, A, B](h: Handler[F, A, B, R])(p: A ! (F & R))(using F: ClassTag[F]): B ! R =
  new Comp.Handle(new Machine.Mark(F.runtimeClass, h, null), null, p)

/** the same, in place, from the state `s0` */
def handle[F, S, R, A, B](h: InPlace[F, S, A, B, R], s0: S)(p: A ! (F & R))(using F: ClassTag[F]): B ! R =
  new Comp.Handle(new Machine.Mark(F.runtimeClass, null, h), s0, p)

/** `handle`, the effect kept in the row: a clause may ask the operation again — interception, audit, delegation. */
def intercept[F: ClassTag, R <: F, A, B](h: Handler[F, A, B, R])(p: A ! R): B ! R = handle[F, R, A, B](h)(p)

def intercept[F: ClassTag, S, R <: F, A, B](h: InPlace[F, S, A, B, R], s0: S)(p: A ! R): B ! R =
  handle[F, S, R, A, B](h, s0)(p)

/** a computation run to its next question that no handler in it answers */
def step[E, A](p: A ! E): Step[E, A] = Machine.run(p)

/** a program with nothing left to ask, run to its value */
def run[A](p: A ! Any): A = step(p) match
  case Step.Done(a) => a
  case Step.Asked(op, _) => throw NoHandler(op)
