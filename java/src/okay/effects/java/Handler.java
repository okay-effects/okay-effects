package okay.effects.java;

/** THE GENERAL HANDLER of the effect {@code F}, in a program of an {@code A}: a {@code B}, asking what is left,
 * {@code R}. A clause is given the rest of the program, {@code k}, as a value: to resume once, many times, later,
 * or never. */
public interface Handler<F, A, B, R> {
  Comp<R, B> ret(A a);

  /** an operation of the effect, and the rest of the program: a {@code switch} over the sealed family */
  Comp<R, B> on(F op, K<R, B> k);

  /** of the operations of {@code F}, those this handler answers; any other passes outside */
  default boolean mine(F op) { return true; }
}
