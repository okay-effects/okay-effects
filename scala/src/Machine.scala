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

  /** how a handler of `E` meets the operations of its row `R`: it took its effect out — outside is the row
   * without it — or it kept it there */
  sealed abstract class Way[E[_], R[_], Out[_]]:
    def at: At[E, R]
    /** the place outside the handler of the operation at `other`; null when it is the handler's own */
    def beyond[F[_], X](other: At[F, R], op: F[X]): At[F, Out]

  final class Takes[E[_], R[_], Rest[_]](val at: Place[E, R, Rest]) extends Way[E, R, Rest]:
    def beyond[F[_], X](other: At[F, R], op: F[X]): At[F, Rest] = at.outside(other, op)

  final class Keeps[E[_], R[_]](val at: At[E, R]) extends Way[E, R, R]:
    def beyond[F[_], X](other: At[F, R], op: F[X]): At[F, R] =
      if at.same[F, [G[_]] =>> At[G, R]](other, at) == null then other else null

  /** A LEVEL: the program inside it is of the row `R` and comes to a `V`; the run is of the row `P`, to a `Z` */
  sealed trait Level[R[_], V, P[_], Z]

  /** a level without what is under it — as a `k` keeps it: put back, it is a new level on another stack */
  sealed trait Part[R[_], V, Out[_], W]:
    def over[P[_], Z](below: Level[Out, W, P, Z]): Level[R, V, P, Z]

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

  /** under a handler in place: the level is the cell of its state */
  final class InPlace[E[_], R[_], Out[_], A, B, S, W, P[_], Z](val way: Way[E, R, Out],
      val handler: Handler.Stateful[E, Out, A, B, S], val outside: Frames[Out, B, W], val below: Level[Out, W, P, Z],
      from: S) extends Cell[S](from) with Level[R, A, P, Z] with Part[R, A, Out, W] with Answers[E]:
    def over[P2[_], Z2](under: Level[Out, W, P2, Z2]): Level[R, A, P2, Z2] =
      InPlace(way, handler, outside, under, state)
    def answer[X](op: E[X]): X = handler.answer(op, this)
    /** this, when the operation at `at` is its handler's; else null */
    def own[F[_]](at: At[F, R]): Answers[F] = way.at.same[F, Answers](at, this)

  /** a program of the row `R` in the row `S`: no handler, the places renamed */
  final class Renamed[R[_], S[_], A, W, P[_], Z](val sub: Sub[R, S], val outside: Frames[S, A, W],
      val below: Level[S, W, P, Z]) extends Level[R, A, P, Z] with Part[R, A, S, W]:
    def over[P2[_], Z2](under: Level[S, W, P2, Z2]): Level[R, A, P2, Z2] = Renamed(sub, outside, under)

  /** THE LEVELS A CONTINUATION TOOK, the outermost first: from the body at `R0` of a `V0`, out to the row `R`
   * and a `V` */
  sealed abstract class Taken[R0[_], V0, R[_], V]
  final class None[R[_], V]() extends Taken[R, V, R, V]
  final class More[R0[_], V0, R1[_], V1, R[_], V](val inner: Taken[R0, V0, R1, V1], val part: Part[R1, V1, R, V])
      extends Taken[R0, V0, R, V]

  /** the levels taken, put back on `onto` */
  @tailrec
  private def put[R0[_], V0, R[_], V, P[_], Z](taken: Taken[R0, V0, R, V], onto: Level[R, V, P, Z])
      : Level[R0, V0, P, Z] = taken match
    case _: None[r, ?] => onto: Level[r, V, P, Z]
    case more: More[r0, ?, r1, v1, r, ?] =>
      put[r0, V0, r1, v1, P, Z](more.inner, more.part.over[P, Z](onto: Level[r, V, P, Z]))

  /** A CONTINUATION: the frames at the operation and the levels it passed. Resumed in a row `Out` for a `B`, it
   * puts them back over what is there. */
  sealed abstract class K[R0[_], V0, X, Out[_], B] extends (X => Comp[Out, B]):
    def inner: Frames[R0, X, V0]
    def over[W, P[_], Z](outside: Frames[Out, B, W], below: Level[Out, W, P, Z]): Level[R0, V0, P, Z]
    def apply(x: X): Comp[Out, B] = Comp.Resume(this, x)

  /** the rest of the program under a general handler: the handler is put back too */
  final class Under[R0[_], V0, X, E[_], R[_], Out[_], A, B](val inner: Frames[R0, X, V0],
      taken: Taken[R0, V0, R, A], way: Way[E, R, Out], handler: Handler.General[E, Out, A, B])
      extends K[R0, V0, X, Out, B]:
    def over[W, P[_], Z](outside: Frames[Out, B, W], below: Level[Out, W, P, Z]): Level[R0, V0, P, Z] =
      put(taken, General(way, handler, outside, below))

  /** the rest of the whole run, after an operation no handler answered */
  final class Whole[R0[_], V0, X, P0[_], Z0](val inner: Frames[R0, X, V0], taken: Taken[R0, V0, P0, Z0])
      extends K[R0, V0, X, P0, Z0]:
    def over[W, P[_], Z](outside: Frames[P0, Z0, W], below: Level[P0, W, P, Z]): Level[R0, V0, P, Z] =
      put(taken, Renamed(Sub.Same(), outside, below))

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
    case ask: Comp.Ask[r, e, x] =>
      val handler = inPlace[e, x, r, V, P, Z](ask.at, ask.op, level)
      if handler != null then
        val answer = handler.answer[x](ask.op)
        // the answer goes straight to the frame that waits for it: no `Done` is made
        frames match
          case more: Then[r2, ?, y, ?] => loop[r2, y, V, P, Z](more.next(answer), more.rest, level)
          case _: End[?, ?] => loop[R, X, V, P, Z](Comp.Done[R, X](answer), frames, level)
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
        case _: End[?, ?] => loop[r0, x, v0, P, Z](Comp.Done[r0, x](resume.x), k.inner, levels)

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
    case in: InPlace[e, r, o, a, ?, ?, w, p, z] =>
      val further = passing[e, r, o, F, X](in.way, at, op)
      out[F, X, R0, V0, o, w, p, z](further, op, inner, More[R0, V0, r, a, o, w](taken, in), in.below)
    case renamed: Renamed[r, s, a, w, p, z] =>
      val further = renamed.sub[F, X](at, op)
      out[F, X, R0, V0, s, w, p, z](further, op, inner, More[R0, V0, r, a, s, w](taken, renamed), renamed.below)
    case _: Top[p, z] => Stop[p, z](Step.Asked[p, F, X, z](at, op, Whole[R0, V0, X, p, z](inner, taken)))

  /** past a handler in place that did not answer */
  private def passing[E[_], R[_], Out[_], F[_], X](way: Way[E, R, Out], at: At[F, R], op: F[X]): At[F, Out] =
    val further = way.beyond[F, X](at, op)
    if further == null then throw NoHandler(op) else further

  /** the general handler's clause for its own operation: the two places show that it is of the handler's effect */
  private def clause[E[_], R[_], Out[_], A, B, F[_], X](way: Way[E, R, Out], handler: Handler.General[E, Out, A, B],
      at: At[F, R], op: F[X], k: X => Comp[Out, B]): Comp[Out, B] =
    val own = way.at.same[F, [G[_]] =>> Handler.General[G, Out, A, B]](at, handler)
    if own == null then throw NoHandler(op) else own.on[X](op, k)
