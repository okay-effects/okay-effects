package okay.effects.java;

import java.util.function.Function;
import java.util.function.Supplier;

/** A computation of an {@code A} that may ask the operations of the row {@code R}. Only data: nothing runs until
 * {@code step}. Made by the functions of {@link Effects}, never directly: an operation is asked with evidence. */
public abstract sealed class Comp<R, A> {
  /** a value */
  static final class Done<R, A> extends Comp<R, A> {
    final A value;
    Done(A value) { this.value = value; }
  }

  /** a question: an operation, to be answered */
  static final class Ask<R, X> extends Comp<R, X> {
    final Op<?, X> op;
    Ask(Op<?, X> op) { this.op = op; }
  }

  /** a computation, and what follows its value */
  static final class Bind<R, X, A> extends Comp<R, A> {
    final Comp<R, X> first;
    final Function<X, Comp<R, A>> next;
    Bind(Comp<R, X> first, Function<X, Comp<R, A>> next) { this.first = first; this.next = next; }
  }

  /** a computation under a handler, with the handler's state */
  static final class Handle<R, A> extends Comp<R, A> {
    final Machine.Mark mark;
    final Object state;
    final Comp<?, ?> body;
    Handle(Machine.Mark mark, Object state, Comp<?, ?> body) { this.mark = mark; this.state = state; this.body = body; }
  }

  /** a continuation taken earlier, resumed with an answer */
  static final class Resume<R, A> extends Comp<R, A> {
    final Machine.Pieces k;
    final Object x;
    Resume(Machine.Pieces k, Object x) { this.k = k; this.x = x; }
  }

  public final <B> Comp<R, B> flatMap(Function<A, Comp<R, B>> f) { return new Bind<>(this, f); }

  public final <B> Comp<R, B> map(Function<A, B> f) { return new Bind<>(this, a -> new Done<>(f.apply(a))); }

  /** the computation after this one, whose value is not needed */
  public final <B> Comp<R, B> then(Supplier<Comp<R, B>> next) { return new Bind<>(this, a -> next.get()); }

  /** the same computation where a longer row is wanted: it asks no more than it did */
  @SuppressWarnings("unchecked") // a row is only a type: no value of a computation depends on it
  public final <F> Comp<Cons<F, R>, A> up() { return (Comp<Cons<F, R>, A>) this; }
}
