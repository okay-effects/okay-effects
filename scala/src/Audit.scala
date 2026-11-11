package okay.effects.scala

/*
 * CONTROL AND AUDIT. An interceptor stands between a program and the handler of one of its effects: each operation
 * comes to it first. `observe` is shown each one and passes it on; `around` is given each one and its way on, and
 * gives the answer — passed on, changed, or refused. Both are `intercept`. An effect its author declares SENSITIVE is
 * handled no other way than through an audit: `handle` and `intercept` refuse it; `audit` puts an interceptor on it
 * and leaves `Audited[S]` in the row; `handleAudited` takes only that. So every operation of a sensitive effect that
 * is answered has passed its audit, and the compiler checks it.
 */

/** THE EFFECT `E` IS SENSITIVE: said by its author, a given in the effect's companion —
 * `object Pay: given Sensitive[Pay] = Sensitive()`. Found there wherever the effect is named. */
final class Sensitive[E[_]] private ()

object Sensitive:
  def apply[E[_]](): Sensitive[E] = new Sensitive[E]

/** THE EFFECT `E` IS PLAIN: no `Sensitive[E]` is found. What `handle`, `intercept` and `Step.Asked.of` ask. It is
 * found for an effect that is known (Messages.scala); for an effect that is a type parameter it is not: a function
 * generic in its effect asks it of its caller, `(using Plain[E])`, and the caller's effect is checked. Nothing at
 * run time. */
opaque type Plain[E[_]] = Unit

object Plain:
  inline given plain[E[_]]: Plain[E] = ${ Messages.plain[E] }
  /** what the check gives when it passes */
  private[scala] def checked[E[_]]: Plain[E] = ()

/** `E` AUDITED: what an audit of the sensitive effect `E` leaves in the row outside it. Outside this object it is
 * not `E`: no program asks an operation of `E` there, and a handler written for `Audited[E]` is given operations it
 * cannot read. In here it is `E`: the same operations and places at run time, and nothing is cast. */
type Audited[E[_]] = [X] =>> Audited.Op[E, X]

object Audited:
  opaque type Op[E[_], X] = E[X]

  /** the place of `E` audited is a place of `E`: for the audit's way on, and for the handler */
  private[scala] def place[E[_], R[_], Rest[_]](at: Place[Audited[E], R, Rest]): Place[E, R, Rest] = at

/** THE WAY ON past an audit of `S`, in the row `T` outside it: `pass(op)` asks `op` there, of the handler that
 * `handleAudited` put. Only `audit` makes one, for its interceptor. */
final class Pass[S[_], T[_]] private[scala] (at: At[S, T]):
  def apply[X](op: S[X]): X ! T = Comp.Ask(at, op)

/** AN INTERCEPTOR of the sensitive effect `S`, the row outside its audit `T`: each operation, the rest `k` and the
 * way on. Its clause may tell (record), pass the operation on as it is or changed (`pass`), change the answer, or
 * refuse — fail by an effect of `T`, or end the program with a `B` of its own (`k.unwind` first). It goes on by
 * `k.after(c)`: if the handler that answers `c` leaves, the rest is left with it and its finalizers run. */
abstract class Interceptor[S[_], T[_], A, B]:
  def ret(a: A): B ! T
  def apply[X](op: S[X], k: Continuation[X, T, B], pass: Pass[S, T]): B ! T

/** THE SENSITIVE EFFECT `S` AUDITED by `i` for `body`, a program of `T + S`: each of its operations is given to the
 * clause, which runs outside the audit, in `T`. `T` has `Audited[S]`, where no program asks `S`. */
def audit[S[_], T[_], A, B](i: Interceptor[S, T, A, B])(body: A ! T + S)(using at: In[Audited[S], T])(using
    Sensitive[S]): B ! T =
  val pass = Pass[S, T](Audited.place(at.place))
  val on = [X] => (op: S[X], k: Continuation[X, T, B]) => i[X](op, k, pass)
  Comp.Handle(Machine.Takes(Place.Last[T, S]()), Handler.General(i.ret, on), body)

/** THE SENSITIVE EFFECT `S` HANDLED by `h`, under its audit only: `Audited[S]` taken out of the row */
def handleAudited[S[_], R[_], Rest[_], A, B](p: A ! R, h: Handler[S, Rest, A, B])(using
    at: Split[Audited[S], R, Rest])(using Sensitive[S]): B ! Rest =
  Comp.Handle(Machine.Takes(Audited.place(at.place)), h, p)

/** each operation of `E` in `p` shown to `see` — a program of the row, run before the operation goes on as it is:
 * a trace, a count, a trail. Nothing of the program changes. */
def observe[E[_], R[_], A](p: A ! R)(see: [X] => E[X] => Unit ! R)(using at: In[E, R])(using Plain[E]): A ! R =
  intercept(p, Handler.control[E, R, A, A](pure)([X] => (op: E[X], k: Continuation[X, R, A]) =>
    Comp.Bind(see[X](op), _ => k.after(Comp.Ask(at.place, op)))))

/** around each operation of `E` in `p`: given it and `proceed`, its way on as it is, the program of its answer —
 * `proceed`, something before or after it, another operation, an answer of its own, or a failure */
def around[E[_], R[_], A](p: A ! R)(f: [X] => (E[X], X ! R) => X ! R)(using at: In[E, R])(using Plain[E]): A ! R =
  intercept(p, Handler.control[E, R, A, A](pure)([X] => (op: E[X], k: Continuation[X, R, A]) =>
    k.after(f[X](op, Comp.Ask(at.place, op)))))
