package okay.effects.java;

/** THE ROW of a computation — the effects it may ask — is a list in its type:
 * {@code Cons<State<Integer>, Cons<Choose, Nil>>}. Only types: there is no value of a row. {@code Nil}: no effect
 * left, the program can run. */
public final class Nil {
  private Nil() {}
}
