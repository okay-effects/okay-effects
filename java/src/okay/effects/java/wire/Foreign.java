package okay.effects.java.wire;

import java.util.List;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Effects;
import okay.effects.java.Op;

/** THE OPERATION THAT CROSSES a process: a name and arguments in JSON, answered in JSON. A program written over it
 * runs in one process, in one language; its handlers may be in another. Only this data crosses — a continuation
 * stays where it was made, known outside by a number. The messages are the okay wire's (wire/README.md). */
public sealed interface Foreign {
  record Call(String name, List<Json> args) implements Foreign, Op<Foreign, Json> {}

  static Comp<Json, Eff<Foreign, ?>> call(String name, Json... args) {
    return Effects.perform(new Call(name, List.of(args)));
  }

  /** the wire's version: a host refuses a far side of any other */
  long SHIM = 9;
}
