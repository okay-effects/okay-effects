package okay.effects.java.std;

import static okay.effects.java.Effects.*;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.InPlace;
import okay.effects.java.K;
import okay.effects.java.Nil;
import okay.effects.java.Op;

/** EMIT: a stream of values, possibly endless. The producer is a program that sends; what a stream is made into —
 * a list, a loop, another stream, a lazy sequence pulled a value at a time — is the handler's choice. A handler
 * that makes a stream of a stream takes the evidence of the stream it sends to, {@code out}. */
public sealed interface Emit<T> {
  record Send<T>(T value) implements Emit<T>, Op<Emit<T>, Void> {}

  static <T, R> Comp<R, Void> send(In<Emit<T>, R> in, T value) { return perform(in, new Send<>(value)); }

  /** every value of a sequence, which may be lazy and endless */
  static <T, R> Comp<R, Void> sendAll(In<Emit<T>, R> in, Iterable<T> values) {
    return sendAll(in, Lazy.of(values.iterator()));
  }

  private static <T, R> Comp<R, Void> sendAll(In<Emit<T>, R> in, Lazy<T> values) {
    return defer(() -> {
      Lazy.Next<T> next = values.next();
      return next == null ? pure(null) : send(in, next.value()).then(() -> sendAll(in, next.rest()));
    });
  }

  /** the values sent, with the program's own */
  record Sent<T, A>(List<T> values, A value) {}

  static <T, R, A> Comp<R, Sent<T, A>> collect(Body<Emit<T>, R, A> body) {
    return handle(Emit.class, new InPlace<Emit<T>, Chain<T>, A, Sent<T, A>, R>() {
      public Comp<R, Sent<T, A>> ret(Chain<T> sent, A a) { return pure(new Sent<>(Chain.list(sent), a)); }
      public Cell.Answered answer(Cell<Chain<T>> sent, Emit<T> op) {
        return switch (op) {
          case Send<T> send -> { sent.value = new Chain<>(send.value(), sent.value); yield sent.answer(send, null); }
        };
      }
    }, null, body);
  }

  /** the same; {@code of} only names the type of the values, where the compiler cannot find it */
  static <T, R, A> Comp<R, Sent<T, A>> collect(Class<T> of, Body<Emit<T>, R, A> body) { return collect(body); }

  /** {@code f} run for each value, as it is sent */
  static <T, R, A> Comp<R, A> each(Function<T, Comp<R, Void>> f, Body<Emit<T>, R, A> body) {
    return handle(Emit.class, new Handler<Emit<T>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(Emit<T> op, K<R, A> k) {
        return switch (op) {
          case Send<T> send -> f.apply(send.value()).flatMap(done -> k.resume(send, done));
        };
      }
    }, body);
  }

  /** a stream of the values, each through {@code f} */
  static <T, U, R, A> Comp<R, A> map(In<Emit<U>, R> out, Function<T, U> f, Body<Emit<T>, R, A> body) {
    return handle(Emit.class, new Handler<Emit<T>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(Emit<T> op, K<R, A> k) {
        return switch (op) {
          case Send<T> send -> send(out, f.apply(send.value())).flatMap(done -> k.resume(send, done));
        };
      }
    }, body);
  }

  /** a stream of the values that {@code keep} */
  static <T, R, A> Comp<R, A> filter(In<Emit<T>, R> out, Predicate<T> keep, Body<Emit<T>, R, A> body) {
    return handle(Emit.class, new Handler<Emit<T>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(Emit<T> op, K<R, A> k) {
        return switch (op) {
          case Send<T> send -> keep.test(send.value())
              ? send(out, send.value()).flatMap(done -> k.resume(send, done))
              : k.resume(send, null);
        };
      }
    }, body);
  }

  /** a stream of the first {@code n} values; the producer is not resumed after them */
  static <T, R, A> Comp<R, Void> take(In<Emit<T>, R> out, int n, Body<Emit<T>, R, A> body) {
    // the program's answer is a function of how many values are left to take
    Comp<R, Function<Integer, Comp<R, Void>>> taking = handle(
        Emit.class, new Handler<Emit<T>, A, Function<Integer, Comp<R, Void>>, R>() {
          public Comp<R, Function<Integer, Comp<R, Void>>> ret(A a) { return pure(left -> pure(null)); }
          public Comp<R, Function<Integer, Comp<R, Void>>> on(Emit<T> op, K<R, Function<Integer, Comp<R, Void>>> k) {
            return switch (op) {
              case Send<T> send -> pure(left -> left <= 0 ? pure(null) : send(out, send.value()).then(() ->
                  left == 1 ? pure(null) : k.resume(send, null).flatMap(rest -> rest.apply(left - 1))));
            };
          }
        }, body);
    return taking.flatMap(rest -> rest.apply(n));
  }

  /** the values, pulled as they are needed: the producer runs no further than was asked */
  static <T, A> Iterator<T> pull(Body<Emit<T>, Nil, A> body) {
    Lazy<T> values = new Lazy<>(() -> run(handle(Emit.class, new Handler<Emit<T>, A, Lazy.Next<T>, Nil>() {
      public Comp<Nil, Lazy.Next<T>> ret(A a) { return pure(null); }
      public Comp<Nil, Lazy.Next<T>> on(Emit<T> op, K<Nil, Lazy.Next<T>> k) {
        return switch (op) {
          case Send<T> send -> pure(new Lazy.Next<>(send.value(), new Lazy<>(() -> run(k.resume(send, null)))));
        };
      }
    }, body)));
    return new Lazy.Values<>(values);
  }

  /** the same; {@code of} only names the type of the values, where the compiler cannot find it */
  static <T, A> Iterator<T> pull(Class<T> of, Body<Emit<T>, Nil, A> body) { return pull(body); }

  /** two streams, value by value in pairs, as long as both last */
  static <T, U, R, A, B> Comp<R, Void> zip(
      In<Emit<Pair<T, U>>, R> out, Body<Emit<T>, Nil, A> a, Body<Emit<U>, Nil, B> b) {
    return sendAll(out, () -> {
      Iterator<T> first = pull(a);
      Iterator<U> second = pull(b);
      return new Iterator<>() {
        public boolean hasNext() { return first.hasNext() && second.hasNext(); }
        public Pair<T, U> next() { return new Pair<>(first.next(), second.next()); }
      };
    });
  }

  /** two streams in turn, a value of each; then what is left of the longer */
  static <T, R, A, B> Comp<R, Void> merge(In<Emit<T>, R> out, Body<Emit<T>, Nil, A> a, Body<Emit<T>, Nil, B> b) {
    return sendAll(out, () -> new Iterator<>() {
      private Iterator<T> first = pull(a);
      private Iterator<T> second = pull(b);
      public boolean hasNext() { return first.hasNext() || second.hasNext(); }
      public T next() {
        if (!first.hasNext()) return second.next();
        T value = first.next();
        Iterator<T> other = first;
        first = second;
        second = other;
        return value;
      }
    });
  }
}

/** A lazy sequence: a value is made once, when it is first asked for, and kept — so a program resumed twice from
 * the same place is sent the same values twice. */
final class Lazy<T> {
  /** a value and what follows it; {@code null} is the end */
  record Next<T>(T value, Lazy<T> rest) {}

  private Supplier<Next<T>> make;
  private Next<T> made;

  Lazy(Supplier<Next<T>> make) { this.make = make; }

  static <T> Lazy<T> of(Iterator<T> values) {
    return new Lazy<>(() -> values.hasNext() ? new Next<>(values.next(), of(values)) : null);
  }

  Next<T> next() {
    if (make != null) {
      made = make.get();
      make = null;
    }
    return made;
  }

  /** the values from a place on; those passed are not kept */
  static final class Values<T> implements Iterator<T> {
    private Lazy<T> left;

    Values(Lazy<T> from) { left = from; }

    public boolean hasNext() { return left.next() != null; }

    public T next() {
      Next<T> next = left.next();
      if (next == null) throw new NoSuchElementException();
      left = next.rest();
      return next.value();
    }
  }
}
