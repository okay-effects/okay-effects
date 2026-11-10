package okay.effects.java;

import java.util.function.Function;

/** THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
 * the frames inside the handler, the handler, its state. The top level is kept unpacked in the loop's variables.
 *
 *   Bind       a frame is pushed on the top level
 *   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
 *   Handle     a new level
 *   Ask        the nearest handler of the operation is found. In place: it answers, the loop goes on — nothing is
 *              allocated. General: the levels above and at the handler are taken off as `k`, and the clause runs
 *              below them
 *   Resume     the levels of a `k` are put back on top
 *
 * Levels and frames are never changed: a `k` is shared, never copied, and may be resumed any number of times.
 * Types are checked at the kernel's surface (Effects.java); in here they are raw and values are `Object` — Java
 * cannot keep the types of a loop's variables in step. That is what the two warnings suppressed say. */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Machine {
  private Machine() {}

  record Mark(Class<?> effect, Handler general, InPlace inPlace) {
    boolean accepts(Object op) {
      return effect.isInstance(op) && (general != null ? general.mine(op) : inPlace.mine(op));
    }
  }

  /** what follows a value, inside one level */
  record Frames(Function<Object, Comp> next, Frames rest) {}

  /** a level under the top one; a level with a handler always has a level below it */
  record Level(Frames frames, Mark mark, Object state, Level below) {}

  /** the levels of a continuation, the lowest first */
  record Pieces(Frames frames, Mark mark, Object state, Pieces above) implements Function<Object, Comp> {
    public Comp apply(Object x) { return new Comp.Resume(this, x); }
  }

  static <A, R> Step<A, R> run(Comp<A, R> p) {
    Comp c = p;
    Frames frames = null;
    Mark mark = null;
    Object state = null;
    Level below = null;
    Cell cell = new Cell();
    run:
    while (true) {
      Object value; // what `c` comes to, when it does at once
      switch (c) {
        case Comp.Bind bind -> {
          frames = new Frames(bind.next, frames);
          c = bind.first;
          continue;
        }
        case Comp.Done done -> value = done.value;
        case Comp.Ask ask -> {
          Object op = ask.op;
          int found = mark != null && mark.accepts(op) ? 0 : find(op, below);
          if (found < 0) return new Step.Asked(ask.op, take(frames, mark, state, below, -1));
          Level at = level(below, found);
          Mark handler = found == 0 ? mark : at.mark;
          if (handler.inPlace != null) {
            cell.value = found == 0 ? state : at.state;
            cell.asked = op;
            handler.inPlace.answer(cell, op);
            value = cell.answer;
            if (found == 0) state = cell.value; else below = replace(below, found, cell.value);
          } else {
            c = handler.general.on(op, new K(op, take(frames, mark, state, below, found)));
            Level under = found == 0 ? below : at.below;
            frames = under.frames; mark = under.mark; state = under.state; below = under.below;
            continue;
          }
        }
        case Comp.Handle handle -> {
          below = new Level(frames, mark, state, below);
          frames = null; mark = handle.mark; state = handle.state;
          c = handle.body;
          continue;
        }
        case Comp.Resume resume -> {
          below = new Level(frames, mark, state, below);
          Pieces piece = resume.k;
          while (piece.above != null) {
            if (piece.frames != null || piece.mark != null) {
              below = new Level(piece.frames, piece.mark, piece.state, below);
            }
            piece = piece.above;
          }
          frames = piece.frames; mark = piece.mark; state = piece.state;
          value = resume.x;
        }
      }
      // the value: a level with no frame left gives it to its handler's `ret`, and is popped
      while (frames == null) {
        if (below == null) return new Step.Done(value);
        Mark returning = mark;
        Object last = state;
        frames = below.frames; mark = below.mark; state = below.state; below = below.below;
        if (returning != null) {
          c = returning.general != null ? returning.general.ret(value) : returning.inPlace.ret(last, value);
          continue run;
        }
      }
      c = frames.next.apply(value);
      frames = frames.rest;
    }
  }

  /** how many levels down the operation's handler is, the first below being 1; -1 when there is none */
  private static int find(Object op, Level below) {
    int depth = 1;
    for (Level at = below; at != null; at = at.below, depth++) {
      if (at.mark != null && at.mark.accepts(op)) return depth;
    }
    return -1;
  }

  /** the level `depth` down; null for the top one, which is not packed */
  private static Level level(Level below, int depth) {
    if (depth == 0) return null;
    Level at = below;
    for (int left = depth; left > 1; left--) at = at.below;
    return at;
  }

  /** the levels from the top down to `depth` (to the bottom when -1), as a continuation */
  private static Pieces take(Frames frames, Mark mark, Object state, Level below, int depth) {
    Pieces pieces = new Pieces(frames, mark, state, null);
    for (Level at = below; at != null && depth != 0; at = at.below, depth--) {
      pieces = new Pieces(at.frames, at.mark, at.state, pieces);
    }
    return pieces;
  }

  /** the levels, the state of the one `depth` down replaced */
  private static Level replace(Level at, int depth, Object state) {
    if (depth == 1) return at.state == state ? at : new Level(at.frames, at.mark, state, at.below);
    Level under = replace(at.below, depth - 1, state);
    return under == at.below ? at : new Level(at.frames, at.mark, at.state, under);
  }
}
