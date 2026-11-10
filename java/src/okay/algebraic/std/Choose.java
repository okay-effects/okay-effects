package okay.algebraic.std;

import static okay.algebraic.Comp.*;

import java.util.ArrayList;
import java.util.List;
import okay.algebraic.Comp;
import okay.algebraic.Handler;
import okay.algebraic.K;
import okay.algebraic.Op;

/** CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times. */
public sealed interface Choose {
  record Among<X>(List<X> values) implements Choose, Op<X> {}

  static <X> Comp<X> among(List<X> values) { return perform(new Among<>(values)); }

  /** every value the program comes to */
  static <A> Comp<List<A>> all(Comp<A> p) {
    Handler<Choose, A, List<A>> h = Handler.of(
        Choose.class,
        a -> pure(List.of(a)),
        (op, k) -> switch (op) {
          case Among<?> among -> from(among, 0, List.of(), k);
        });
    return handle(h, p);
  }

  private static <X, A> Comp<List<A>> from(Among<X> among, int i, List<A> found, K<List<A>> k) {
    if (i == among.values().size()) return pure(found);
    return k.resume(among, among.values().get(i)).flatMap(more -> {
      List<A> all = new ArrayList<>(found);
      all.addAll(more);
      return from(among, i + 1, List.copyOf(all), k);
    });
  }
}
