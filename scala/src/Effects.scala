package okay.algebraic.scala3

import scala.annotation.tailrec
import scala.reflect.ClassTag

/** An operation of the effect `E`: a question as data, answered by an `X`. */
trait Op[-E, X]

/** A computation of an `A` that may ask the operations of the row `E`. Only data: nothing runs until `step`. */
enum Comp[-E, +A]:
  /** a value */
  case Done[A](value: A) extends Comp[Any, A]
  /** a question: an operation, to be answered */
  case Ask[E, X](op: Op[E, X]) extends Comp[E, X]
  /** a computation, and what follows its value */
  case Bind[E, X, A](first: Comp[E, X], next: X => Comp[E, A]) extends Comp[E, A]
  /** a computation, and a continuation taken earlier put back after it */
  case Plug[E, X, A](first: Comp[E, X], rest: Rest[E, X, A]) extends Comp[E, A]

  def flatMap[F, B](f: A => Comp[F, B]): Comp[E & F, B] = Bind(this, f)
  def map[B](f: A => B): Comp[E, B] = Bind(this, a => Done(f(a)))

/** `A ! E`: an `A`, asking the effects of the row `E`. A row is an intersection: `State[Int] + Choose`. */
infix type ![+A, -E] = Comp[E, A]
infix type +[E, F] = E & F

/** A continuation as data: what waits for an `X` to come to an `A`. A value: resumed never, once or many times. */
enum Rest[-E, -X, +A]:
  case End[A]() extends Rest[Any, A, A]
  case Then[E, X, Y, A](next: X => Comp[E, Y], rest: Rest[E, Y, A]) extends Rest[E, X, A]
  case Both[E, X, Y, A](first: Rest[E, X, Y], rest: Rest[E, Y, A]) extends Rest[E, X, A]

  /** the computation resumed with the answer */
  def apply(x: X): Comp[E, A] = Comp.Plug(Comp.Done(x), this)

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
enum Step[-E, +A]:
  case Done[A](value: A) extends Step[Any, A]
  case Asked[E, X, A](op: Op[E, X], k: Rest[E, X, A]) extends Step[E, A]

/** An operation that reached the top: its effect has no handler. */
final class NoHandler(val op: Op[?, ?]) extends RuntimeException("an operation with no handler: " + op)

/** The answers of a handler of the effect `F`, in a program of an `A`: a `B`, asking what is left, `R`. */
trait Handler[F, A, B, R]:
  def ret(a: A): B ! R
  def on[X](op: F & Op[F, X], k: X => B ! R): B ! R

def pure[A](a: A): A ! Any = Comp.Done(a)

def perform[E, X](op: Op[E, X]): X ! E = Comp.Ask(op)

/** a computation built when it is reached: a call in tail position takes no stack */
def defer[E, A](p: => A ! E): A ! E = Comp.Bind(Comp.Done(()), _ => p)

/** THE MACHINE: a computation run to its next question, in a loop — constant stack, whatever the program. */
def step[E, A](p: A ! E): Step[E, A] = loop(p, Rest.End())

@tailrec private def loop[E, X, A](c: Comp[E, X], k: Rest[E, X, A]): Step[E, A] = c match
  case Comp.Ask(op) => Step.Asked(op, k)
  case Comp.Bind(first, next) => loop(first, Rest.Then(next, k))
  case Comp.Plug(first, rest) => loop(first, Rest.Both(rest, k))
  case Comp.Done(x) => k match
    case Rest.End() => Step.Done(x)
    case Rest.Then(next, rest) => loop(next(x), rest)
    case Rest.Both(first, rest) => first match
      case Rest.End() => loop(c, rest)
      case Rest.Then(next, more) => loop(next(x), Rest.Both(more, rest))
      case Rest.Both(a, b) => loop(c, Rest.Both(a, Rest.Both(b, rest)))

/** a program with nothing left to ask, run to its value */
def run[A](p: A ! Any): A = step(p) match
  case Step.Done(a) => a
  case Step.Asked(op, _) => throw NoHandler(op)

/** THE FOLD: the operations of `F` answered by `h`, every other one asked again outside; `F` leaves the row. */
def handle[F: ClassTag, R, A, B](h: Handler[F, A, B, R])(p: A ! (F & R)): B ! R =
  defer:
    step(p) match
      case Step.Done(a) => h.ret(a)
      case asked: Step.Asked[?, x, ?] => answer[F, R, A, B, x](asked.op, asked.k, h)

/** `handle`, the effect kept in the row: a clause may ask the operation again — interception, audit, delegation. */
def intercept[F: ClassTag, R <: F, A, B](h: Handler[F, A, B, R])(p: A ! R): B ! R = handle[F, R, A, B](h)(p)

/** The two casts of the kernel, and why they hold: a row is known to the compiler only. At run time an effect is its
 * class — an operation of `F`'s class was declared `extends F, Op[F, X]`; any other one belongs to the rest, `R`. */
private def answer[F, R, A, B, X](op: Op[?, X], k: Rest[F & R, X, A], h: Handler[F, A, B, R])(
    using F: ClassTag[F]
): B ! R =
  val next = (x: X) => handle[F, R, A, B](h)(k(x))
  if F.runtimeClass.isInstance(op) then h.on(op.asInstanceOf[F & Op[F, X]], next)
  else Comp.Bind(Comp.Ask(op.asInstanceOf[Op[R, X]]), next)
