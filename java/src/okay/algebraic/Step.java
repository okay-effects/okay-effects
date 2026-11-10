package okay.algebraic;

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
public sealed interface Step<A> {
  record Done<A>(A value) implements Step<A> {}
  record Asked<X, A>(Op<X> op, Rest<X, A> k) implements Step<A> {}
}
