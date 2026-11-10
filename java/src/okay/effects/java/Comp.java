package okay.effects.java;

import java.util.function.Function;
import java.util.function.Supplier;

/** A computation of an {@code A} that may ask the operations of the row {@code R}. Only data: nothing runs until
 * {@code step}. Made by the functions of {@link Effects}, never directly. Whoever takes a computation takes one
 * that asks no more than the row at hand: {@code Comp<A, ? super R>}. */
public abstract sealed class Comp<A, R> {
  /** a value */
  static final class Done<A, R> extends Comp<A, R> {
    final A value;
    Done(A value) { this.value = value; }
  }

  /** a question: an operation, to be answered by the handler `skip` row levels past the nearest */
  static final class Ask<X, R> extends Comp<X, R> {
    final Op<?, X, ?> op;
    final int skip;
    Ask(Op<?, X, ?> op, int skip) { this.op = op; this.skip = skip; }
  }

  /** a computation, and what follows its value; the rows of the two were checked where they were bound */
  static final class Bind<X, A, R> extends Comp<A, R> {
    final Comp<X, ?> first;
    final Function<X, ? extends Comp<A, ?>> next;
    Bind(Comp<X, ?> first, Function<X, ? extends Comp<A, ?>> next) { this.first = first; this.next = next; }
  }

  /** a computation under a handler, with the handler's state; or under the marker of an `up` or `under` */
  static final class Handle<A, R> extends Comp<A, R> {
    final Machine.Mark mark;
    final Object state;
    final Comp<?, ?> body;
    Handle(Machine.Mark mark, Object state, Comp<?, ?> body) { this.mark = mark; this.state = state; this.body = body; }
  }

  /** a continuation taken earlier, resumed with an answer */
  static final class Resume<A, R> extends Comp<A, R> {
    final Machine.Pieces k;
    final Object x;
    Resume(Machine.Pieces k, Object x) { this.k = k; this.x = x; }
  }

  /** this, then what {@code f} makes of its value. The row {@code Q} is one that has the effects of both: the
   * compiler finds it. */
  public final <B, Q extends R> Comp<B, Q> flatMap(Function<A, ? extends Comp<B, ? super Q>> f) {
    return new Bind<>(this, f);
  }

  public final <B, Q extends R> Comp<B, Q> map(Function<A, B> f) {
    return new Bind<>(this, a -> new Done<>(f.apply(a)));
  }

  /** the computation after this one, whose value is not needed. Where the two have different rows, write
   * {@code flatMap(done -> ..)}: javac finds the common row only through a lambda that has a parameter. */
  public final <B, Q extends R> Comp<B, Q> then(Supplier<? extends Comp<B, ? super Q>> next) {
    return new Bind<>(this, a -> next.get());
  }
}
