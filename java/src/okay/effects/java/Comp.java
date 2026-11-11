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

  /** a question: an operation, to be answered by the handler `skip` row levels past the nearest. An {@link Op},
   * or a {@link Sensitive.Op}, which only {@link Audit} asks */
  static final class Ask<X, R> extends Comp<X, R> {
    final Object op;
    final int skip;
    Ask(Object op, int skip) { this.op = op; this.skip = skip; }
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

  /** a continuation taken earlier, unwound: its finalizers run, and nothing else of it. Where its handler was
   * stands `left`: what becomes of an operation of the handler's own that a finalizer asks. */
  static final class Unwind<R> extends Comp<Void, R> {
    final Machine.Pieces k;
    final Machine.Mark left;
    Unwind(Machine.Pieces k, Machine.Mark left) { this.k = k; this.left = left; }
  }

  /** an unwinding that goes on from here, to its end `to`. Also the frame it leaves where it will go on: what
   * comes there — a finalizer's value, the value of a level being unwound — is dropped. */
  static final class Walk<A, R> extends Comp<A, R> implements Function<Object, Comp<A, R>> {
    final Object to;
    Walk(Object to) { this.to = to; }
    public Comp<A, R> apply(Object dropped) { return this; }
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

  /** THIS, AND {@code finalizer} WHEN IT IS LEFT. With a value: the finalizer runs, and the value is this
   * program's. Without one — a handler outside does not go on with the rest of the program this is part of, and
   * unwinds it ({@link K#unwind}: a failure, a choice of nothing, a stream cut short): the finalizer runs then,
   * where it was installed — the handlers between it and the one that leaves still answer it, their states as the
   * path left them. Finalizers run the innermost first, each once for every path that ends or is unwound past it.
   * The finalizer is a program of the same row, made when it is run; its value is dropped. One that fails is left
   * as any program is: what waits beyond it is still unwound, and the failure goes to its handler. Not run: when
   * the rest of the program is kept and never resumed (an {@code Emit.pull} not read to its end), and when the
   * host throws. */
  public final <Q extends R> Comp<A, Q> ensuring(Supplier<? extends Comp<?, ? super Q>> finalizer) {
    return new Bind<>(this, new Machine.Finally<>(finalizer));
  }
}
