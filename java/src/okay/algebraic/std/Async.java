package okay.algebraic.std;

import static okay.algebraic.Comp.*;

import java.util.function.Consumer;
import okay.algebraic.Comp;
import okay.algebraic.Handler;
import okay.algebraic.Op;

/** ASYNC: the answer comes later — the handler keeps the rest and returns; who has the answer resumes it. */
public sealed interface Async {
  record Await<X>(Consumer<Consumer<X>> register) implements Async, Op<X> {}

  static <X> Comp<X> await(Consumer<Consumer<X>> register) { return perform(new Await<>(register)); }

  /** the program started; {@code done} is called with its value, now or later */
  static <A> void start(Comp<A> p, Consumer<A> done) {
    Handler<Async, A, Void> h = Handler.of(
        Async.class,
        a -> { done.accept(a); return pure(null); },
        (op, k) -> switch (op) {
          case Await<?> await -> register(await, k);
        });
    handle(h, p).run();
  }

  private static <X> Comp<Void> register(Await<X> await, okay.algebraic.K<Void> k) {
    await.register().accept(x -> k.resume(await, x).run());
    return pure(null);
  }
}
