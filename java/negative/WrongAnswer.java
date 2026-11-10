import static okay.effects.java.Effects.*;

import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Handler;
import okay.effects.java.InPlace;
import okay.effects.java.K;
import okay.effects.java.std.State;

/** An operation answered by a value of another type than it asks: the operation is the witness of the type. */
final class WrongAnswer {
  static <A, R> Comp<A, R> inPlace(Comp<A, ? super Eff<State<Integer>, R>> p) {
    return handle(State.class, new InPlace<State<Integer>, Integer, A, A, R>() {
      public Comp<A, R> ret(Integer s, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Integer> state, State<Integer> op) {
        return switch (op) {
          case State.Get<Integer> get -> state.answer(get, "zero"); // refused: a Get<Integer> asks an Integer
          case State.Put<Integer> put -> state.answer(put, null);
        };
      }
    }, 0, p);
  }

  static <A, R> Comp<A, R> general(Comp<A, ? super Eff<State<Integer>, R>> p) {
    return handle(State.class, new Handler<State<Integer>, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(State<Integer> op, K<A, R> k) {
        return switch (op) {
          case State.Get<Integer> get -> k.resume(get, 0);
          case State.Put<Integer> put -> k.resume(put, put.value()); // refused: a Put is answered by nothing
        };
      }
    }, p);
  }
}
