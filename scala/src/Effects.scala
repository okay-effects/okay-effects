package okay.effects.scala

import scala.language.implicitConversions

/** A computation of an `A` that may ask the operations of the row `R`. Only data: nothing runs until `step`. */
sealed abstract class Comp[R[_], +A]:
  /** A BIND. Its row `S` is the one expected of it — the declared type of the program it is in; with none
   * expected, the row of what `f` gives; or else this program's own. This program need not be of that row: one of
   * a smaller row is put into it, as `into` does (`Fits`). THE COST of that: it runs under a level that renames
   * each of its operations on the way to their handlers (Machine.scala) — a level made and dropped at every such
   * bind, which a program written in the row itself, or generic in its row, does not pay. */
  final def flatMap[S[_], B, V >: A](f: V => B ! S)(using fits: Fits[V ! R, S, V]): B ! S = Comp.Bind(fits(this), f)
  /** a bind of a function to a value; its row is the one expected of it, or this program's own */
  final def map[S[_], B, V >: A](f: V => B)(using fits: Fits[V ! R, S, V]): B ! S =
    Comp.Bind(fits(this), a => Comp.Done(f(a)))
  /** this program in a row that has its effects, and others: `p.into[S]` */
  final def into[S[_]](using sub: Sub[R, S]): A ! S = Comp.embedded(this, sub)
  /** this program, and `finalizer` when it is left: `ensuring(this)(finalizer)` */
  final def ensuring(finalizer: => Any ! R): A ! R = Comp.Ensure(this, () => finalizer)

object Comp:
  /** a value */
  final case class Done[R[_], +A](value: A) extends Comp[R, A]
  /** a question: an operation of the effect at the place `at` of the row, to be answered */
  final case class Ask[R[_], E[_], X](at: Place[E, R, ?], op: E[X]) extends Comp[R, X]
  /** a computation, and what follows its value */
  final case class Bind[R[_], X, +A](first: Comp[R, X], next: X => Comp[R, A]) extends Comp[R, A]
  /** a computation under a handler of `E`: the row outside it is `Out` */
  private[scala] final case class Handle[E[_], R[_], Out[_], A, B](way: Machine.Way[E, R, Out],
      handler: Handler[E, Out, A, B], body: Comp[R, A]) extends Comp[Out, B]
  /** a computation of the row `R`, in the row `S` */
  private[scala] final case class Embedded[R[_], S[_], +A](sub: Sub[R, S], body: Comp[R, A]) extends Comp[S, A]
  /** a continuation taken earlier, resumed with an answer */
  private[scala] final case class Resume[R0[_], V0, X, R[_], A](k: Machine.K[R0, V0, X, R, A], x: X)
      extends Comp[R, A]
  /** a computation, and the finalizer that runs when it is left: with its value, or unwound */
  private[scala] final case class Ensure[R[_], +A](body: Comp[R, A], finalizer: () => Comp[R, Any])
      extends Comp[R, A]
  /** a continuation taken earlier, unwound: its finalizers run, and nothing else of it */
  private[scala] final case class Unwind[R0[_], V0, X, R[_], A](k: Machine.K[R0, V0, X, R, A])
      extends Comp[R, Unit]

  /** a word is a program of any row that has its effect: its place is found where the row is known */
  implicit def placed[E[_], X, R[_]](w: Word[E, X])(using at: In[E, R]): X ! R = w.into

  /** a program is a program of any row that has its effects: where a bigger row is expected of it — an argument,
   * a result, a branch — it is put into that row, as `into` does, and at the cost `flatMap` says */
  implicit def widened[R[_], S[_], A](p: A ! R)(using sub: Sub[R, S]): A ! S = p.into

  /** `p`, its places those of `S`. In the same row it is the same program; an operation alone, or a value, is
   * made anew; any other is run under a level that renames what passes it (Machine.scala). */
  private def embedded[R[_], S[_], A](p: A ! R, sub: Sub[R, S]): A ! S = sub match
    case Sub.Same() => p
    case _ => p match
      case Ask(at, op) => Ask(sub(at, op), op)
      case Done(value) => Done(value)
      case _ => Embedded(sub, p)

/** `P` IS A PROGRAM OF THE ROW `R`, to a `V`: as it is — and then this is nothing at run time; or a word, by its
 * effect's place; or a program of a smaller row, put into `R` as `into` does — under a level that renames what
 * passes it (Machine.scala). What a bind asks of its program, and a branch of each of its parts. */
trait Fits[-P, R[_], +V]:
  def apply(p: P): V ! R

object Fits extends FitsInto:
  given same[R[_], A]: Fits[A ! R, R, A] = p => p
  given word[E[_], X, R[_]](using at: In[E, R]): Fits[Word[E, X], R, X] = w => w.into

trait FitsInto:
  given smaller[R[_], S[_], A](using sub: Sub[R, S]): Fits[A ! R, S, A] = p => p.into

/** `A ! R`: a program of an `A` over the row `R` — `Int ! State % Int + Choose` */
infix type ![+A, R[_]] = Comp[R, A]

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
sealed trait Step[R[_], +A]

object Step:
  final case class Done[R[_], +A](value: A) extends Step[R, A]

  /** an operation no handler answered: its place in the row, and the rest of the program */
  final case class Asked[R[_], E[_], X, +A](at: In[E, R], op: E[X], k: Continuation[X, R, A]) extends Step[R, A]:
    /** this, as an operation of `F`, when it is one: when its place is `F`'s place */
    def of[F[_]](using place: In[F, R]): Option[Asked[R, F, X, A]] =
      Option(at.place.same[F, [G[_]] =>> Asked[R, G, X, A]](place.place, this))

/** An operation whose place says no effect: a row was given where an effect is meant — handled, or put into
 * another row, as if it were one of its own effects. Rows and effects are of one kind, so the types cannot refuse
 * it. */
final class NoHandler(val op: Any) extends RuntimeException("an operation with no handler: " + op)

/** The rest of an unwinding resumed where the unwinding is not: a continuation taken from a finalizer while it ran
 * in an unwinding, by a handler inside the part unwound, was kept and resumed on another stack. The finalizers
 * have run; there is no value to go on with. */
final class NoUnwinding extends RuntimeException("the rest of an unwinding was resumed outside the unwinding")

/** THE REST OF A PROGRAM, as a general handler's clause is given it: `k`. A function — `k(x)` is the program that
 * RESUMES it with the answer `x`. And `k.unwind` is the program that LEAVES it: the finalizers that wait in it
 * (`ensuring`) run, the innermost first, and nothing else of it does; then `()`. The handler that unwinds has
 * left: an operation of its own that a finalizer asks then is not answered by it — it goes to the handler
 * outside, if this one kept its effect in the row (`intercept`), and else the finalizer ends there. A failure of
 * a finalizer answered inside what is unwound does not stop the unwinding. A `k` is a value, and nothing
 * is remembered of what was done with it: every resumption is a path, which runs its finalizers at its end, and
 * every unwinding is the end of a path — unwound twice, the finalizers run twice. A clause that neither resumes
 * nor unwinds `k` runs nothing of it: the rest of the program is kept, and may be resumed later (`Async`). */
abstract class Continuation[-X, R[_], +B] private[scala] () extends (X => B ! R):
  /** this continuation left: its finalizers, then `()` */
  def unwind: Unit ! R

/** A handler's state, read and written by its clause while it answers. */
abstract class Cell[S] private[scala] (private[scala] var state: S):
  final def get: S = state
  final def set(s: S): Unit = state = s

/** A HANDLER of the effect `E`, the row outside it `R`, in a program of an `A`: a `B`. The machine has two shapes.
 * A clause takes an operation `E[X]` as it is, and its match learns `X` from the case: `case Get() => cell.get`
 * answers an `S`. Made by `Handler.general`, `tail`, `abort`, `stateful`. */
enum Handler[E[_], R[_], A, B]:
  /** THE GENERAL HANDLER: a clause is given the rest of the program, `k`, as a value: to resume once, many times,
   * or later; or to unwind */
  case General(ret: A => B ! R, on: [X] => (E[X], Continuation[X, R, B]) => B ! R)
  /** THE HANDLER IN PLACE: every operation answered at once, from a state the handler keeps — nothing is captured,
   * the program goes on. The state belongs to the path: a continuation resumed twice starts twice from the state
   * it was taken with. */
  case Stateful[E[_], R[_], A, B, S](init: S, ret: (S, A) => B ! R, answer: [X] => (E[X], Cell[S]) => X)
      extends Handler[E, R, A, B]

/** a handler of each shape, its value's clause first, its operations' after */
object Handler:
  /** `on` gets the operation and `k`, the rest of the program under this handler, as a function: to resume */
  def general[E[_], R[_], A, B](ret: A => B ! R)(on: [X] => (E[X], X => B ! R) => B ! R): Handler[E, R, A, B] =
    General(ret, [X] => (op: E[X], k: Continuation[X, R, B]) => on(op, k))

  /** `on` gets the operation and `k` as it is: to resume, or to unwind — a clause that does not go on with the
   * rest of the program says `k.unwind` first, so that what the program holds is released */
  def control[E[_], R[_], A, B](ret: A => B ! R)(on: [X] => (E[X], Continuation[X, R, B]) => B ! R)
      : Handler[E, R, A, B] = General(ret, on)

  /** each operation answered in place, from the state in the cell */
  def stateful[E[_], R[_], A, B, S](init: S)(ret: (S, A) => B ! R)(answer: [X] => (E[X], Cell[S]) => X)
      : Handler[E, R, A, B] = Stateful(init, ret, answer)

  /** each operation answered in place: a handler in place with nothing to keep */
  def tail[E[_], R[_], A, B](ret: A => B ! R)(answer: [X] => E[X] => X): Handler[E, R, A, B] =
    Stateful[E, R, A, B, Unit]((), (_, a) => ret(a), [X] => (op: E[X], _: Cell[Unit]) => answer(op))

  /** each operation ends the program: `abort`'s is the handler's result — a general handler that unwinds `k`,
   * then goes on with `abort` */
  def abort[E[_], R[_], A, B](ret: A => B ! R)(abort: [X] => E[X] => B ! R): Handler[E, R, A, B] =
    General(ret, [X] => (op: E[X], k: Continuation[X, R, B]) => k.unwind.flatMap(_ => abort(op)))

def pure[A, R[_]](a: A): A ! R = Comp.Done(a)

/** the value `()` */
def unit[R[_]]: Unit ! R = Comp.Done(())

/** AN OPERATION, NOT YET PLACED: a word of an effect — `State.get`, `Choose.among(1, 2)`. It says no row: the row
 * is the program's, known where the word is used, and there the compiler finds the effect's place in it. So a
 * word is bound (`flatMap`, `map`: a `for`) into a program of the row expected, is a program where one is expected,
 * and `into[R]` says the row. At run time it is the operation itself: nothing is made for it. */
opaque type Word[E[_], X] <: Unplaced[X] = E[X]

/** what a word is as far as its answer goes: branches of words come to one type by it (Branches.scala) */
opaque type Unplaced[+X] = Any

/** the word of an operation: `def deposit(n: Int) = word(Account.Deposit(n))` */
def word[E[_], X](op: E[X]): Word[E, X] = op

/** The order of a bind's clauses is the order the compiler learns in: the effect's own type from the evidence in
 * scope (`Known`), so that `f`'s argument has its type; then `f`, and with it or with what is expected the row;
 * then the effect's place in that row. */
extension [E[_], X](w: Word[E, X])
  def flatMap[R[_], B](using Known[E])(f: X => B ! R)(using at: In[E, R]): B ! R =
    Comp.Bind(Comp.Ask(at.place, w), f)
  def map[R[_], B](using Known[E])(f: X => B)(using at: In[E, R]): B ! R =
    Comp.Bind(Comp.Ask(at.place, w), x => Comp.Done(f(x)))
  /** this word as a program of the row `R` */
  def into[R[_]](using at: In[E, R]): X ! R = Comp.Ask(at.place, w)

/** a word or a program in every branch of an `if` or a `match` that is bound (Branches.scala) */
extension [V](inline p: Unplaced[V] | Comp[?, V])
  inline def flatMap[R[_], B](f: V => B ! R): B ! R = ${ Branches.flatMap[R, V, B]('p, 'f) }
  inline def map[R[_], B](f: V => B): B ! R = ${ Branches.map[R, V, B]('p, 'f) }

/** an operation of `E`, placed: `perform[State % Int](State.Get())` — the effect said, the answer's type from the
 * operation, the row from the evidence in scope or from what is expected. (Scala takes all of a method's type
 * arguments or none, so the rest are `Performing`'s; it is nothing at run time.) */
def perform[E[_]]: Performing[E] = ()

opaque type Performing[E[_]] = Unit

extension [E[_]](performing: Performing[E])
  def apply[R[_], X](op: E[X])(using at: In[E, R]): X ! R = Comp.Ask(at.place, op)

/** a computation built when it is reached: a call in tail position takes no stack */
def defer[A, R[_]](p: => A ! R): A ! R = Comp.Bind(unit[R], _ => p)

/** `p`, AND `finalizer` WHEN IT IS LEFT. With a value: the finalizer runs, and the value is `p`'s. Without one —
 * a handler outside does not go on with the rest of the program `p` is part of, and unwinds it (a failure, the
 * first of the choices, a stream cut short): the finalizer runs then, where it was installed — the handlers
 * between it and the one that leaves still answer it, their states as the path left them. Finalizers run the
 * innermost first, each once for every path that ends or is unwound past it. The finalizer is a program of the
 * same row, made when it is run; its value is dropped. One that fails is left as any program is: what waits
 * beyond it is still unwound, and the failure goes to its handler. Not run: when the rest of the program is kept
 * and never resumed (an `Emit.pull` not read to its end), and when the host throws. */
def ensuring[A, R[_]](p: A ! R)(finalizer: => Any ! R): A ! R = Comp.Ensure(p, () => finalizer)

/** `acquire`d, used, `release`d when the use is left: `ensuring` of the use */
def bracket[S, A, R[_]](acquire: S ! R)(use: S => A ! R)(release: S => Any ! R): A ! R =
  Comp.Bind(acquire, s => Comp.Ensure(use(s), () => release(s)))

/** `E` handled by `h` from wherever it is in `p`'s row: the program over the rest */
def handle[E[_], R[_], Rest[_], A, B](p: A ! R, h: Handler[E, Rest, A, B])(using at: Split[E, R, Rest]): B ! Rest =
  Comp.Handle(Machine.Takes(at.place), h, p)

/** `handle`, the effect kept in the row: each operation at `E`'s place is given to `h` first, and a clause may
 * ask it again — it goes to the handler of that place further out. Interception, audit, delegation. */
def intercept[E[_], R[_], A, B](p: A ! R, h: Handler[E, R, A, B])(using at: In[E, R]): B ! R =
  Comp.Handle(Machine.Keeps(at.place), h, p)

/** a computation run to its next question that no handler in it answers */
def step[R[_], A](p: A ! R): Step[R, A] = Machine.run(p)

/** a program with nothing left to ask, run to its value */
def run[A](p: A ! Pure): A = step(p) match
  case Step.Done(a) => a
  case Step.Asked(_, op, _) => throw NoHandler(op)
