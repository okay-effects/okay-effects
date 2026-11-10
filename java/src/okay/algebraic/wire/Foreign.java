package okay.algebraic.wire;

import java.util.List;
import okay.algebraic.Comp;
import okay.algebraic.Op;

/** THE OPERATION THAT CROSSES a process: a name and arguments in JSON, answered in JSON. A program written over it
 * runs in one process, in one language; its handlers may be in another. Only this data crosses — a continuation
 * stays where it was made, known outside by a number. The messages are the okay wire's (SPEC.md, "The wire"). */
public sealed interface Foreign {
  record Call(String name, List<Json> args) implements Foreign, Op<Json> {}

  static Comp<Json> call(String name, Json... args) { return Comp.perform(new Call(name, List.of(args))); }

  long SHIM = 9;

  /** The far side's failure, by name. */
  final class Condition extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final String kind;
    public Condition(String kind, String message) { super(kind + ": " + message); this.kind = kind; }
  }
}
