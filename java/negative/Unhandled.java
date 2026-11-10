import static okay.effects.java.Effects.*;

import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.std.State;
import okay.effects.java.std.Writer;

/** An effect with no handler: the program's row is not empty, and {@code run} takes only what fits the empty one. */
final class Unhandled {
  static Comp<Integer, Eff<State<Integer>, ?>> counter() {
    return State.<Integer>get().flatMap(s -> State.put(s + 1).map(done -> s));
  }

  static Comp<Integer, Eff<State<Integer>, Eff<Writer<String>, ?>>> program() {
    return counter().flatMap(s -> up(Writer.tell("was " + s)).map(done -> s));
  }

  public static void main(String[] args) {
    // every effect handled: these lines are here to show that the ones after them are refused for what they lack
    System.out.println(run(State.run(0, counter())));
    System.out.println(run(Writer.run(State.run(0, program()))));
    System.out.println(run(counter())); // refused: the state has no handler
    System.out.println(run(State.run(0, program()))); // refused: the writer has none
  }
}
