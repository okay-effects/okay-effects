package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.List;
import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.In;
import okay.effects.java.InPlace;
import okay.effects.java.Op;

/** WRITER: values told, collected in order. */
public sealed interface Writer<W> {
  record Tell<W>(W value) implements Writer<W>, Op<Writer<W>, Void> {}

  static <W, R> Comp<R, Void> tell(In<Writer<W>, R> in, W w) { return perform(in, new Tell<>(w)); }

  /** what was told, with the value */
  record Logged<W, A>(List<W> log, A value) {}

  static <W, R, A> Comp<R, Logged<W, A>> run(Body<Writer<W>, R, A> body) {
    return handle(Writer.class, new InPlace<Writer<W>, Chain<W>, A, Logged<W, A>, R>() {
      public Comp<R, Logged<W, A>> ret(Chain<W> told, A a) { return pure(new Logged<>(Chain.list(told), a)); }
      public Cell.Answered answer(Cell<Chain<W>> told, Writer<W> op) {
        return switch (op) {
          case Tell<W> tell -> { told.value = new Chain<>(tell.value(), told.value); yield told.answer(tell, null); }
        };
      }
    }, null, body);
  }

  /** the same; {@code of} only names the type of what is told, where the compiler cannot find it */
  static <W, R, A> Comp<R, Logged<W, A>> run(Class<W> of, Body<Writer<W>, R, A> body) { return run(body); }
}
