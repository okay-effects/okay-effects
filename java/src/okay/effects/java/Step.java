package okay.effects.java;

import java.util.function.Function;

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
public sealed interface Step<A, R> {
  record Done<A, R>(A value) implements Step<A, R> {}
  record Asked<X, A, R>(Op<?, X, ?> op, Function<X, Comp<A, R>> k) implements Step<A, R> {}
}
