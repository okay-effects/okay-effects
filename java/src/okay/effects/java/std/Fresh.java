package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.In;
import okay.effects.java.InPlace;
import okay.effects.java.Op;

/** FRESH: a number never given before, in this run. */
public sealed interface Fresh {
  record Next() implements Fresh, Op<Fresh, Long> {}

  static <R> Comp<R, Long> fresh(In<Fresh, R> in) { return perform(in, new Next()); }

  static <R, A> Comp<R, A> run(Body<Fresh, R, A> body) {
    return handle(Fresh.class, new InPlace<Fresh, Long, A, A, R>() {
      public Comp<R, A> ret(Long next, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Long> next, Fresh op) {
        return switch (op) {
          case Next n -> { next.value += 1; yield next.answer(n, next.value - 1); }
        };
      }
    }, 0L, body);
  }
}
