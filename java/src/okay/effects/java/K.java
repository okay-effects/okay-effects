package okay.effects.java;

/** The rest of a handled program, waiting for the answer of the operation it asked. A value, never changed:
 * resumed never, once, or many times. */
public final class K<B, R> {
  private final Object asked;
  private final Machine.Pieces pieces;

  K(Object asked, Machine.Pieces pieces) { this.asked = asked; this.pieces = pieces; }

  /** the rest resumed with the answer; the operation is the witness of the answer's type:
   * {@code case Among<X> among -> k.resume(among, among.values().get(0))} */
  public <X> Comp<B, R> resume(Op<?, X, ?> op, X answer) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp.Resume<>(pieces, answer);
  }
}
