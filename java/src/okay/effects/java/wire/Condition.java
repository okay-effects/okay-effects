package okay.effects.java.wire;

/** A failure that crosses, by name: raised on one side, handled on the other ({@code Throws<Condition>}). */
public final class Condition extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public final String kind;
  public final String message;

  public Condition(String kind, String message) {
    super(kind + ": " + message);
    this.kind = kind;
    this.message = message;
  }
}
