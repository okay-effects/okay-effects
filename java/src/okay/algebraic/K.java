package okay.algebraic;

import java.util.function.Function;

/** The rest of a handled program, waiting for the answer of the operation it asked. */
public final class K<B> {
  private final Op<?> asked;
  private final Function<Object, Comp<B>> next;

  private K(Op<?> asked, Function<Object, Comp<B>> next) { this.asked = asked; this.next = next; }

  /** the rest resumed with the answer; the operation is the witness of the answer's type:
   * {@code case State.Get<S> get -> k.resume(get, state)} */
  public <X> Comp<B> resume(Op<X> op, X answer) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return next.apply(answer);
  }

  /** the operation asked again outside, the rest resumed with what is answered there: interception, audit */
  @SuppressWarnings("unchecked")
  public Comp<B> pass() { return new Comp.Bind<>(new Comp.Ask<>((Op<Object>) asked), next); }

  /** The casts of the kernel, and why they hold: at run time an effect is the class of its operations — an
   * operation of the handler's class is an {@code E}; the answer given to {@code next} is the asked operation's,
   * which {@link #resume} checks by the witness. */
  @SuppressWarnings("unchecked")
  static <E, A, B, X> Comp<B> answer(Handler<E, A, B> h, Step.Asked<X, A> asked) {
    Function<Object, Comp<B>> next = x -> Comp.handle(h, asked.k().resume((X) x));
    K<B> k = new K<>(asked.op(), next);
    return h.effect().isInstance(asked.op()) ? h.on((E) asked.op(), k) : k.pass();
  }
}
