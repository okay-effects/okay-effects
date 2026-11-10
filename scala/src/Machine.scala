package okay.effects.scala

import scala.annotation.tailrec

/** THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
 * the frames inside the handler, the handler, its state. The top level is kept unpacked in the loop's arguments.
 *
 *   Bind       a frame is pushed on the top level
 *   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
 *   Handle     a new level
 *   Ask        the handler of the operation's place is found. In place: it answers, the loop goes on with the
 *              answer — nothing is allocated. General: the levels above and at the handler are taken off as `k`,
 *              and the clause runs below them
 *   Resume     the levels of a `k` are put back on top
 *
 * HOW A HANDLER IS FOUND: by the operation's place in the row, a number the compiler found (Rows.scala) — no class
 * is looked at. Walking down the levels, the place is this level's handler's, or the search goes on; past a handler
 * put by `handle`, which took its effect out of the row, the places after its own are one less. An interceptor
 * keeps its effect in the row, and changes no place. A program put into another row (`into`) runs under a level
 * with no handler, which RENAMES: an operation that passes it has, outside, its place in that row. Such a level
 * is a level like any other — popped with its value, taken into a `k` and put back with the rest.
 *
 * Levels and frames are immutable: a `k` is shared, never copied, and may be resumed any number of times. Types
 * are checked at the kernel's surface (Effects.scala); in here values are `Any`, and the casts say so. */
private[scala] object Machine:
  /** in here a row and an effect are any: the surface has checked them */
  type Any1[X] = Any
  type Node = Comp[Any1, Any]

  /** A handler on its level: the place it answers, and whether it took its effect out of the row (`handle`) or
   * left it there (`intercept`). Or the level of no handler, which renames: `renames`, the places outside (`Sub`).
   * The casts forget the handler's types, which `handle` has checked. */
  final class Mark(val place: Int, val takes: Boolean, handler: Handler[?, ?, ?, ?],
      val renames: IArray[Int] | Null = null):
    val general = handler match
      case general: Handler.General[?, ?, ?, ?] => general.asInstanceOf[Handler.General[Any1, Any1, Any, Any]]
      case _ => null
    val inPlace = handler match
      case inPlace: Handler.Stateful[?, ?, ?, ?, ?] =>
        inPlace.asInstanceOf[Handler.Stateful[Any1, Any1, Any, Any, Any]]
      case _ => null
    def init: Any = if inPlace != null then inPlace.init else null

  object Mark:
    /** a level that answers nothing (no operation is at the place -1) and gives its value on as it is */
    def renaming(sub: IArray[Int]): Mark =
      new Mark(-1, false, Handler.abort[Any1, Any1, Any, Any](pure)([X] => (op: Any) => throw NoHandler(op)), sub)

  /** what follows a value, inside one level */
  final class Frames(val next: Any => Node, val rest: Frames | Null)

  /** a level under the top one */
  final class Level(val frames: Frames | Null, val mark: Mark | Null, val state: Any, val below: Level | Null)

  /** the levels of a continuation, the lowest first */
  final class Pieces(val frames: Frames | Null, val mark: Mark | Null, val state: Any, val above: Pieces | Null)
      extends (Any => Node):
    def apply(x: Any): Node = new Comp.Resume(this, x)

  /** the two casts of a run: into the machine, where types are forgotten, and out of it */
  def run[R[_], A](p: Comp[R, A]): Step[R, A] =
    loop(p.asInstanceOf[Node], null, null, null, null, null, new Cell[Any](null)).asInstanceOf[Step[R, A]]

  /** One turn takes the computation `c` apart; or, when there is none, gives the value `x` to what waits for it.
   * So an answer in place and a `k` resumed go on as values: no `Done` is made for them. */
  @tailrec
  private def loop(c: Node | Null, x: Any, frames: Frames | Null, mark: Mark | Null, state: Any,
      below: Level | Null, cell: Cell[Any]): Step[Any1, Any] = c match
    case null =>
      if frames != null then loop(frames.next(x), null, frames.rest, mark, state, below, cell)
      else if mark != null then
        val after = returned(mark, state, x)
        if below == null then loop(after, null, null, null, null, null, cell)
        else loop(after, null, below.frames, below.mark, below.state, below.below, cell)
      else if below != null then loop(null, x, below.frames, below.mark, below.state, below.below, cell)
      else Step.Done(x)
    case bind: Comp.Bind[Any1, ?, Any] =>
      loop(bind.first, null, new Frames(bind.next.asInstanceOf[Any => Node], frames), mark, state, below, cell)
    case done: Comp.Done[Any1, Any] =>
      // the first line of `case null`, here too: the common case in one turn
      if frames != null then loop(frames.next(done.value), null, frames.rest, mark, state, below, cell)
      else loop(null, done.value, null, mark, state, below, cell)
    case ask: Comp.Ask[Any1, ?, ?] =>
      val place = numberOf(ask.at)
      if mark != null && mark.inPlace != null && mark.place == place then
        cell.state = state
        val answer = mark.inPlace.nn.answer[Any](ask.op, cell)
        loop(null, answer, frames, mark, cell.state, below, cell)
      else
        val found = find(place, mark, below, 0)
        if found < 0 then Step.Asked[Any1, Any1, Any, Any](placeAt(-1 - found), ask.op,
          take(frames, mark, state, below, -1))
        else
          val at = level(below, found)
          val handler = if found == 0 then mark.nn else at.nn.mark.nn
          if handler.inPlace != null then
            cell.state = at.nn.state
            val answer = handler.inPlace.nn.answer[Any](ask.op, cell)
            loop(null, answer, frames, mark, state, replace(below, found, cell.state), cell)
          else
            val k = take(frames, mark, state, below, found)
            val under = if found == 0 then below else at.nn.below
            val clause = handler.general.nn.on[Any](ask.op, k)
            if under == null then loop(clause, null, null, null, null, null, cell)
            else loop(clause, null, under.frames, under.mark, under.state, under.below, cell)
    case handle: Comp.Handle[Any1, Any] =>
      // a cast: the body is of another row, the one with the handler's effect
      loop(handle.body.asInstanceOf[Node], null, null, handle.mark, handle.mark.init,
        new Level(frames, mark, state, below), cell)
    case resume: Comp.Resume[Any1, Any] =>
      val empty = frames == null && mark == null && below == null
      var under = if empty then null else new Level(frames, mark, state, below)
      var piece = resume.k
      while piece.above != null do
        if piece.frames != null || piece.mark != null then
          under = new Level(piece.frames, piece.mark, piece.state, under)
        piece = piece.above.nn
      loop(null, resume.x, piece.frames, piece.mark, piece.state, under, cell)

  private def returned(mark: Mark, state: Any, value: Any): Node =
    if mark.general != null then mark.general.nn.ret(value) else mark.inPlace.nn.ret(state, value)

  /** how many levels down from `mark`'s the handler of `place` is, counted from `depth`; with no handler,
   * -1 - the place in the row of the whole run */
  @tailrec
  private def find(place: Int, mark: Mark | Null, below: Level | Null, depth: Int): Int =
    if mark != null && mark.place == place then depth
    else
      val outside =
        if mark == null then place
        else if mark.takes then (if place > mark.place then place - 1 else place)
        else if mark.renames != null then renamed(mark.renames.nn, place)
        else place
      if below == null then -1 - outside else find(outside, below.mark, below.below, depth + 1)

  /** the level `depth` down; null for the top one, which is not packed */
  private def level(below: Level | Null, depth: Int): Level | Null =
    var at = below
    var left = depth
    if depth == 0 then null
    else
      while left > 1 do
        at = at.nn.below
        left -= 1
      at

  /** the levels from the top down to `depth` (to the bottom when -1), as a continuation */
  private def take(frames: Frames | Null, mark: Mark | Null, state: Any, below: Level | Null, depth: Int): Pieces =
    var pieces = new Pieces(frames, mark, state, null)
    var at = below
    var left = depth
    while at != null && left != 0 do
      pieces = new Pieces(at.frames, at.mark, at.state, pieces)
      at = at.below
      left -= 1
    pieces

  /** the levels, the state of the one `depth` down replaced */
  private def replace(below: Level | Null, depth: Int, state: Any): Level | Null =
    val at = below.nn
    if depth == 1 then (if at.state.asInstanceOf[AnyRef] eq state.asInstanceOf[AnyRef] then at
      else new Level(at.frames, at.mark, state, at.below))
    else
      val under = replace(at.below, depth - 1, state)
      if under eq at.below then at else new Level(at.frames, at.mark, at.state, under)
