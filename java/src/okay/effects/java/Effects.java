package okay.effects.java;

import java.util.function.Supplier;

/** The words of the model: {@code import static okay.effects.java.Effects.*}. */
public final class Effects {
  private Effects() {}

  /** a value: it asks nothing, and fits any row */
  public static <A, R> Comp<A, R> pure(A a) { return new Comp.Done<>(a); }

  /** an operation asked: its effect is the first of the row, whatever the rest */
  public static <E, X> Comp<X, Eff<E, ?>> perform(Op<E, X> op) { return new Comp.Ask<>(op); }

  /** the computation, under one more handler: its effects are one step further */
  public static <A, R> Comp<A, Eff<?, ? extends R>> up(Comp<A, R> c) { return retyped(c); }

  /** the computation, with one more handler under its first effect: the rest of its effects are one step further */
  public static <A, E, R> Comp<A, Eff<E, ? extends Eff<?, ? extends R>>> under(Comp<A, ? super Eff<E, R>> c) {
    return retyped(c);
  }

  /** the same computation, where exactly the row {@code Q} is wanted — a declared type; {@code Q} has its effects */
  public static <A, Q> Comp<A, Q> fit(Comp<A, ? super Q> c) { return retyped(c); }

  /** the one cast of `up`, `under` and `fit`, whose signatures say which rows are allowed */
  @SuppressWarnings("unchecked") // a row is only a type: no value of a computation depends on it
  private static <A, R> Comp<A, R> retyped(Comp<A, ?> c) { return (Comp<A, R>) c; }

  /** a computation built when it is reached: a call in tail position takes no stack */
  public static <A, R> Comp<A, R> defer(Supplier<? extends Comp<A, ? super R>> p) {
    return new Comp.Bind<>(new Comp.Done<>(null), nothing -> p.get());
  }

  /** the operations of {@code F}, the first effect of the program's row, answered by {@code h}; every other one
   * passes outside. {@code F} leaves the row: {@code R} is what is under it. {@code effect} is the class of the
   * operations of {@code F}: how an operation finds its handler. */
  public static <F, A, B, R> Comp<B, R> handle(
      Class<? super F> effect, Handler<F, A, B, R> h, Comp<A, ? super Eff<F, R>> p) {
    return new Comp.Handle<>(new Machine.Mark(effect, h, null), null, p);
  }

  /** the same, in place, from the state {@code s0} */
  public static <F, S, A, B, R> Comp<B, R> handle(
      Class<? super F> effect, InPlace<F, S, A, B, R> h, S s0, Comp<A, ? super Eff<F, R>> p) {
    return new Comp.Handle<>(new Machine.Mark(effect, null, h), s0, p);
  }

  /** {@code handle}, the row kept as it is: a clause may ask the operation again, and the next handler out
   * answers — interception, audit, delegation. That the effect is in {@code R} is checked where the clause asks. */
  public static <F, A, B, R> Comp<B, R> intercept(
      Class<? super F> effect, Handler<F, A, B, R> h, Comp<A, ? super R> p) {
    return new Comp.Handle<>(new Machine.Mark(effect, h, null), null, p);
  }

  public static <F, S, A, B, R> Comp<B, R> intercept(
      Class<? super F> effect, InPlace<F, S, A, B, R> h, S s0, Comp<A, ? super R> p) {
    return new Comp.Handle<>(new Machine.Mark(effect, null, h), s0, p);
  }

  /** a computation run to its next question that no handler in it answers */
  public static <A, R> Step<A, R> step(Comp<A, R> p) { return Machine.run(p); }

  /** a program with nothing left to ask, run to its value */
  public static <A> A run(Comp<A, ? super Pure> p) {
    return switch (step(p)) {
      case Step.Done<A, ?> done -> done.value();
      case Step.Asked<?, A, ?> asked -> throw new NoHandler(asked.op());
    };
  }
}
