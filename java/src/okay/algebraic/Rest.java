package okay.algebraic;

import java.util.function.Function;

/** A continuation as data: what waits for an {@code X} to come to an {@code A}. A value: resumed never, once or
 * many times. */
public sealed interface Rest<X, A> {
  record End<A>() implements Rest<A, A> {}
  record Then<X, Y, A>(Function<X, Comp<Y>> next, Rest<Y, A> rest) implements Rest<X, A> {}
  record Both<X, Y, A>(Rest<X, Y> first, Rest<Y, A> rest) implements Rest<X, A> {}

  /** the computation resumed with the answer */
  default Comp<A> resume(X x) { return new Comp.Plug<>(new Comp.Done<>(x), this); }
}
