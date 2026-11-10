package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.function.Function;
import okay.effects.java.Body;
import okay.effects.java.Comp;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.K;
import okay.effects.java.Op;

/** THROWS: the rest of the program is dropped. */
public sealed interface Throws<E> {
  /** answered by nothing: by an {@code X} of any type, for there will be none */
  record Raise<E, X>(E error) implements Throws<E>, Op<Throws<E>, X> {}

  static <E, R, X> Comp<R, X> raise(In<Throws<E>, R> in, E error) { return perform(in, new Raise<>(error)); }

  sealed interface Result<E, A> {}
  record Ok<E, A>(A value) implements Result<E, A> {}
  record Err<E, A>(E error) implements Result<E, A> {}

  static <E, R, A> Comp<R, Result<E, A>> run(Body<Throws<E>, R, A> body) {
    return handle(Throws.class, new Handler<Throws<E>, A, Result<E, A>, R>() {
      public Comp<R, Result<E, A>> ret(A a) { return pure(new Ok<>(a)); }
      public Comp<R, Result<E, A>> on(Throws<E> op, K<R, Result<E, A>> k) {
        return switch (op) {
          case Raise<E, ?> raise -> pure(new Err<>(raise.error()));
        };
      }
    }, body);
  }

  /** the same; {@code of} only names the type of the failure, where the compiler cannot find it */
  static <E, R, A> Comp<R, Result<E, A>> run(Class<E> of, Body<Throws<E>, R, A> body) { return run(body); }

  /** the program, or what {@code rescue} makes of its failure */
  static <E, R, A> Comp<R, A> recover(Function<E, Comp<R, A>> rescue, Body<Throws<E>, R, A> body) {
    return handle(Throws.class, new Handler<Throws<E>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(Throws<E> op, K<R, A> k) {
        return switch (op) {
          case Raise<E, ?> raise -> rescue.apply(raise.error());
        };
      }
    }, body);
  }
}
