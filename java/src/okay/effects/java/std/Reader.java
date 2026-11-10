package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.function.Function;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Handler;
import okay.effects.java.InPlace;
import okay.effects.java.K;
import okay.effects.java.Op;

/** READER: a value of the surroundings. */
public sealed interface Reader<V> {
  record Ask<V>() implements Reader<V>, Op<Reader<V>, V> {}

  static <V> Comp<V, Eff<Reader<V>, ?>> ask() { return perform(new Ask<>()); }

  static <V, A, R> Comp<A, R> run(V value, Comp<A, ? super Eff<Reader<V>, R>> p) {
    return handle(Reader.class, new InPlace<Reader<V>, Void, A, A, R>() {
      public Comp<A, R> ret(Void state, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Void> state, Reader<V> op) {
        return switch (op) {
          case Ask<V> ask -> state.answer(ask, value);
        };
      }
    }, null, p);
  }

  /** the program, its asks answered by the reader outside, through {@code f}: an interceptor */
  static <V, A, R extends Eff<Reader<V>, ?>> Comp<A, R> local(Function<V, V> f, Comp<A, ? super R> p) {
    return intercept(Reader.class, new Handler<Reader<V>, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Reader<V> op, K<A, R> k) {
        return switch (op) {
          case Ask<V> ask -> Reader.<V>ask().flatMap(v -> k.resume(ask, f.apply(v)));
        };
      }
    }, p);
  }
}
