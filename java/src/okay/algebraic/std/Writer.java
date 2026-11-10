package okay.algebraic.std;

import static okay.algebraic.Comp.*;

import java.util.ArrayList;
import java.util.List;
import okay.algebraic.Comp;
import okay.algebraic.Handler;
import okay.algebraic.Op;

/** WRITER: values told, collected in order. */
public sealed interface Writer<W> {
  record Tell<W>(W value) implements Writer<W>, Op<Void> {}

  static <W> Comp<Void> tell(W w) { return perform(new Tell<>(w)); }

  record Logged<W, A>(List<W> log, A value) {
    Logged<W, A> after(W w) {
      List<W> all = new ArrayList<>(List.of(w));
      all.addAll(log);
      return new Logged<>(List.copyOf(all), value);
    }
  }

  static <W, A> Comp<Logged<W, A>> run(Comp<A> p) {
    Handler<Writer<W>, A, Logged<W, A>> h = Handler.of(
        Writer.class,
        a -> pure(new Logged<>(List.of(), a)),
        (op, k) -> switch (op) {
          case Tell<W> tell -> k.resume(tell, null).map(l -> l.after(tell.value()));
        });
    return handle(h, p);
  }
}
