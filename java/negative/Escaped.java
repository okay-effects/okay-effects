import static okay.effects.java.Effects.*;

import okay.effects.java.Comp;
import okay.effects.java.Cons;
import okay.effects.java.In;
import okay.effects.java.Nil;
import okay.effects.java.std.Reader;
import okay.effects.java.std.State;

/** Evidence used where it does not hold: it speaks of one row, and does not fit another. */
final class Escaped {
  public static void main(String[] args) {
    // a handler's evidence carried out of it as the program's value: allowed, it is only a value
    Comp<Nil, State.Final<Integer, In<State<Integer>, Cons<State<Integer>, Nil>>>> out = State.run(0, st -> pure(st));
    Comp<Nil, Integer> after = out.flatMap(carried -> State.get(carried.value())); // refused: asked outside the handler
    // the outer handler's evidence under an inner handler, not brought up to its row; `rd.up()` is accepted
    Comp<Nil, State.Final<Integer, Integer>> under = Reader.run(1, rd -> State.run(0, st -> Reader.ask(rd))); // refused
    System.out.println(run(after) + run(under).value());
  }
}
