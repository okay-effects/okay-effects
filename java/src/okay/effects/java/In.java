package okay.effects.java;

/** EVIDENCE that the effect {@code E} is in the row {@code R}: what {@code perform} asks for, and what makes an
 * effect without a handler an error of the compiler. A handler gives it to its body; the body passes it on. A proof
 * only: at run time an operation finds its handler by its class. */
public final class In<E, R> {
  private In() {}

  /** the first effect of a row is in it */
  public static <E, R> In<E, Cons<E, R>> here() { return new In<>(); }

  /** an effect of a row is in a longer one: the evidence of an outer handler, under an inner one */
  public <F> In<E, Cons<F, R>> up() { return new In<>(); }
}
