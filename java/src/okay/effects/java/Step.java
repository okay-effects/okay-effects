package okay.effects.java;

import java.util.function.Function;

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
public sealed interface Step<R, A> {
  record Done<R, A>(A value) implements Step<R, A> {}
  record Asked<R, X, A>(Op<?, X> op, Function<X, Comp<R, A>> k) implements Step<R, A> {}
}
