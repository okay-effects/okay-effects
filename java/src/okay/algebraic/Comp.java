package okay.algebraic;

import java.util.function.Function;
import java.util.function.Supplier;

/** A computation of an {@code A}. Only data: nothing runs until {@link #step()}. */
public sealed interface Comp<A> {
  /** a value */
  record Done<A>(A value) implements Comp<A> {}
  /** a question: an operation, to be answered */
  record Ask<X>(Op<X> op) implements Comp<X> {}
  /** a computation, and what follows its value */
  record Bind<X, A>(Comp<X> first, Function<X, Comp<A>> next) implements Comp<A> {}
  /** a computation, and a continuation taken earlier put back after it */
  record Plug<X, A>(Comp<X> first, Rest<X, A> rest) implements Comp<A> {}

  static <A> Comp<A> pure(A a) { return new Done<>(a); }

  static <X> Comp<X> perform(Op<X> op) { return new Ask<>(op); }

  /** a computation built when it is reached: a call in tail position takes no stack */
  static <A> Comp<A> defer(Supplier<Comp<A>> p) { return new Bind<>(new Done<>(null), nothing -> p.get()); }

  default <B> Comp<B> flatMap(Function<A, Comp<B>> f) { return new Bind<>(this, f); }

  default <B> Comp<B> map(Function<A, B> f) { return new Bind<>(this, a -> new Done<>(f.apply(a))); }

  /** the computation after this one, whose value is not needed */
  default <B> Comp<B> then(Supplier<Comp<B>> next) { return new Bind<>(this, a -> next.get()); }

  /** THE FOLD: the operations of the handler's effect answered by it, every other one asked again outside. */
  static <E, A, B> Comp<B> handle(Handler<E, A, B> h, Comp<A> c) {
    return defer(() -> switch (c.step()) {
      case Step.Done<A> done -> h.ret(done.value());
      case Step.Asked<?, A> asked -> K.answer(h, asked);
    });
  }

  /** a program with nothing left to ask, run to its value */
  default A run() {
    return switch (step()) {
      case Step.Done<A> done -> done.value();
      case Step.Asked<?, A> asked -> throw new NoHandler(asked.op());
    };
  }

  /** THE MACHINE: the computation run to its next question, in a loop — constant stack, whatever the program.
   * The one method that is not checked by the compiler: Java cannot say that an {@code End}'s X is its A, nor keep
   * the types of a loop's variables in step. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  default Step<A> step() {
    Comp c = this;
    Rest k = new Rest.End<>();
    while (true) {
      switch (c) {
        case Ask ask -> { return new Step.Asked(ask.op(), k); }
        case Bind bind -> { k = new Rest.Then(bind.next(), k); c = bind.first(); }
        case Plug plug -> { k = k instanceof Rest.End ? plug.rest() : new Rest.Both(plug.rest(), k); c = plug.first(); }
        case Done done -> {
          switch (k) {
            case Rest.End end -> { return new Step.Done(done.value()); }
            case Rest.Then then -> { c = (Comp) then.next().apply(done.value()); k = then.rest(); }
            case Rest.Both both -> {
              switch (both.first()) {
                case Rest.End end -> k = both.rest();
                case Rest.Then then -> {
                  c = (Comp) then.next().apply(done.value());
                  k = new Rest.Both(then.rest(), both.rest());
                }
                case Rest.Both inner -> k = new Rest.Both(inner.first(), new Rest.Both(inner.rest(), both.rest()));
              }
            }
          }
        }
      }
    }
  }
}
