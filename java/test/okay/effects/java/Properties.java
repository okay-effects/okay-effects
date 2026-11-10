package okay.effects.java;

import static okay.effects.java.Effects.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okay.effects.java.std.Async;
import okay.effects.java.std.Choose;
import okay.effects.java.std.Clock;
import okay.effects.java.std.Emit;
import okay.effects.java.std.Fresh;
import okay.effects.java.std.Pair;
import okay.effects.java.std.Reader;
import okay.effects.java.std.State;
import okay.effects.java.std.Throws;
import okay.effects.java.std.Writer;

/** What the model promises, each promise a test. Run: {@code java -cp out okay.effects.java.Properties}. What the
 * compiler promises — no program with an effect left runs — is in {@code negative/}. */
final class Properties {
  static int failed = 0;

  interface Check {
    void run() throws Exception;
  }

  static void test(String name, Check body) {
    try {
      body.run();
      System.out.println("  + " + name);
    } catch (Throwable t) {
      failed++;
      System.out.println("==> X " + name + ": " + t);
    }
  }

  static void same(Object got, Object expected) {
    if (!Objects.equals(got, expected)) throw new AssertionError("expected " + expected + ", got " + got);
  }

  /** the first {@code n} values of a stream */
  static <T> List<T> first(int n, Iterator<T> stream) {
    List<T> values = new ArrayList<>();
    while (values.size() < n && stream.hasNext()) values.add(stream.next());
    return values;
  }

  // The programs: the type of each is the row of the effects it asks, in the order of the handlers it is run under.

  static Comp<Integer, Eff<State<Integer>, ?>> count(int n) {
    if (n == 0) return State.get();
    return State.<Integer>get().flatMap(s -> State.put(s + 1)).then(() -> count(n - 1));
  }

  static Comp<Integer, Eff<State<Integer>, Eff<Writer<String>, Eff<Throws<String>, ?>>>> logged() {
    return State.<Integer>get().flatMap(s ->
        up(Writer.tell("was " + s)).flatMap(told ->
            State.put(s + 1).flatMap(put -> s > 100 ? up(up(Throws.raise("too big"))) : pure(s * 2))));
  }

  static Comp<Integer, Eff<State<Integer>, ?>> nested(int binds) {
    Comp<Integer, Eff<State<Integer>, ?>> p = State.get();
    for (int i = 0; i < binds; i++) p = p.flatMap(s -> State.put(s + 1).map(done -> s + 1));
    return p;
  }

  static <R> Comp<Boolean, R> even(int n) { return n == 0 ? pure(true) : defer(() -> odd(n - 1)); }
  static <R> Comp<Boolean, R> odd(int n) { return n == 0 ? pure(false) : defer(() -> even(n - 1)); }

  static Comp<Integer, Eff<State<Integer>, ?>> add(int x) {
    return State.<Integer>get().flatMap(s -> State.put(s + x).map(done -> s + x));
  }

  static Comp<Integer, Eff<Choose, ?>> oddOnly() {
    return Choose.among(List.of(2, 4, 5, 7)).flatMap(x -> x % 2 == 0 ? Choose.none() : pure(x));
  }

  static Comp<Void, Eff<Emit<Integer>, ?>> from(int n) { return Emit.send(n).then(() -> from(n + 1)); }

  static Comp<Void, Eff<Emit<Integer>, ?>> three() { return Emit.sendAll(List.of(1, 2, 3)); }

  static Comp<Integer, Eff<Async, ?>> later(ArrayDeque<Runnable> waiting, int n) {
    return Async.await(resume -> waiting.push(() -> resume.accept(n)));
  }

  static Comp<Integer, Eff<Async, ?>> after(long millis, int n) { return Async.sleep(millis).map(done -> n); }

  static Comp<Integer, Eff<Async, ?>> answered(int n, int sum) {
    if (n == 0) return pure(sum);
    return Async.<Integer>await(resume -> resume.accept(1)).flatMap(x -> answered(n - 1, sum + x));
  }

  /** an interceptor at the state's place in the row — a handler of the program's first effect, put over the state
   * ({@code under}): every operation on the state seen, a put of more than 2 changed, and all asked again */
  static <A, T> Comp<A, Eff<State<Integer>, T>> audit(List<String> seen, Comp<A, ? super Eff<State<Integer>, T>> p) {
    return handle(State.class, new Handler<State<Integer>, A, A, Eff<State<Integer>, T>>() {
      public Comp<A, Eff<State<Integer>, T>> ret(A a) { return pure(a); }
      public Comp<A, Eff<State<Integer>, T>> on(State<Integer> op, K<A, Eff<State<Integer>, T>> k) {
        seen.add(op.toString());
        return switch (op) {
          case State.Put<Integer> put when put.value() > 2 ->
              perform(new State.Put<>(2)).flatMap(done -> k.resume(put, done));
          case State.Put<Integer> put -> perform(put).flatMap(done -> k.resume(put, done));
          case State.Get<Integer> get -> perform(get).flatMap(s -> k.resume(get, s));
        };
      }
    }, under(p));
  }

  sealed interface Account {}
  record Deposit(int amount) implements Account, Op<Account, Integer> {}

  /** an account kept in a state: the handler of one effect asks the operations of another, the next in the row */
  static <A, R extends Eff<State<Integer>, ?>> Comp<A, R> onState(Comp<A, ? super Eff<Account, R>> p) {
    return handle(Account.class, new Handler<Account, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Account op, K<A, R> k) {
        return switch (op) {
          case Deposit deposit -> State.<Integer>get().flatMap(s ->
              State.put(s + deposit.amount()).then(() -> k.resume(deposit, s + deposit.amount())));
        };
      }
    }, p);
  }

  // Two effects of one class are two places in the row: an operation goes to the one its type says.

  static Comp<String, Eff<State<String>, Eff<State<Integer>, ?>>> twoStates() {
    return State.<String>modify(s -> s + "b").flatMap(done ->
        up(State.<Integer>modify(n -> n + 1)).flatMap(more ->
            up(State.<Integer>get()).flatMap(n -> State.<String>get().map(s -> s + n))));
  }

  static Comp<Integer, Eff<State<Integer>, Eff<Reader<Integer>, ?>>> stateOverReader() {
    return State.<Integer>get().flatMap(s ->
        up(Reader.<Integer>ask()).flatMap(v -> State.put(s + v).map(done -> s + v)));
  }

  /** a choice two places out, asked under a marker: the rest of the program, taken there, has the marker in it */
  static Comp<Integer, Eff<State<String>, Eff<State<Integer>, Eff<Choose, ?>>>> chosenAcross() {
    Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> inner = up(Choose.among(List.of(1, 2))).flatMap(x -> add(x));
    return up(inner).flatMap(v -> State.<String>modify(s -> s + v).map(done -> v));
  }

  static Comp<String, Eff<Reader<String>, Eff<Reader<Integer>, ?>>> twoReaders() {
    return up(Reader.<Integer>ask()).flatMap(n -> Reader.<String>ask().map(s -> s + n));
  }

  /** an interceptor found by the class: every fresh number that passes is seen, asked again, and a hundred more */
  static <A, R extends Eff<Fresh, ?>> Comp<A, R> hundred(List<String> seen, Comp<A, ? super R> p) {
    return intercept(Fresh.class, new Handler<Fresh, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Fresh op, K<A, R> k) {
        seen.add(op.toString());
        return switch (op) {
          case Fresh.Next next -> perform(next).flatMap(n -> k.resume(next, n + 100));
        };
      }
    }, p);
  }

  static Comp<Pair<Long, Long>, Eff<Fresh, Eff<Fresh, ?>>> twoFresh() {
    return Fresh.fresh().flatMap(a -> up(Fresh.fresh()).map(b -> new Pair<>(a, b)));
  }

  /** the rest of a program that stopped at a question, resumed with the answer */
  @SuppressWarnings("unchecked") // the answer is given as the test knows the operation asks: an Integer for a Get
  static <X, A, R> Step<A, R> resumed(Step<A, R> at, Object answer) {
    return step(((Step.Asked<X, A, R>) at).k().apply((X) answer));
  }

  /** the lie the compiler does not let a program tell: a row without an effect that is asked */
  @SuppressWarnings("unchecked") // on purpose: the only way to an operation with no handler
  static <A> Comp<A, Pure> closed(Comp<A, ?> open) { return (Comp<A, Pure>) open; }

  public static void main(String[] args) {
    test("a row: handlers take their effects one by one, a program runs when none is left", () -> {
      var got = run(Throws.run(Writer.run(State.run(1, logged()))));
      same(got, new Throws.Ok<>(new Writer.Logged<>(List.of("was 1"), new State.Final<>(2, 2))));
    });

    test("the order of handlers is the meaning: state kept or lost at a failure", () -> {
      same(run(State.run(0, Throws.run(up(State.put(5)).flatMap(put -> Throws.raise("no"))))),
          new State.Final<>(5, new Throws.Err<>("no")));
      same(run(Throws.run(State.run(0, State.put(5).flatMap(put -> up(Throws.raise("no")))))), new Throws.Err<>("no"));
    });

    test("constant stack: a million operations", () ->
        same(run(State.run(0, count(1_000_000))), new State.Final<>(1_000_000, 1_000_000)));

    test("constant stack: a hundred thousand binds nested to the left", () ->
        same(run(State.run(0, nested(100_000))), new State.Final<>(100_000, 100_000)));

    test("mutual tail recursion without end: a million calls", () -> same(run(even(1_000_000)), true));

    test("a continuation taken many times: every path of a choice", () -> {
      List<Pair<Integer, Integer>> pairs = run(Choose.all(Choose.among(List.of(1, 2, 3)).flatMap(x ->
          Choose.among(List.of(10, 20)).map(y -> new Pair<>(x, y)))));
      same(pairs, List.of(new Pair<>(1, 10), new Pair<>(1, 20), new Pair<>(2, 10), new Pair<>(2, 20),
          new Pair<>(3, 10), new Pair<>(3, 20)));
    });

    test("many times, with state: each path its own state, or one state through all", () -> {
      same(run(Choose.all(State.run(0, up(Choose.among(List.of(1, 2))).flatMap(x -> add(x))))),
          List.of(new State.Final<>(1, 1), new State.Final<>(2, 2)));
      same(run(State.run(0, Choose.all(Choose.among(List.of(1, 2)).flatMap(x -> up(add(x)))))),
          new State.Final<>(3, List.of(1, 3)));
    });

    test("an endless stream, pulled", () -> {
      same(first(3, Emit.pull(from(0))), List.of(0, 1, 2));
      Iterator<Integer> stream = Emit.pull(from(0));
      for (int i = 0; i < 1_000_000; i++) stream.next();
      same(stream.next(), 1_000_000);
    });

    test("asynchrony: the handler returns, the answer resumes the program later", () -> {
      ArrayDeque<Runnable> waiting = new ArrayDeque<>();
      List<Integer> result = new ArrayList<>();
      Async.start(later(waiting, 1).flatMap(a -> later(waiting, 2).map(b -> a + b)), result::add);
      same(result, List.of());
      while (!waiting.isEmpty()) waiting.pop().run();
      same(result, List.of(3));
    });

    test("interception and audit: an operation seen, changed, and asked again", () -> {
      List<String> seen = new ArrayList<>();
      same(run(State.run(0, audit(seen, count(5)))), new State.Final<>(2, 2));
      same(seen.stream().filter(s -> s.startsWith("Put")).count(), 5L);
    });

    test("delegation: an effect answered by the operations of another", () -> {
      var got = run(State.run(100, onState(perform(new Deposit(10)).then(() -> perform(new Deposit(5))))));
      same(got, new State.Final<>(115, 115));
    });

    test("reader: a value of the surroundings, and changed for a part of the program", () ->
        same(run(Reader.run(2, Reader.<Integer>ask().flatMap(a ->
            Reader.local(v -> v * 10, Reader.<Integer>ask()).map(b -> new Pair<>(a, b))))), new Pair<>(2, 20)));

    test("a failure recovered; the first of the choices; fresh numbers; a clock that a test sets", () -> {
      same(run(Throws.recover(e -> pure(e.length()), Throws.raise("no"))), 2);
      same(run(Choose.first(oddOnly())), Optional.of(5));
      same(run(Choose.all(oddOnly())), List.of(5, 7));
      same(run(Fresh.run(Fresh.fresh().flatMap(a -> Fresh.fresh().map(b -> new Pair<>(a, b))))), new Pair<>(0L, 1L));
      same(run(Clock.at(() -> 42L, Clock.now())), 42L);
    });

    test("streams: collected, looped over, mapped, filtered, cut short, zipped, merged", () -> {
      same(run(Emit.collect(three())), new Emit.Sent<>(List.of(1, 2, 3), null));
      same(run(State.run(0, Emit.each(x -> State.modify(s -> s + x), three()))).state(), 6);
      // from the inside out: the endless stream, its even values, each doubled, the first three
      var evens = run(Emit.collect(Emit.take(3, Emit.map(x -> x * 2, Emit.filter(x -> x % 2 == 0, from(0))))));
      same(evens.values(), List.of(0, 4, 8));
      Iterator<Pair<Integer, Integer>> zipped = Emit.pull(Emit.zip(from(0), three()));
      same(first(9, zipped), List.of(new Pair<>(0, 1), new Pair<>(1, 2), new Pair<>(2, 3)));
      same(first(5, Emit.pull(Emit.merge(three(), from(10)))), List.of(1, 10, 2, 11, 3));
    });

    test("asynchrony: both at once, the first of two, all, a sleep, a future", () -> {
      same(Async.runBlocking(Async.both(after(30, 1), after(10, 2))), new Pair<>(1, 2));
      same(Async.runBlocking(Async.race(after(200, 1), after(10, 2))), 2);
      same(Async.runBlocking(Async.all(List.of(after(20, 1), after(10, 2), after(1, 3)))), List.of(1, 2, 3));
      same(Async.toFuture(after(5, 7).map(x -> x + 1)).get(5, TimeUnit.SECONDS), 8);
      CompletableFuture<Integer> late = CompletableFuture.supplyAsync(() -> 4);
      same(Async.runBlocking(Async.fromFuture(() -> late)), new Throws.Ok<>(4));
    });

    test("asynchrony: a million answers given at once take no stack", () ->
        same(Async.runBlocking(answered(1_000_000, 0)), 1_000_000));

    test("no handler: an operation that reached the top is refused by name", () -> {
      Comp<Integer, Eff<State<Integer>, ?>> open = State.get();
      try {
        run(closed(open));
        throw new AssertionError("ran");
      } catch (NoHandler e) {
        same(e.op, new State.Get<>());
      }
    });

    test("two handlers of one class: an operation goes to the one its place in the row says", () -> {
      same(run(Reader.run(1, Reader.run("s", up(Reader.<Integer>ask()).map(x -> x + 1)))), 2);
      same(run(Reader.run(1, Reader.run("s", up(Reader.<Integer>ask().map(x -> x + 1))))), 2);
      same(run(State.run(1, State.run("a", twoStates()))), new State.Final<>(2, new State.Final<>("ab", "ab2")));
    });

    test("two places up, past a handler of the operation's own class", () -> {
      same(run(Reader.run(7, Reader.run("s", Reader.run(1.5, up(up(Reader.<Integer>ask())))))), 7);
      same(run(Reader.run(7, Reader.run("s", Reader.run(1.5, up(up(Reader.<Integer>ask().map(x -> x + 1))))))), 8);
      same(run(Reader.run(7, Reader.run("s", up(Reader.run(1.5, up(Reader.<Integer>ask().map(x -> x + 1))))))), 8);
    });

    test("under: a handler put between the first effect and the rest", () -> {
      same(run(Reader.run(5, Reader.run("s", State.run(0, under(stateOverReader()))))), new State.Final<>(5, 5));
      same(run(Reader.run(5, Reader.run("s", Reader.run(1.5, State.run(0, under(under(stateOverReader()))))))),
          new State.Final<>(5, 5));
      same(run(Reader.run(5, State.run(0, Reader.run("s", up(stateOverReader()))))), new State.Final<>(5, 5));
    });

    test("constant stack: a million operations under a marker", () ->
        same(run(State.run(0, Reader.run("s", up(count(1_000_000))))), new State.Final<>(1_000_000, 1_000_000)));

    test("a continuation resumed twice across a marker: each path its own states", () ->
        same(run(Choose.all(State.run(0, State.run("", chosenAcross())))), List.of(
            new State.Final<>(1, new State.Final<>("1", 1)), new State.Final<>(2, new State.Final<>("2", 2)))));

    test("a question no handler answers, asked under a marker: the rest resumed with the answer", () -> {
      Step<Integer, ?> at = step(up(State.<Integer>get().flatMap(s -> State.put(s + 1).map(done -> s))));
      at = resumed(at, 4);
      same(at instanceof Step.Asked<?, ?, ?> asked ? asked.op() : null, new State.Put<>(5));
      same(resumed(at, null), new Step.Done<>(4));
    });

    test("an interceptor between two handlers of one class", () -> {
      // at a place in the row: the inner reader's asks changed, the outer one's pass
      same(run(Reader.run(1, Reader.run("s", Reader.local(s -> s + "!", twoReaders())))), "s!1");
      same(run(Reader.run(1, Reader.local(n -> n * 10, Reader.run("s", twoReaders())))), "s10");
      // by the class: what goes to the outer handler passes the interceptor, what goes to the inner does not
      List<String> seen = new ArrayList<>();
      same(run(Fresh.run(hundred(seen, Fresh.run(twoFresh())))), new Pair<>(0L, 100L));
      same(seen, List.of("Next[]"));
    });

    test("a thousand random programs: the reference machine's digest", () -> same(Tck.digest(), Tck.DIGEST));

    System.out.println(failed == 0 ? "java: all passed" : "java: " + failed + " FAILED");
    System.exit(failed == 0 ? 0 : 1);
  }
}
