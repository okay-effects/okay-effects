package okay.algebraic;

import java.util.function.BiFunction;
import java.util.function.Function;

/** The answers of a handler of the effect {@code E}, in a program of an {@code A}: a {@code B}. */
public interface Handler<E, A, B> {
  /** the effect: at run time, the class of its operations */
  Class<? super E> effect();

  Comp<B> ret(A a);

  /** an operation of the effect, and the rest of the program: {@code switch} over the sealed family */
  Comp<B> on(E op, K<B> k);

  static <E, A, B> Handler<E, A, B> of(
      Class<? super E> effect, Function<A, Comp<B>> ret, BiFunction<E, K<B>, Comp<B>> on) {
    return new Handler<>() {
      public Class<? super E> effect() { return effect; }
      public Comp<B> ret(A a) { return ret.apply(a); }
      public Comp<B> on(E op, K<B> k) { return on.apply(op, k); }
    };
  }
}
