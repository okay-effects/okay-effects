package okay.effects.scala

import scala.annotation.tailrec

/** THE MACHINE: one loop. What waits for the value is the FRAMES of the binds, up to the nearest handler, and a
 * stack of LEVELS, one for each handler the program is under: the handler, its state, and the frames outside it.
 *
 *   Bind       a frame is pushed
 *   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
 *   Handle     a new level
 *   Embedded   a new level, of no handler: it renames
 *   Ask        the handler of the operation's place is found. In place: it answers, the loop goes on with the
 *              answer — nothing is allocated. General: the levels above and at the handler are taken off as `k`,
 *              and the clause runs below them
 *   Resume     the levels of a `k` are put back on top
 *   Ensure     a frame is pushed that holds the finalizer. A value that comes to it waits while the finalizer
 *              runs
 *   Unwind     the levels of a `k` are put back on top, without its handler, and UNWOUND: its frames are passed
 *              with no value, a finalizer's frame runs its finalizer, a level is left without its `ret`
 *
 * HOW A HANDLER IS FOUND: by the operation's place in the row, which the compiler found (Rows.scala) — no class
 * is looked at. Walking down the levels, the place is this level's handler's, or the search goes on with the
 * operation's place in the row outside: past a handler put by `handle`, the row without its effect; past an
 * interceptor, the same row. A program put into another row (`into`) runs under a level with no handler, which
 * RENAMES: an operation that passes it has, outside, its place in that row.
 *
 * EVERYTHING IS TYPED, and nothing is cast: a level says the row inside it and the value its body comes to, the
 * frames the value they take and the one they come to, and one fits the other. The types a level hides — its
 * handler's effect, the row outside — are found again by matching on it; that an operation is its handler's is
 * shown by the two places (`same`, Rows.scala).
 *
 * Frames and the levels in a `k` are never changed: a `k` is shared, never copied, and may be resumed any number
 * of times. A level on the stack belongs to the one path that runs: a handler in place writes its state there. */
private[scala] object Machine:
  /** what follows a value inside one level: from an `X` to the level's value, a `V`, in the row `R` */
  sealed abstract class Frames[R[_], -X, V]
  final class Then[R[_], X, Y, V](val next: X => Comp[R, Y], val rest: Frames[R, Y, V]) extends Frames[R, X, V]
  final class End[R[_], V]() extends Frames[R, V, V]
  /** `ensuring`: the finalizer runs when a value passes, and when the frames are unwound */
  final class Finally[R[_], X, V](val finalizer: () => Comp[R, Any], val rest: Frames[R, X, V])
      extends Frames[R, X, V]
  /** an unwinding goes on from here, to its end `to`: what comes here — a finalizer's value, the value of a level
   * that is being unwound — is dropped, and `rest` is unwound */
  final class Unwinding[R[_], Y, V](val rest: Frames[R, Y, V], val to: AnyRef) extends Frames[R, Any, V]
  /** the end of an unwinding: `()` to what waits for it */
  final class Unwound[R[_], V](val rest: Frames[R, Unit, V]) extends Frames[R, Any, V]

  /** how a handler of `E` meets the operations of its row `R`: it took its effect out — outside is the row
   * without it — or it kept it there */
  sealed abstract class Way[E[_], R[_], Out[_]]:
    def at: At[E, R]
    /** the place outside the handler of the operation at `other`; null when it is the handler's own */
    def beyond[F[_], X](other: At[F, R], op: F[X]): At[F, Out]
    /** the same, the handler gone (`Gone`): the effect it kept in the row is the handler's outside */
    def gone[F[_], X](other: At[F, R], op: F[X]): At[F, Out]

  final class Takes[E[_], R[_], Rest[_]](val at: Place[E, R, Rest]) extends Way[E, R, Rest]:
    def beyond[F[_], X](other: At[F, R], op: F[X]): At[F, Rest] = at.outside(other, op)
    def gone[F[_], X](other: At[F, R], op: F[X]): At[F, Rest] = at.outside(other, op)

  final class Keeps[E[_], R[_]](val at: At[E, R]) extends Way[E, R, R]:
    def beyond[F[_], X](other: At[F, R], op: F[X]): At[F, R] =
      if at.same[F, [G[_]] =>> At[G, R]](other, at) == null then other else null
    def gone[F[_], X](other: At[F, R], op: F[X]): At[F, R] = other

  /** A LEVEL: the program inside it is of the row `R` and comes to a `V`; the run is of the row `P`, to a `Z` */
  sealed trait Level[R[_], V, P[_], Z]

  /** a level without what is under it — as a `k` keeps it: put back, it is a new level on another stack */
  sealed trait Part[R[_], V, Out[_], W]:
    def over[P[_], Z](below: Level[Out, W, P, Z]): Level[R, V, P, Z]
    /** put back to be unwound, to `to`: a value that comes out of the level goes on unwinding */
    def left[P[_], Z](below: Level[Out, W, P, Z], to: AnyRef): Level[R, V, P, Z]

  /** a level that answers no operation taken out to a general handler: one that is not its own goes past it, to
   * its place outside; null for its own (`stopped`) */
  sealed trait Past[R[_], V, Out[_], W, P[_], Z] extends Level[R, V, P, Z] with Part[R, V, Out, W]:
    def below: Level[Out, W, P, Z]
    def past[F[_], X](at: At[F, R], op: F[X]): At[F, Out]

  /** a handler in place of `F`: it answers at once */
  sealed trait Answers[F[_]]:
    def answer[X](op: F[X]): X

  /** the bottom: the run's own row and value */
  final class Top[P[_], Z]() extends Level[P, Z, P, Z]

  /** under a general handler: outside it, the frames from its value and the levels */
  final class General[E[_], R[_], Out[_], A, B, W, P[_], Z](val way: Way[E, R, Out],
      val handler: Handler.General[E, Out, A, B], val outside: Frames[Out, B, W], val below: Level[Out, W, P, Z])
      extends Level[R, A, P, Z] with Part[R, A, Out, W]:
    def over[P2[_], Z2](under: Level[Out, W, P2, Z2]): Level[R, A, P2, Z2] = General(way, handler, outside, under)
    def left[P2[_], Z2](under: Level[Out, W, P2, Z2], to: AnyRef): Level[R, A, P2, Z2] =
      General(way, handler, Unwinding(outside, to), under)

  /** under a handler in place: the level is the cell of its state */
  final class InPlace[E[_], R[_], Out[_], A, B, S, W, P[_], Z](val way: Way[E, R, Out],
      val handler: Handler.Stateful[E, Out, A, B, S], val outside: Frames[Out, B, W], val below: Level[Out, W, P, Z],
      from: S) extends Cell[S](from) with Past[R, A, Out, W, P, Z] with Answers[E]:
    def over[P2[_], Z2](under: Level[Out, W, P2, Z2]): Level[R, A, P2, Z2] =
      InPlace(way, handler, outside, under, state)
    def left[P2[_], Z2](under: Level[Out, W, P2, Z2], to: AnyRef): Level[R, A, P2, Z2] =
      InPlace(way, handler, Unwinding(outside, to), under, state)
    def past[F[_], X](at: At[F, R], op: F[X]): At[F, Out] = way.beyond(at, op)
    def answer[X](op: E[X]): X = handler.answer(op, this)
    /** this, when the operation at `at` is its handler's; else null */
    def own[F[_]](at: At[F, R]): Answers[F] = way.at.same[F, Answers](at, this)

  /** a program of the row `R` in the row `S`: no handler, the places renamed */
  final class Renamed[R[_], S[_], A, W, P[_], Z](val sub: Sub[R, S], val outside: Frames[S, A, W],
      val below: Level[S, W, P, Z]) extends Level[R, A, P, Z] with Part[R, A, S, W]:
    def over[P2[_], Z2](under: Level[S, W, P2, Z2]): Level[R, A, P2, Z2] = renaming(sub, outside, under)
    def left[P2[_], Z2](under: Level[S, W, P2, Z2], to: AnyRef): Level[R, A, P2, Z2] =
      Renamed(sub, Unwinding(outside, to), under)

  /** A LEVEL THAT RENAMES, PUT ON `below` — a level a continuation took, or the one that stands for the rest of a
   * whole run (`Whole`). When nothing waits between it and a level under it that renames nothing — the rest of a
   * run resumed where a run ends — the two are one level, and that one is not kept: so a program stepped and
   * resumed in a loop, however long, is under one such level, and not under one for every turn. */
  private def renaming[R[_], S[_], A, W, P[_], Z](sub: Sub[R, S], outside: Frames[S, A, W],
      below: Level[S, W, P, Z]): Level[R, A, P, Z] = outside match
    case _: End[?, ?] => below match
      case lower: Renamed[s, t, ?, w, p, ?] => onto[R, s, t, A, w, p, Z](sub, lower.sub, lower.outside, lower.below)
      case _ => Renamed(sub, outside, below)
    case _ => Renamed(sub, outside, below)

  /** the level of `sub` over that of `lower`, nothing between them: the one that renames, when the other does not */
  private def onto[R[_], S[_], T[_], A, W, P[_], Z](sub: Sub[R, S], lower: Sub[S, T], outside: Frames[T, A, W],
      below: Level[T, W, P, Z]): Level[R, A, P, Z] = lower match
    case Sub.Same() => Renamed[R, S, A, W, P, Z](sub, outside, below)
    case _ => sub match
      case Sub.Same() => Renamed[R, T, A, W, P, Z](lower, outside, below)
      case _ => Renamed[R, S, A, A, P, Z](sub, End[S, A](), Renamed[S, T, A, W, P, Z](lower, outside, below))

  /** WHERE A GENERAL HANDLER WAS, while its `k` is unwound: the handler has left, and answers nothing. An
   * operation of its place that a finalizer asks goes outside if the handler kept its effect in the row
   * (`intercept`); if it took the effect out (`handle`), nobody is left to answer, and the finalizer ends there
   * (`stopped`). Outside the level is the end of the unwinding. */
  final class Gone[E[_], R[_], Out[_], A, W, P[_], Z](val way: Way[E, R, Out], val outside: Frames[Out, Any, W],
      val below: Level[Out, W, P, Z]) extends Past[R, A, Out, W, P, Z]:
    def past[F[_], X](at: At[F, R], op: F[X]): At[F, Out] = way.gone(at, op)
    def over[P2[_], Z2](under: Level[Out, W, P2, Z2]): Level[R, A, P2, Z2] = Gone(way, outside, under)
    def left[P2[_], Z2](under: Level[Out, W, P2, Z2], to: AnyRef): Level[R, A, P2, Z2] =
      Gone(way, Unwinding(outside, to), under)
    /** the unwinding that goes on here: the one this level is put back in, or else its own */
    def to: AnyRef = outside match
      case on: Unwinding[?, ?, ?] => on.to
      case own => own

  /** THE LEVELS A CONTINUATION TOOK, the outermost first: from the body at `R0` of a `V0`, out to the row `R`
   * and a `V` */
  sealed abstract class Taken[R0[_], V0, R[_], V]
  final class None[R[_], V]() extends Taken[R, V, R, V]
  final class More[R0[_], V0, R1[_], V1, R[_], V](val inner: Taken[R0, V0, R1, V1], val part: Part[R1, V1, R, V])
      extends Taken[R0, V0, R, V]

  /** how many levels were taken, and `n` */
  @tailrec
  private def count[R0[_], V0, R[_], V](taken: Taken[R0, V0, R, V], n: Int): Int = taken match
    case _: None[?, ?] => n
    case more: More[r0, ?, r1, v1, ?, ?] => count[r0, V0, r1, v1](more.inner, n + 1)

  /** the levels taken, put back on `onto` */
  @tailrec
  private def put[R0[_], V0, R[_], V, P[_], Z](taken: Taken[R0, V0, R, V], onto: Level[R, V, P, Z])
      : Level[R0, V0, P, Z] = taken match
    case _: None[r, ?] => onto: Level[r, V, P, Z]
    case more: More[r0, ?, r1, v1, r, ?] =>
      put[r0, V0, r1, v1, P, Z](more.inner, more.part.over[P, Z](onto: Level[r, V, P, Z]))

  /** the levels taken, put back on `onto` to be unwound */
  @tailrec
  private def putLeft[R0[_], V0, R[_], V, P[_], Z](taken: Taken[R0, V0, R, V], onto: Level[R, V, P, Z], to: AnyRef)
      : Level[R0, V0, P, Z] = taken match
    case _: None[r, ?] => onto: Level[r, V, P, Z]
    case more: More[r0, ?, r1, v1, r, ?] =>
      putLeft[r0, V0, r1, v1, P, Z](more.inner, more.part.left[P, Z](onto: Level[r, V, P, Z], to), to)

  /** A CONTINUATION: the frames at the operation and the levels it passed. Resumed in a row `Out` for a `B`, it
   * puts them back over what is there. Unwound, it puts them back without its handler, over the unwinding's
   * `end`. */
  sealed abstract class K[R0[_], V0, X, Out[_], B] extends Continuation[X, Out, B]:
    def inner: Frames[R0, X, V0]
    def over[W, P[_], Z](outside: Frames[Out, B, W], below: Level[Out, W, P, Z]): Level[R0, V0, P, Z]
    def left[W, P[_], Z](end: Unwound[Out, W], below: Level[Out, W, P, Z]): Level[R0, V0, P, Z]
    def apply(x: X): Comp[Out, B] = Comp.Resume(this, x)
    def unwind: Comp[Out, Unit] = Comp.Unwind(this)

  /** the rest of the program under a general handler: the handler is put back too */
  final class Under[R0[_], V0, X, E[_], R[_], Out[_], A, B](val inner: Frames[R0, X, V0],
      taken: Taken[R0, V0, R, A], way: Way[E, R, Out], handler: Handler.General[E, Out, A, B])
      extends K[R0, V0, X, Out, B]:
    def levels: Int = count(taken, 1)
    def over[W, P[_], Z](outside: Frames[Out, B, W], below: Level[Out, W, P, Z]): Level[R0, V0, P, Z] =
      put(taken, General(way, handler, outside, below))
    def left[W, P[_], Z](end: Unwound[Out, W], below: Level[Out, W, P, Z]): Level[R0, V0, P, Z] =
      putLeft(taken, Gone[E, R, Out, A, W, P, Z](way, end, below), end)

  /** the rest of the whole run, after an operation no handler answered */
  final class Whole[R0[_], V0, X, P0[_], Z0](val inner: Frames[R0, X, V0], taken: Taken[R0, V0, P0, Z0])
      extends K[R0, V0, X, P0, Z0]:
    def levels: Int = count(taken, 0)
    def over[W, P[_], Z](outside: Frames[P0, Z0, W], below: Level[P0, W, P, Z]): Level[R0, V0, P, Z] =
      put(taken, renaming(Sub.Same[P0](), outside, below))
    def left[W, P[_], Z](end: Unwound[P0, W], below: Level[P0, W, P, Z]): Level[R0, V0, P, Z] =
      putLeft(taken, Renamed[P0, P0, Z0, W, P, Z](Sub.Same(), end, below), end)

  /** where the run goes on after an operation went out: on, or no further */
  sealed abstract class Next[P[_], Z]
  final class Go[R[_], X, V, P[_], Z](val c: Comp[R, X], val frames: Frames[R, X, V], val level: Level[R, V, P, Z])
      extends Next[P, Z]
  final class Stop[P[_], Z](val at: Step[P, Z]) extends Next[P, Z]

  def run[R[_], A](p: Comp[R, A]): Step[R, A] = loop[R, A, A, R, A](p, End[R, A](), Top[R, A]())

  /** One turn takes the computation `c` apart. Every type is said: left to itself, the compiler would settle a type
   * that a match has only bounded on `Nothing`. */
  @tailrec
  private def loop[R[_], X, V, P[_], Z](c: Comp[R, X], frames: Frames[R, X, V], level: Level[R, V, P, Z])
      : Step[P, Z] = c match
    case bind: Comp.Bind[r, y, ?] => loop[r, y, V, P, Z](bind.first, Then[r, y, X, V](bind.next, frames), level)
    case done: Comp.Done[?, ?] => frames match
      case more: Then[r, ?, y, ?] => loop[r, y, V, P, Z](more.next(done.value), more.rest, level)
      case _: End[?, ?] => level match
        case general: General[?, ?, out, ?, b, w, p, ?] =>
          loop[out, b, w, p, Z](general.handler.ret(done.value), general.outside, general.below)
        case in: InPlace[?, ?, out, ?, b, ?, w, p, ?] =>
          loop[out, b, w, p, Z](in.handler.ret(in.state, done.value), in.outside, in.below)
        case renamed: Renamed[?, s, a, w, p, ?] =>
          loop[s, a, w, p, Z](Comp.Done[s, a](done.value), renamed.outside, renamed.below)
        case _: Top[p, z] => Step.Done[p, z](done.value)
        case gone: Gone[?, ?, out, ?, w, p, ?] =>
          loop[out, Any, w, p, Z](Comp.Done[out, Any](done.value), gone.outside, gone.below)
      case _ => leaving[R, X, V, P, Z](c, frames, level) match
        case go: Go[r2, x2, v2, p, z] => loop[r2, x2, v2, p, z](go.c, go.frames, go.level)
        case stop: Stop[p, z] => stop.at: Step[p, z]
    case ask: Comp.Ask[r, e, x] =>
      val handler = inPlace[e, x, r, V, P, Z](ask.at, ask.op, level)
      if handler != null then
        val answer = handler.answer[x](ask.op)
        // the answer goes straight to the frame that waits for it: no `Done` is made
        frames match
          case more: Then[r2, ?, y, ?] => loop[r2, y, V, P, Z](more.next(answer), more.rest, level)
          case _ => loop[R, X, V, P, Z](Comp.Done[R, X](answer), frames, level)
      else out[e, x, r, V, r, V, P, Z](ask.at, ask.op, frames, None[r, V](), level) match
        case go: Go[r2, x2, v2, p, z] => loop[r2, x2, v2, p, z](go.c, go.frames, go.level)
        case stop: Stop[p, z] => stop.at: Step[p, z]
    case handle: Comp.Handle[e, r, out, a, b] => handle.handler match
      case general: Handler.General[`e`, `out`, `a`, `b`] =>
        val under = General[e, r, out, a, b, V, P, Z](handle.way, general, frames, level)
        loop[r, a, a, P, Z](handle.body, End[r, a](), under)
      case inPlace: Handler.Stateful[`e`, `out`, `a`, `b`, s] =>
        val under = InPlace[e, r, out, a, b, s, V, P, Z](handle.way, inPlace, frames, level, inPlace.init)
        loop[r, a, a, P, Z](handle.body, End[r, a](), under)
    case embedded: Comp.Embedded[r, s, a] =>
      loop[r, a, a, P, Z](embedded.body, End[r, a](), Renamed[r, s, a, V, P, Z](embedded.sub, frames, level))
    case resume: Comp.Resume[r0, v0, x, r, a] =>
      val k: K[r0, v0, x, r, a] = resume.k
      val levels = k.over[V, P, Z](frames, level)
      k.inner match
        case more: Then[r2, ?, y, ?] => loop[r2, y, v0, P, Z](more.next(resume.x), more.rest, levels)
        case _ => loop[r0, x, v0, P, Z](Comp.Done[r0, x](resume.x), k.inner, levels)
    case _ => leaving[R, X, V, P, Z](c, frames, level) match
      case go: Go[r2, x2, v2, p, z] => loop[r2, x2, v2, p, z](go.c, go.frames, go.level)
      case stop: Stop[p, z] => stop.at: Step[p, z]

  /** THE TURNS OF LEAVING, apart from the loop, which they do not slow.
   *
   *   Ensure     a frame that holds the finalizer
   *   Unwind     the levels of `k` are put back over the end of the unwinding — what waited for it — and unwound
   *   Done       at a finalizer's frame: the finalizer runs, and the value waits for it. At a frame of an
   *              unwinding: the value is dropped, and the unwinding goes on, or ends */
  private def leaving[R[_], X, V, P[_], Z](c: Comp[R, X], frames: Frames[R, X, V], level: Level[R, V, P, Z])
      : Next[P, Z] = c match
    case ensure: Comp.Ensure[r, x] => Go[r, x, V, P, Z](ensure.body, Finally[r, x, V](ensure.finalizer, frames), level)
    case unwind: Comp.Unwind[r0, v0, x, r, b] =>
      val k: K[r0, v0, x, r, b] = unwind.k
      val end = Unwound[r, V](frames)
      walk[r0, x, v0, P, Z](k.inner, k.left[V, P, Z](end, level), end)
    case _ => frames match
      case last: Finally[r, ?, ?] =>
        Go[r, Any, V, P, Z](last.finalizer(), Then[r, Any, X, V](_ => c, last.rest), level)
      case on: Unwinding[r, y, ?] => walk[r, y, V, P, Z](on.rest, level, on.to)
      case end: Unwound[r, ?] => Go[r, Unit, V, P, Z](Comp.Done[r, Unit](()), end.rest, level)
      case _ => Go[R, X, V, P, Z](c, frames, level)

  /** UNWINDING, to its end `to`: the frames are passed with no value. A finalizer's frame: the finalizer runs, and
   * the unwinding goes on after it (`Unwinding`). The end of a level's frames: the level is left without its
   * `ret`, and the frames outside it are unwound. The end of another unwinding, begun inside this one, is
   * passed. */
  @tailrec
  private def walk[R[_], X, V, P[_], Z](frames: Frames[R, X, V], level: Level[R, V, P, Z], to: AnyRef): Next[P, Z] =
    frames match
      case more: Then[r, ?, y, ?] => walk[r, y, V, P, Z](more.rest, level, to)
      case last: Finally[r, x, ?] =>
        Go[r, Any, V, P, Z](last.finalizer(), Unwinding[r, x, V](last.rest, to), level)
      case on: Unwinding[r, y, ?] => walk[r, y, V, P, Z](on.rest, level, to)
      case end: Unwound[r, ?] =>
        if end eq to then Go[r, Unit, V, P, Z](Comp.Done[r, Unit](()), end.rest, level)
        else walk[r, Unit, V, P, Z](end.rest, level, to)
      case _: End[?, ?] => level match
        case general: General[?, ?, out, ?, b, w, p, ?] => walk[out, b, w, p, Z](general.outside, general.below, to)
        case in: InPlace[?, ?, out, ?, b, ?, w, p, ?] => walk[out, b, w, p, Z](in.outside, in.below, to)
        case renamed: Renamed[?, s, a, w, p, ?] => walk[s, a, w, p, Z](renamed.outside, renamed.below, to)
        case _: Top[?, ?] => throw NoUnwinding()
        case gone: Gone[?, ?, out, ?, w, p, ?] => walk[out, Any, w, p, Z](gone.outside, gone.below, to)

  /** the handler in place that answers the operation at `at`, looked for from `level` out. Null when a general
   * handler answers it, or none does. */
  @tailrec
  private def inPlace[F[_], X, R[_], V, P[_], Z](at: At[F, R], op: F[X], level: Level[R, V, P, Z]): Answers[F] =
    level match
      case in: InPlace[?, r, out, ?, ?, ?, w, p, ?] =>
        val further = in.way.beyond[F, X](at: At[F, r], op)
        if further == null then in.own[F](at) else inPlace[F, X, out, w, p, Z](further, op, in.below)
      case general: General[?, r, out, ?, ?, w, p, ?] =>
        val further = general.way.beyond[F, X](at: At[F, r], op)
        if further == null then null else inPlace[F, X, out, w, p, Z](further, op, general.below)
      case renamed: Renamed[r, s, ?, w, p, ?] =>
        inPlace[F, X, s, w, p, Z](renamed.sub[F, X](at: At[F, r], op), op, renamed.below)
      case gone: Gone[?, r, out, ?, w, p, ?] =>
        val further = gone.way.gone[F, X](at: At[F, r], op)
        if further == null then null else inPlace[F, X, out, w, p, Z](further, op, gone.below)
      case _: Top[?, ?] => null

  /** an operation on its way out to a general handler: the levels it passes are taken into `k`, and the clause
   * runs outside the handler. With no handler, the run stops at the operation. */
  @tailrec
  private def out[F[_], X, R0[_], V0, R[_], V, P[_], Z](at: At[F, R], op: F[X], inner: Frames[R0, X, V0],
      taken: Taken[R0, V0, R, V], level: Level[R, V, P, Z]): Next[P, Z] = level match
    case general: General[e, r, o, a, b, w, p, z] =>
      val further = general.way.beyond[F, X](at, op)
      if further != null then
        out[F, X, R0, V0, o, w, p, z](further, op, inner, More[R0, V0, r, a, o, w](taken, general), general.below)
      else
        val k = Under[R0, V0, X, e, r, o, a, b](inner, taken, general.way, general.handler)
        Go[o, b, w, p, z](clause[e, r, o, a, b, F, X](general.way, general.handler, at, op, k), general.outside,
          general.below)
    case past: Past[r, a, o, w, p, z] =>
      val further = past.past[F, X](at, op)
      if further != null then
        out[F, X, R0, V0, o, w, p, z](further, op, inner, More[R0, V0, r, a, o, w](taken, past), past.below)
      else stopped[F, X, R0, V0, r, a, o, w, p, z](past, op, inner, taken)
    case renamed: Renamed[r, s, a, w, p, z] =>
      val further = renamed.sub[F, X](at, op)
      out[F, X, R0, V0, s, w, p, z](further, op, inner, More[R0, V0, r, a, s, w](taken, renamed), renamed.below)
    case _: Top[p, z] => Stop[p, z](Step.Asked[p, F, X, z](at, op, Whole[R0, V0, X, p, z](inner, taken)))

  /** an operation taken out, come to a level whose own it is, and which answers none. A handler in place: it did
   * not answer. A handler that has left: the finalizer that asks ends here — what it had taken is put back, and
   * the unwinding goes on from the operation. */
  private def stopped[F[_], X, R0[_], V0, R[_], V, Out[_], W, P[_], Z](level: Past[R, V, Out, W, P, Z], op: F[X],
      inner: Frames[R0, X, V0], taken: Taken[R0, V0, R, V]): Next[P, Z] = level match
    case gone: Gone[?, ?, ?, ?, ?, ?, ?] => walk[R0, X, V0, P, Z](inner, put[R0, V0, R, V, P, Z](taken, gone), gone.to)
    case _: InPlace[?, ?, ?, ?, ?, ?, ?, ?, ?] => throw NoHandler(op)

  /** the general handler's clause for its own operation: the two places show that it is of the handler's effect */
  private def clause[E[_], R[_], Out[_], A, B, F[_], X](way: Way[E, R, Out], handler: Handler.General[E, Out, A, B],
      at: At[F, R], op: F[X], k: Continuation[X, Out, B]): Comp[Out, B] =
    val own = way.at.same[F, [G[_]] =>> Handler.General[G, Out, A, B]](at, handler)
    if own == null then throw NoHandler(op) else own.on[X](op, k)
