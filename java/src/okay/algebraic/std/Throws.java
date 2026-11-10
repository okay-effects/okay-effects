package okay.algebraic.std;

import static okay.algebraic.Comp.*;

import okay.algebraic.Comp;
import okay.algebraic.Handler;
import okay.algebraic.Op;

/** THROWS: the rest of the program is dropped. */
public sealed interface Throws<E> {
  record Raise<E, X>(E error) implements Throws<E>, Op<X> {}

  static <E, X> Comp<X> raise(E error) { return perform(new Raise<>(error)); }

  sealed interface Result<E, A> {}
  record Ok<E, A>(A value) implements Result<E, A> {}
  record Err<E, A>(E error) implements Result<E, A> {}

  static <E, A> Comp<Result<E, A>> run(Comp<A> p) {
    Handler<Throws<E>, A, Result<E, A>> h = Handler.of(
        Throws.class,
        a -> pure(new Ok<>(a)),
        (op, k) -> switch (op) {
          case Raise<E, ?> raise -> pure(new Err<>(raise.error()));
        });
    return handle(h, p);
  }
}
