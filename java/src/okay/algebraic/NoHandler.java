package okay.algebraic;

/** An operation that reached the top: its effect has no handler. */
public final class NoHandler extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public final transient Op<?> op;
  public NoHandler(Op<?> op) { super("an operation with no handler: " + op); this.op = op; }
}
