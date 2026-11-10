package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import okay.effects.java.Body;
import okay.effects.java.Comp;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.K;
import okay.effects.java.Op;

/** CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times. */
public sealed interface Choose {
  record Among<X>(List<X> values) implements Choose, Op<Choose, X> {}

  static <R, X> Comp<R, X> among(In<Choose, R> in, List<X> values) { return perform(in, new Among<>(values)); }

  /** no value: this path ends */
  static <R, X> Comp<R, X> none(In<Choose, R> in) { return perform(in, new Among<>(List.of())); }

  /** every value the program comes to */
  static <R, A> Comp<R, List<A>> all(Body<Choose, R, A> body) {
    return handle(Choose.class, new Handler<Choose, A, List<A>, R>() {
      public Comp<R, List<A>> ret(A a) { return pure(List.of(a)); }
      public Comp<R, List<A>> on(Choose op, K<R, List<A>> k) {
        return switch (op) {
          case Among<?> among -> every(among, 0, List.of(), k);
        };
      }
    }, body);
  }

  private static <X, R, A> Comp<R, List<A>> every(Among<X> among, int i, List<A> found, K<R, List<A>> k) {
    if (i == among.values().size()) return pure(found);
    return k.resume(among, among.values().get(i)).flatMap(more -> {
      List<A> all = new ArrayList<>(found);
      all.addAll(more);
      return every(among, i + 1, all, k);
    });
  }

  /** the first value the program comes to, the other paths not taken */
  static <R, A> Comp<R, Optional<A>> first(Body<Choose, R, A> body) {
    return handle(Choose.class, new Handler<Choose, A, Optional<A>, R>() {
      public Comp<R, Optional<A>> ret(A a) { return pure(Optional.of(a)); }
      public Comp<R, Optional<A>> on(Choose op, K<R, Optional<A>> k) {
        return switch (op) {
          case Among<?> among -> from(among, 0, k);
        };
      }
    }, body);
  }

  private static <X, R, A> Comp<R, Optional<A>> from(Among<X> among, int i, K<R, Optional<A>> k) {
    if (i == among.values().size()) return pure(Optional.empty());
    return k.resume(among, among.values().get(i))
        .flatMap(found -> found.isPresent() ? pure(found) : from(among, i + 1, k));
  }
}
