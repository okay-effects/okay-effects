import static okay.effects.java.Effects.*;

import okay.effects.java.Comp;
import okay.effects.java.In;
import okay.effects.java.std.State;
import okay.effects.java.std.Writer;

/** An effect with no handler: the program's row is not empty, and {@code run} takes only the empty one. */
final class Unhandled {
  static <R> Comp<R, Integer> counter(In<State<Integer>, R> st) {
    return State.get(st).flatMap(s -> State.put(st, s + 1).map(done -> s));
  }

  static <R> Comp<R, Integer> program(In<State<Integer>, R> st, In<Writer<String>, R> wr) {
    return counter(st).flatMap(s -> Writer.tell(wr, "was " + s).map(done -> s));
  }

  /** the state handled, the writer left in the row */
  static <R> Comp<R, State.Final<Integer, Integer>> half(In<Writer<String>, R> wr) {
    return State.run(0, st -> program(st, wr.up()));
  }

  public static void main(String[] args) {
    // every effect handled: these lines are here to show that the ones after them are refused for what they lack
    System.out.println(run(State.run(0, st -> counter(st))));
    System.out.println(run(Writer.run(String.class, wr -> half(wr))));
    System.out.println(run(counter(In.here()))); // refused: the state has no handler
    System.out.println(run(half(In.here()))); // refused: the writer has none
  }
}
