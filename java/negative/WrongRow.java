import static okay.effects.java.Effects.*;

import java.util.List;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.std.Choose;
import okay.effects.java.std.State;

/** A row that does not say the truth: handlers in another order than the program's type, a state of another type,
 * an effect asked that the declared type has not. */
final class WrongRow {
  /** a state, and a choice under it */
  static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> chosen() {
    return up(Choose.among(List.of(1, 2))).flatMap(x -> State.<Integer>get().map(s -> s + x));
  }

  static Comp<Integer, Eff<State<Integer>, ?>> lacking() {
    return State.<Integer>get().flatMap(s -> up(Choose.among(List.of(1, 2))).map(x -> s + x)); // refused: no choice
  }

  static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> unlifted() {
    return State.<Integer>get().flatMap(s -> Choose.among(List.of(1, 2)).map(x -> s + x)); // refused: not lifted
  }

  public static void main(String[] args) {
    // the handlers in the order of the row: this line is here to show that the ones after it are refused for theirs
    System.out.println(run(Choose.all(State.run(0, chosen()))));
    System.out.println(run(State.run(0, Choose.all(chosen())))); // refused: the choice is not the first of the row
    System.out.println(run(Choose.all(State.run("", chosen())))); // refused: the state is of integers
  }
}
