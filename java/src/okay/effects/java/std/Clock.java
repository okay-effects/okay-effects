package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.function.LongSupplier;
import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.In;
import okay.effects.java.InPlace;
import okay.effects.java.Op;

/** CLOCK: the time, in milliseconds — the system's, or one a test sets. */
public sealed interface Clock {
  record Now() implements Clock, Op<Clock, Long> {}

  static <R> Comp<R, Long> now(In<Clock, R> in) { return perform(in, new Now()); }

  static <R, A> Comp<R, A> system(Body<Clock, R, A> body) { return at(System::currentTimeMillis, body); }

  static <R, A> Comp<R, A> at(LongSupplier time, Body<Clock, R, A> body) {
    return handle(Clock.class, new InPlace<Clock, Void, A, A, R>() {
      public Comp<R, A> ret(Void state, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Void> state, Clock op) {
        return switch (op) {
          case Now now -> state.answer(now, time.getAsLong());
        };
      }
    }, null, body);
  }
}
