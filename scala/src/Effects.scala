package okay.effects.scala

/** A computation of an `A` that may ask the operations of the row `R`. Only data: nothing runs until `step`. */
sealed abstract class Comp[R[_], +A]:
  final def flatMap[B](f: A => B ! R): B ! R = new Comp.Bind(this, f)
  final def map[B](f: A => B): B ! R = new Comp.Bind(this, a => new Comp.Done(f(a)))

object Comp:
  /** a value */
  final class Done[R[_], +A](val value: A) extends Comp[R, A]
  /** a question: an operation of the effect at the place `at` of the row, to be answered */
  final class Ask[R[_], E[_], X](val at: In[E, R], val op: E[X]) extends Comp[R, X]
  /** a computation, and what follows its value */
  final class Bind[R[_], X, +A](val first: Comp[R, X], val next: X => Comp[R, A]) extends Comp[R, A]
  /** a computation under a handler */
  private[scala] final class Handle[R[_], +A](val mark: Machine.Mark, val body: Comp[?, ?]) extends Comp[R, A]
  /** a continuation taken earlier, resumed with an answer */
  private[scala] final class Resume[R[_], +A](val k: Machine.Pieces, val x: Any) extends Comp[R, A]

/** `A ! R`: a program of an `A` over the row `R` — `Int ! State % Int + Choose` */
infix type ![+A, R[_]] = Comp[R, A]

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
sealed trait Step[R[_], +A]

object Step:
  final case class Done[R[_], +A](value: A) extends Step[R, A]

  /** an operation no handler answered: its place in the row, and the rest of the program */
  final case class Asked[R[_], E[_], X, +A](at: In[E, R], op: E[X], k: X => A ! R) extends Step[R, A]:
    /** this, as an operation of `F`, when it is one: when its place is `F`'s place */
    def of[F[_]](using place: In[F, R]): Option[Asked[R, F, X, A]] =
      // a cast: one place of a row is one effect, so the operation at `F`'s place is an `F[X]`
      if numberOf(at) == numberOf(place) then Some(this.asInstanceOf[Asked[R, F, X, A]]) else None

/** An operation that reached the top: its effect has no handler. The types leave no way to it but a cast. */
final class NoHandler(val op: Any) extends RuntimeException("an operation with no handler: " + op)

/** A handler's state, read and written by its clause while it answers. */
final class Cell[S](private[scala] var state: S):
  def get: S = state
  def set(s: S): Unit = state = s

/** A HANDLER of the effect `E`, the row outside it `R`, in a program of an `A`: a `B`. The machine has two shapes.
 * A clause takes an operation `E[X]` as it is, and its match learns `X` from the case: `case Get() => cell.get`
 * answers an `S`. Made by `Handler.general`, `tail`, `abort`, `stateful`. */
enum Handler[E[_], R[_], A, B]:
  /** THE GENERAL HANDLER: a clause is given the rest of the program, `k`, as a value: to resume once, many times,
   * later, or never */
  case General(ret: A => B ! R, on: [X] => (E[X], X => B ! R) => B ! R)
  /** THE HANDLER IN PLACE: every operation answered at once, from a state the handler keeps — nothing is captured,
   * the program goes on. The state belongs to the path: a continuation resumed twice starts twice from the state
   * it was taken with. */
  case Stateful[E[_], R[_], A, B, S](init: S, ret: (S, A) => B ! R, answer: [X] => (E[X], Cell[S]) => X)
      extends Handler[E, R, A, B]

/** a handler of each shape, its value's clause first, its operations' after */
object Handler:
  /** `on` gets the operation and `k`, the rest of the program under this handler */
  def general[E[_], R[_], A, B](ret: A => B ! R)(on: [X] => (E[X], X => B ! R) => B ! R): Handler[E, R, A, B] =
    General(ret, on)

  /** each operation answered in place, from the state in the cell */
  def stateful[E[_], R[_], A, B, S](init: S)(ret: (S, A) => B ! R)(answer: [X] => (E[X], Cell[S]) => X)
      : Handler[E, R, A, B] = Stateful(init, ret, answer)

  /** each operation answered in place: a handler in place with nothing to keep */
  def tail[E[_], R[_], A, B](ret: A => B ! R)(answer: [X] => E[X] => X): Handler[E, R, A, B] =
    Stateful[E, R, A, B, Unit]((), (_, a) => ret(a), [X] => (op: E[X], _: Cell[Unit]) => answer(op))

  /** each operation ends the program: `abort`'s is the handler's result — a general handler that drops `k` */
  def abort[E[_], R[_], A, B](ret: A => B ! R)(abort: [X] => E[X] => B ! R): Handler[E, R, A, B] =
    General(ret, [X] => (op: E[X], _: X => B ! R) => abort(op))

def pure[A, R[_]](a: A): A ! R = new Comp.Done(a)

/** the value `()` */
def unit[R[_]]: Unit ! R = new Comp.Done(())

/** an operation of `E`: `perform[State % Int](State.Get())` — the effect said, the answer's type from the
 * operation, the row from the evidence in scope or from what is expected. (Scala takes all of a method's type
 * arguments or none, so the rest are `Performing`'s; it is nothing at run time.) */
def perform[E[_]]: Performing[E] = ()

opaque type Performing[E[_]] = Unit

extension [E[_]](performing: Performing[E])
  def apply[R[_], X](op: E[X])(using at: In[E, R]): X ! R = new Comp.Ask(at, op)

/** a computation built when it is reached: a call in tail position takes no stack */
def defer[A, R[_]](p: => A ! R): A ! R = new Comp.Bind(unit[R], _ => p)

/** `E` handled by `h` from wherever it is in `p`'s row: the program over the rest */
def handle[E[_], R[_], Rest[_], A, B](p: A ! R, h: Handler[E, Rest, A, B])(using at: Split[E, R, Rest]): B ! Rest =
  new Comp.Handle(new Machine.Mark(numberOf(at), true, h), p)

/** `handle`, the effect kept in the row: each operation at `E`'s place is given to `h` first, and a clause may
 * ask it again — it goes to the handler of that place further out. Interception, audit, delegation. */
def intercept[E[_], R[_], A, B](p: A ! R, h: Handler[E, R, A, B])(using at: In[E, R]): B ! R =
  new Comp.Handle(new Machine.Mark(numberOf(at), false, h), p)

/** a computation run to its next question that no handler in it answers */
def step[R[_], A](p: A ! R): Step[R, A] = Machine.run(p)

/** a program with nothing left to ask, run to its value */
def run[A](p: A ! Pure): A = step(p) match
  case Step.Done(a) => a
  case Step.Asked(_, op, _) => throw NoHandler(op)
