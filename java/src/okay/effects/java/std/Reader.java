package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.function.Function;
import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.InPlace;
import okay.effects.java.K;
import okay.effects.java.Op;

/** READER: a value of the surroundings. */
public sealed interface Reader<V> {
  record Ask<V>() implements Reader<V>, Op<Reader<V>, V> {}

  static <V, R> Comp<R, V> ask(In<Reader<V>, R> in) { return perform(in, new Ask<>()); }

  static <V, R, A> Comp<R, A> run(V value, Body<Reader<V>, R, A> body) {
    return handle(Reader.class, new InPlace<Reader<V>, Void, A, A, R>() {
      public Comp<R, A> ret(Void state, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Void> state, Reader<V> op) {
        return switch (op) {
          case Ask<V> ask -> state.answer(ask, value);
        };
      }
    }, null, body);
  }

  /** the program, its asks answered by the reader outside, through {@code f}: an interceptor */
  static <V, R, A> Comp<R, A> local(In<Reader<V>, R> in, Function<V, V> f, Comp<R, A> p) {
    return intercept(Reader.class, in, new Handler<Reader<V>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(Reader<V> op, K<R, A> k) {
        return switch (op) {
          case Ask<V> ask -> ask(in).flatMap(v -> k.resume(ask, f.apply(v)));
        };
      }
    }, p);
  }
}
