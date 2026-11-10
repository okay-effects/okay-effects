package okay.algebraic.std;

import static okay.algebraic.Comp.*;

import java.util.Iterator;
import java.util.NoSuchElementException;
import okay.algebraic.Comp;
import okay.algebraic.NoHandler;
import okay.algebraic.Op;
import okay.algebraic.Step;

/** EMIT: a stream, possibly endless — who handles it takes one value and keeps the rest for later. */
public sealed interface Emit<T> {
  record Send<T>(T value) implements Emit<T>, Op<Void> {}

  static <T> Comp<Void> send(T value) { return perform(new Send<>(value)); }

  /** the values sent, pulled as they are needed: a pull is a step of the machine */
  static <T> Iterator<T> stream(Comp<?> p) {
    return new Iterator<>() {
      private Step<?> at = p.step();

      public boolean hasNext() { return at instanceof Step.Asked; }

      public T next() {
        if (!(at instanceof Step.Asked<?, ?> asked)) throw new NoSuchElementException();
        if (!(asked.op() instanceof Send<?> send)) throw new NoHandler(asked.op());
        @SuppressWarnings("unchecked") // the program was written against Emit<T>
        T value = (T) send.value();
        at = asked.k().resume(null).step();
        return value;
      }
    };
  }
}
