package okay.effects.java;

/** THE ROW of a computation — the effects it may ask — is a list in its type, in the order of the handlers, the
 * nearest first: {@code Eff<State<Integer>, Eff<Choose, ?>>} is a state, a choice under it, and anything under
 * that. Only types: there is no value of a row.
 *
 * A computation is good wherever the row has its effects. Java's types are not open inside: a row that is to be
 * used in longer rows, or under a handler that asks the effect next to its own, is written open at every step —
 * {@code Eff<State<Integer>, ? extends Eff<Choose, ?>>}. A row may be given a name, when no handler takes it apart:
 * {@code interface Mine extends Eff<Log, Eff<Store, Pure>> {}}. */
public interface Eff<E, R> {}
