package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.function.Function;
import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.In;
import okay.effects.java.InPlace;
import okay.effects.java.Op;

/** STATE. An effect is a sealed family of operations — data; each says what answers it. */
public sealed interface State<S> {
  record Get<S>() implements State<S>, Op<State<S>, S> {}
  record Put<S>(S value) implements State<S>, Op<State<S>, Void> {}

  static <S, R> Comp<R, S> get(In<State<S>, R> in) { return perform(in, new Get<>()); }
  static <S, R> Comp<R, Void> put(In<State<S>, R> in, S s) { return perform(in, new Put<>(s)); }
  static <S, R> Comp<R, Void> modify(In<State<S>, R> in, Function<S, S> f) {
    return get(in).flatMap(s -> put(in, f.apply(s)));
  }

  /** the final state with the value */
  record Final<S, A>(S state, A value) {}

  /** the state kept by the handler, answered in place; each path of the program has its own */
  static <S, R, A> Comp<R, Final<S, A>> run(S s0, Body<State<S>, R, A> body) {
    return handle(State.class, new InPlace<State<S>, S, A, Final<S, A>, R>() {
      public Comp<R, Final<S, A>> ret(S s, A a) { return pure(new Final<>(s, a)); }
      public Cell.Answered answer(Cell<S> state, State<S> op) {
        return switch (op) {
          case Get<S> get -> state.answer(get, state.value);
          case Put<S> put -> { state.value = put.value(); yield state.answer(put, null); }
        };
      }
    }, s0, body);
  }
}
