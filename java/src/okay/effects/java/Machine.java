package okay.effects.java;

import java.util.function.Function;

/** THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
 * the frames inside the handler, the handler, its state. The top level is kept unpacked in the loop's variables.
 *
 *   Bind       a frame is pushed on the top level
 *   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
 *   Handle     a new level
 *   Ask        the operation's handler is found. In place: it answers, the loop goes on — nothing is allocated.
 *              General: the levels above and at the handler are taken off as `k`, and the clause runs below them
 *   Resume     the levels of a `k` are put back on top
 *
 * HOW A HANDLER IS FOUND: by the operation's place in the row, as the types say. A level put by `handle` is a ROW
 * level: it took the first effect off the row. An operation says how many row levels it skips — none, when asked
 * plainly — and the search walks down the levels counting them. `up` and `under` of an operation alone only
 * change its count. Of any other computation they put a MARKER: a level with no handler, which adds to the count
 * of the operations that pass it — all of them (`up`), or those not going to the nearest row level (`under`). An
 * interceptor is not a row level and is not counted: it takes the operations of its class that pass it.
 *
 * A marker is a level like any other: popped when its computation has its value, taken into a `k` and put back
 * with the rest, copied when a state under it changes. So what holds of levels holds with markers among them.
 *
 * Levels and frames are never changed: a `k` is shared, never copied, and may be resumed any number of times.
 * Types are checked at the kernel's surface (Effects.java); in here they are raw and values are `Object` — Java
 * cannot keep the types of a loop's variables in step. That is what the two warnings suppressed say. */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Machine {
  private Machine() {}

  /** What a level is, besides its frames: a handler — of a row level (`row`) or an interceptor — or, with no
   * handler, a marker: an operation that passes it with `keep` or more row levels to skip skips `by` more. */
  record Mark(Class<?> effect, Handler general, InPlace inPlace, boolean row, int keep, int by) {
    static Mark handler(Class<?> effect, Handler general, InPlace inPlace, boolean row) {
      return new Mark(effect, general, inPlace, row, 0, 0);
    }

    static Mark marker(int keep, int by) { return new Mark(null, null, null, false, keep, by); }

    boolean marker() { return by != 0; }

    /** an interceptor's question: is the operation of its class, and its own */
    boolean accepts(Object op) {
      return effect.isInstance(op) && (general != null ? general.mine(op) : inPlace.mine(op));
    }
  }

  /** what follows a value, inside one level */
  record Frames(Function<Object, Comp> next, Frames rest) {}

  /** a level under the top one; a level with a handler or a marker always has a level below it */
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
          // the common case first: asked plainly, right under the handler of the row's first effect
          int found = mark != null && mark.row && ask.skip == 0 ? 0 : find(op, ask.skip, mark, below);
          if (found < 0) return new Step.Asked(ask.op, take(frames, mark, state, below, -1));
          Level at = level(below, found);
          Mark handler = found == 0 ? mark : at.mark;
          assert handler.effect.isInstance(op) : op; // the row sent it here: the types say it is of the effect
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
      // the value: a level with no frame left gives it to its handler's `ret`, and is popped; a marker only pops.
      // The marker is told by its number: a test of a field for null here sent the JIT into deoptimizing again
      // and again, and a choice ran 2.5 times slower.
      while (frames == null) {
        if (below == null) return new Step.Done(value);
        Mark returning = mark;
        Object last = state;
        frames = below.frames; mark = below.mark; state = below.state; below = below.below;
        if (returning != null && returning.by == 0) {
          c = returning.general != null ? returning.general.ret(value) : returning.inPlace.ret(last, value);
          continue run;
        }
      }
      c = frames.next.apply(value);
      frames = frames.rest;
    }
  }

  /** how many levels down the operation's handler is, the top one being 0; -1 when there is none. `skip` is how
   * many row levels the operation has still to pass: a marker adds to it, a row level passed takes one off. */
  private static int find(Object op, int skip, Mark top, Level below) {
    Mark mark = top;
    Level next = below;
    for (int depth = 0; ; depth++) {
      if (mark != null) {
        if (mark.row) {
          if (skip == 0) return depth;
          skip--;
        } else if (mark.marker()) {
          if (skip >= mark.keep) skip += mark.by;
        } else if (mark.accepts(op)) {
          return depth;
        }
      }
      if (next == null) return -1;
      mark = next.mark;
      next = next.below;
    }
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
