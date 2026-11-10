package okay.algebraic.std;

import static okay.algebraic.Comp.*;

import java.util.function.Function;
import okay.algebraic.Comp;
import okay.algebraic.Handler;
import okay.algebraic.Op;

/** STATE. An effect is a sealed family of operations — data; each says what answers it. */
public sealed interface State<S> {
  record Get<S>() implements State<S>, Op<S> {}
  record Put<S>(S value) implements State<S>, Op<Void> {}

  static <S> Comp<S> get() { return perform(new Get<>()); }
  static <S> Comp<Void> put(S s) { return perform(new Put<>(s)); }

  /** the final state with the value */
  record Final<S, A>(S state, A value) {}

  /** the state passed along: a program's answer is a function of the state it is resumed in */
  static <S, A> Comp<Final<S, A>> run(S s0, Comp<A> p) {
    Handler<State<S>, A, Function<S, Comp<Final<S, A>>>> h = Handler.of(
        State.class,
        a -> pure(s -> pure(new Final<>(s, a))),
        (op, k) -> switch (op) {
          case Get<S> get -> pure(s -> k.resume(get, s).flatMap(f -> f.apply(s)));
          case Put<S> put -> pure(s -> k.resume(put, null).flatMap(f -> f.apply(put.value())));
        });
    return handle(h, p).flatMap(f -> f.apply(s0));
  }
}
