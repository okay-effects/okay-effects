package okay.effects.java;

import java.util.function.Supplier;

/** The words of the model: {@code import static okay.effects.java.Effects.*}. */
public final class Effects {
  private Effects() {}

  public static <R, A> Comp<R, A> pure(A a) { return new Comp.Done<>(a); }

  /** an operation asked; the evidence says its effect is in the row */
  public static <E, R, X> Comp<R, X> perform(In<E, R> in, Op<E, X> op) { return new Comp.Ask<>(op); }

  /** {@code in.up()}, for more than one step — {@code up(up(in))}: a chain of calls loses the types, nested calls
   * keep them */
  public static <E, F, R> In<E, Cons<F, R>> up(In<E, R> in) { return in.up(); }

  /** a computation built when it is reached: a call in tail position takes no stack */
  public static <R, A> Comp<R, A> defer(Supplier<Comp<R, A>> p) {
    return new Comp.Bind<>(new Comp.Done<>(null), nothing -> p.get());
  }

  /** the operations of {@code F} answered by {@code h}, every other one passing outside; {@code F} leaves the row.
   * {@code effect} is the class of the operations of {@code F}: how an operation finds its handler. */
  public static <F, R, A, B> Comp<R, B> handle(Class<? super F> effect, Handler<F, A, B, R> h, Body<F, R, A> body) {
    return new Comp.Handle<>(new Machine.Mark(effect, h, null), null, body.apply(In.here()));
  }

  /** the same, in place, from the state {@code s0} */
  public static <F, S, R, A, B> Comp<R, B> handle(
      Class<? super F> effect, InPlace<F, S, A, B, R> h, S s0, Body<F, R, A> body) {
    return new Comp.Handle<>(new Machine.Mark(effect, null, h), s0, body.apply(In.here()));
  }

  /** {@code handle}, the effect kept in the row: a clause may ask the operation again, and the next handler out
   * answers — interception, audit, delegation. */
  public static <F, R, A, B> Comp<R, B> intercept(
      Class<? super F> effect, In<F, R> in, Handler<F, A, B, R> h, Comp<R, A> p) {
    return new Comp.Handle<>(new Machine.Mark(effect, h, null), null, p);
  }

  public static <F, S, R, A, B> Comp<R, B> intercept(
      Class<? super F> effect, In<F, R> in, InPlace<F, S, A, B, R> h, S s0, Comp<R, A> p) {
    return new Comp.Handle<>(new Machine.Mark(effect, null, h), s0, p);
  }

  /** a computation run to its next question that no handler in it answers */
  public static <R, A> Step<R, A> step(Comp<R, A> p) { return Machine.run(p); }

  /** a program with nothing left to ask, run to its value */
  public static <A> A run(Comp<Nil, A> p) {
    return switch (step(p)) {
      case Step.Done<Nil, A> done -> done.value();
      case Step.Asked<Nil, ?, A> asked -> throw new NoHandler(asked.op());
    };
  }
}
