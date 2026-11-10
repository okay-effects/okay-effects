import static okay.effects.java.Effects.*;

import okay.effects.java.Body;
import okay.effects.java.Comp;
import okay.effects.java.Handler;
import okay.effects.java.K;
import okay.effects.java.std.State;

/** A handler that does not answer every operation of its effect: the family is sealed, the {@code switch} is whole. */
final class MissingCase {
  static <R, A> Comp<R, A> readOnly(Body<State<Integer>, R, A> body) {
    return handle(State.class, new Handler<State<Integer>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(State<Integer> op, K<R, A> k) {
        return switch (op) { // refused: no case for a Put
          case State.Get<Integer> get -> k.resume(get, 0);
        };
      }
    }, body);
  }
}
