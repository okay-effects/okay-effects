package okay.effects.java;

import java.util.function.Function;
import java.util.function.Supplier;

/** THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
 * the frames inside the handler, the handler, its state. The top level is kept unpacked in the loop's variables.
 *
 *   Bind       a frame is pushed on the top level
 *   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
 *   Handle     a new level
 *   Ask        the operation's handler is found. In place: it answers, the loop goes on — nothing is allocated.
 *              General: the levels above and at the handler are taken off as `k`, and the clause runs below them
 *   Resume     the levels of a `k` are put back on top
 *   ensuring   a bind whose frame holds the finalizer (`Finally`): a value that comes to it waits while the
 *              finalizer runs
 *   Unwind     the levels of a `k` are put back on top, without its handler, and walked
 *   Walk       UNWINDING: the frames are passed with no value, a finalizer's frame runs its finalizer, a level is
 *              left without its `ret` — to the end of the unwinding, which gives nothing to what waited for it
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
 * WHERE A HANDLER WAS, while its `k` is unwound, is a marker too: the handler has left, and answers nothing. One
 * put by `handle` took a place of the row: the operations that pass skip one level less, and its own — with none
 * to skip — has nobody left to answer it: the finalizer that asks ends there, and the unwinding goes on (`GONE`).
 * One that kept its effect in the row sends its own to the next place (`KEPT`). An interceptor leaves no marker.
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

    /** where a handler that took its place of the row was, and one that kept its effect there */
    static final Mark GONE = marker(0, -1);
    static final Mark KEPT = marker(1, -1);

    boolean marker() { return by != 0; }

    /** an interceptor's question: is the operation of its class, and its own */
    boolean accepts(Object op) {
      return effect.isInstance(op) && (general != null ? general.mine(op) : inPlace.mine(op));
    }
  }

  /** what follows a value, inside one level */
  record Frames(Function<Object, Comp> next, Frames rest) {}

  /** `ensuring`: the frame of a finalizer. A value that comes waits while the finalizer runs; an unwinding runs
   * the finalizer and goes on. */
  record Finally<A>(Supplier<? extends Comp<?, ?>> finalizer) implements Function<A, Comp<A, ?>> {
    public Comp<A, ?> apply(A a) { return new Comp.Bind<>(finalizer.get(), dropped -> new Comp.Done<>(a)); }
  }

  /** the end of an unwinding, a frame over what waited for it: nothing is given on */
  static final class Unwound implements Function<Object, Comp> {
    public Comp apply(Object dropped) { return new Comp.Done(null); }
  }

  /** a level under the top one; a level with a handler or a marker always has a level below it */
  record Level(Frames frames, Mark mark, Object state, Level below) {}

  /** the levels of a continuation, the lowest first */
  record Pieces(Frames frames, Mark mark, Object state, Pieces above) implements Step.Rest {
    public Comp apply(Object x) { return new Comp.Resume(this, x); }
    public Comp unwind() { return new Comp.Unwind(this, null); }
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
          if (found < 0) {
            if (found == -1) return new Step.Asked(ask.op, take(frames, mark, state, below, -1));
            // its handler has left: the finalizer that asks ends here, and the unwinding goes on from here
            c = new Comp.Walk(ending(found == -2 ? below : level(below, -2 - found).below));
            continue;
          }
          Level at = level(below, found);
          Mark handler = found == 0 ? mark : at.mark;
          // The handler takes `op` as its effect: a cast, with no test. It holds by the types: the row sent the
          // operation to the handler of the effect E it names, and `perform` took it only as a class that is an E.
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
        case Comp.Unwind unwind -> {
          // the levels of `k`, over the end of the unwinding. A value that comes out of one of them — a handler
          // in it answered a finalizer — is dropped there, and the unwinding goes on (`walk`, as a frame).
          Unwound end = new Unwound();
          Comp.Walk walk = new Comp.Walk(end);
          below = new Level(new Frames(end, frames), mark, state, below);
          Pieces piece = unwind.k;
          Mark at = piece.mark != null && piece.mark.row ? unwind.left : null;
          while (piece.above != null) {
            below = new Level(new Frames(walk, piece.frames), at, piece.state, below);
            piece = piece.above;
            at = piece.mark;
          }
          frames = piece.frames; mark = at; state = piece.state;
          c = walk;
          continue;
        }
        case Comp.Walk walk -> {
          while (true) {
            if (frames == null) {
              // a level is left without its `ret`. At the bottom, the unwinding is not on this stack.
              if (below == null) throw new NoUnwinding();
              frames = below.frames; mark = below.mark; state = below.state; below = below.below;
            } else if (frames.next instanceof Finally last) {
              c = (Comp) last.finalizer.get();
              frames = new Frames(walk, frames.rest);
              continue run;
            } else if (frames.next == walk.to) {
              frames = frames.rest;
              break;
            } else {
              // a bind; or what an unwinding begun inside this one left
              frames = frames.rest;
            }
          }
          value = null;
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

  /** how many levels down the operation's handler is, the top one being 0; -1 when there is none; -2 - depth when
   * it was the handler that has left the level `depth` down. `skip` is how many row levels the operation has
   * still to pass: a marker adds to it, a row level passed takes one off. */
  private static int find(Object op, int skip, Mark top, Level below) {
    Mark mark = top;
    Level next = below;
    for (int depth = 0; ; depth++) {
      if (mark != null) {
        if (mark.row) {
          if (skip == 0) return depth;
          skip--;
        } else if (mark.marker()) {
          // below none to skip: the operation's own handler was here, and has left
          if (skip >= mark.keep && (skip += mark.by) < 0) return -2 - depth;
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

  /** the end of the unwinding that goes on where a handler has left, from the level under its own: the one its
   * levels were put back in, or else its own */
  private static Object ending(Level under) {
    return under.frames.next instanceof Comp.Walk walk ? walk.to : under.frames.next;
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
