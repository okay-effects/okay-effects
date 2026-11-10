package okay.effects.scala

import scala.annotation.tailrec

/** THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
 * the frames inside the handler, the handler, its state. The top level is kept unpacked in the loop's arguments.
 *
 *   Bind       a frame is pushed on the top level
 *   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
 *   Handle     a new level
 *   Ask        the nearest handler of the operation is found. In place: it answers, the loop goes on — nothing is
 *              allocated but the answer. General: the levels above and at the handler are taken off as `k`, and
 *              the clause runs below them
 *   Resume     the levels of a `k` are put back on top
 *
 * Levels and frames are immutable: a `k` is shared, never copied, and may be resumed any number of times. Types
 * are checked at the kernel's surface (Effects.scala); in here values are `Any`, and the casts say so. */
private[scala] object Machine:
  type Node = Comp[Nothing, Any]

  final class Mark(effect: Class[?], general0: Handler[?, ?, ?, ?] | Null, inPlace0: InPlace[?, ?, ?, ?, ?] | Null):
    val general = general0.asInstanceOf[Handler[Any, Any, Any, Any] | Null]
    val inPlace = inPlace0.asInstanceOf[InPlace[Any, Any, Any, Any, Any] | Null]
    def accepts(op: Any): Boolean =
      effect.isInstance(op) && (if general != null then general.mine(op) else inPlace.nn.mine(op))

  /** what follows a value, inside one level */
  final class Frames(val next: Any => Node, val rest: Frames | Null)

  /** a level under the top one */
  final class Level(val frames: Frames | Null, val mark: Mark | Null, val state: Any, val below: Level | Null)

  /** the levels of a continuation, the lowest first */
  final class Pieces(val frames: Frames | Null, val mark: Mark | Null, val state: Any, val above: Pieces | Null)
      extends (Any => Comp[Any, Any]):
    def apply(x: Any): Comp[Any, Any] = new Comp.Resume(this, x)

  val unit: Comp[Any, Unit] = new Comp.Done(())

  def run[E, A](p: Comp[E, A]): Step[E, A] =
    loop(p.asInstanceOf[Node], null, null, null, null, new Cell[Any](null)).asInstanceOf[Step[E, A]]

  @tailrec
  private def loop(c: Node, frames: Frames | Null, mark: Mark | Null, state: Any, below: Level | Null,
      cell: Cell[Any]): Step[Nothing, Any] = c match
    case bind: Comp.Bind[?, ?, ?] =>
      loop(bind.first, new Frames(bind.next.asInstanceOf[Any => Node], frames), mark, state, below, cell)
    case done: Comp.Done[?] =>
      if frames != null then loop(frames.next(done.value), frames.rest, mark, state, below, cell)
      else if mark != null then
        val after = returned(mark, state, done.value)
        if below == null then loop(after, null, null, null, null, cell)
        else loop(after, below.frames, below.mark, below.state, below.below, cell)
      else if below != null then loop(done, below.frames, below.mark, below.state, below.below, cell)
      else Step.Done(done.value)
    case ask: Comp.Ask[?, ?] =>
      if mark != null && mark.inPlace != null && mark.accepts(ask.op) then
        cell.value = state
        val answer = mark.inPlace.nn.answer(cell, ask.op.asInstanceOf[Op[Any, Any]])
        loop(new Comp.Done(answer), frames, mark, cell.value, below, cell)
      else
        val found = find(ask.op, mark, below)
        if found < 0 then Step.Asked(ask.op.asInstanceOf[Op[Nothing, Any]], take(frames, mark, state, below, -1))
        else
          val at = level(below, found)
          val handler = if found == 0 then mark.nn else at.nn.mark.nn
          if handler.inPlace != null then
            cell.value = at.nn.state
            val answer = handler.inPlace.nn.answer(cell, ask.op.asInstanceOf[Op[Any, Any]])
            loop(new Comp.Done(answer), frames, mark, state, replace(below, found, cell.value), cell)
          else
            val k = take(frames, mark, state, below, found)
            val under = if found == 0 then below else at.nn.below
            val clause = handler.general.nn.on(ask.op.asInstanceOf[Op[Any, Any]], k).asInstanceOf[Node]
            if under == null then loop(clause, null, null, null, null, cell)
            else loop(clause, under.frames, under.mark, under.state, under.below, cell)
    case handle: Comp.Handle[?, ?] =>
      loop(handle.body.asInstanceOf[Node], null, handle.mark, handle.state, new Level(frames, mark, state, below),
        cell)
    case resume: Comp.Resume[?, ?] =>
      val empty = frames == null && mark == null && below == null
      var under = if empty then null else new Level(frames, mark, state, below)
      var piece = resume.k
      while piece.above != null do
        if piece.frames != null || piece.mark != null then
          under = new Level(piece.frames, piece.mark, piece.state, under)
        piece = piece.above.nn
      loop(new Comp.Done(resume.x), piece.frames, piece.mark, piece.state, under, cell)

  private def returned(mark: Mark, state: Any, value: Any): Node =
    (if mark.general != null then mark.general.nn.ret(value) else mark.inPlace.nn.ret(state, value)).asInstanceOf[Node]

  /** how many levels down the operation's handler is: 0 the top level, 1 the first below; -1 when there is none */
  private def find(op: Any, mark: Mark | Null, below: Level | Null): Int =
    if mark != null && mark.accepts(op) then 0
    else
      var depth = 1
      var at = below
      while at != null && !(at.mark != null && at.mark.nn.accepts(op)) do
        at = at.below
        depth += 1
      if at == null then -1 else depth

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
