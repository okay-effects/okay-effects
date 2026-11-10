package okay.effects.java;

/** The program under a handler of the effect {@code F}: made from the evidence that {@code F} is in its row, in
 * front of what is left, {@code R}. */
@FunctionalInterface
public interface Body<F, R, A> {
  Comp<Cons<F, R>, A> apply(In<F, Cons<F, R>> in);
}
