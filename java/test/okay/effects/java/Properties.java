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

  // The programs: each polymorphic in its row, and given the evidence of the effects it asks.

  static <R> Comp<R, Integer> count(In<State<Integer>, R> st, int n) {
    if (n == 0) return State.get(st);
    return State.get(st).flatMap(s -> State.put(st, s + 1)).then(() -> count(st, n - 1));
  }

  static <R> Comp<R, Integer> logged(In<State<Integer>, R> st, In<Writer<String>, R> wr, In<Throws<String>, R> th) {
    return State.get(st).flatMap(s ->
        Writer.tell(wr, "was " + s)
            .then(() -> State.put(st, s + 1))
            .then(() -> s > 100 ? Throws.<String, R, Void>raise(th, "too big") : pure(null))
            .map(done -> s * 2));
  }

  static <R> Comp<R, Void> failing(In<State<Integer>, R> st, In<Throws<String>, R> th) {
    return State.put(st, 5).then(() -> Throws.raise(th, "no"));
  }

  static <R> Comp<R, Integer> nested(In<State<Integer>, R> st, int binds) {
    Comp<R, Integer> p = State.get(st);
    for (int i = 0; i < binds; i++) p = p.flatMap(s -> State.put(st, s + 1).map(done -> s + 1));
    return p;
  }

  static <R> Comp<R, Boolean> even(int n) { return n == 0 ? pure(true) : defer(() -> odd(n - 1)); }
  static <R> Comp<R, Boolean> odd(int n) { return n == 0 ? pure(false) : defer(() -> even(n - 1)); }

  static <R> Comp<R, Integer> chosen(In<State<Integer>, R> st, In<Choose, R> ch) {
    return Choose.among(ch, List.of(1, 2)).flatMap(x ->
        State.get(st).flatMap(s -> State.put(st, s + x).map(done -> s + x)));
  }

  static <R> Comp<R, Integer> oddOnly(In<Choose, R> ch) {
    return Choose.among(ch, List.of(2, 4, 5, 7)).flatMap(x -> x % 2 == 0 ? Choose.none(ch) : pure(x));
  }

  static <R> Comp<R, Void> from(In<Emit<Integer>, R> em, int n) {
    return Emit.send(em, n).then(() -> from(em, n + 1));
  }

  static <R> Comp<R, Void> three(In<Emit<Integer>, R> em) { return Emit.sendAll(em, List.of(1, 2, 3)); }

  static <R> Comp<R, Integer> later(In<Async, R> as, ArrayDeque<Runnable> waiting, int n) {
    return Async.await(as, resume -> waiting.push(() -> resume.accept(n)));
  }

  static <R> Comp<R, Integer> after(In<Async, R> as, long millis, int n) {
    return Async.sleep(as, millis).map(done -> n);
  }

  static <R> Comp<R, Integer> answered(In<Async, R> as, int n, int sum) {
    if (n == 0) return pure(sum);
    return Async.<R, Integer>await(as, resume -> resume.accept(1)).flatMap(x -> answered(as, n - 1, sum + x));
  }

  /** an interceptor: every operation on the state seen, a put of more than 2 changed, and all asked again */
  static <R, A> Comp<R, A> audit(In<State<Integer>, R> st, List<String> seen, Comp<R, A> p) {
    return intercept(State.class, st, new Handler<State<Integer>, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(State<Integer> op, K<R, A> k) {
        seen.add(op.toString());
        return switch (op) {
          case State.Put<Integer> put when put.value() > 2 ->
              perform(st, new State.Put<>(2)).flatMap(done -> k.resume(put, done));
          case State.Put<Integer> put -> perform(st, put).flatMap(done -> k.resume(put, done));
          case State.Get<Integer> get -> perform(st, get).flatMap(s -> k.resume(get, s));
        };
      }
    }, p);
  }

  sealed interface Account {}
  record Deposit(int amount) implements Account, Op<Account, Integer> {}

  /** an account kept in a state: the handler of one effect asks the operations of another */
  static <R, A> Comp<R, A> onState(In<State<Integer>, R> st, Body<Account, R, A> body) {
    return handle(Account.class, new Handler<Account, A, A, R>() {
      public Comp<R, A> ret(A a) { return pure(a); }
      public Comp<R, A> on(Account op, K<R, A> k) {
        return switch (op) {
          case Deposit deposit -> State.get(st).flatMap(s ->
              State.put(st, s + deposit.amount()).then(() -> k.resume(deposit, s + deposit.amount())));
        };
      }
    }, body);
  }

  /** the lie the compiler does not let a program tell: a row without an effect that is asked */
  @SuppressWarnings("unchecked") // on purpose: the only way to an operation with no handler
  static <A> Comp<Nil, A> closed(Comp<?, A> open) { return (Comp<Nil, A>) open; }

  public static void main(String[] args) {
    test("a row: handlers take their effects one by one, a program runs when none is left", () -> {
      var got = run(Throws.run(String.class, th ->
          Writer.run(String.class, wr -> State.run(1, st -> logged(st, wr.up(), up(up(th)))))));
      same(got, new Throws.Ok<>(new Writer.Logged<>(List.of("was 1"), new State.Final<>(2, 2))));
    });

    test("the order of handlers is the meaning: state kept or lost at a failure", () -> {
      same(run(State.run(0, st -> Throws.run(String.class, th -> failing(st.up(), th)))),
          new State.Final<>(5, new Throws.Err<>("no")));
      same(run(Throws.run(String.class, th -> State.run(0, st -> failing(st, th.up())))), new Throws.Err<>("no"));
    });

    test("constant stack: a million operations", () ->
        same(run(State.run(0, st -> count(st, 1_000_000))), new State.Final<>(1_000_000, 1_000_000)));

    test("constant stack: a hundred thousand binds nested to the left", () ->
        same(run(State.run(0, st -> nested(st, 100_000))), new State.Final<>(100_000, 100_000)));

    test("mutual tail recursion without end: a million calls", () -> same(run(even(1_000_000)), true));

    test("a continuation taken many times: every path of a choice", () -> {
      List<Pair<Integer, Integer>> pairs = run(Choose.all(ch -> Choose.among(ch, List.of(1, 2, 3)).flatMap(x ->
          Choose.among(ch, List.of(10, 20)).map(y -> new Pair<>(x, y)))));
      same(pairs, List.of(new Pair<>(1, 10), new Pair<>(1, 20), new Pair<>(2, 10), new Pair<>(2, 20),
          new Pair<>(3, 10), new Pair<>(3, 20)));
    });

    test("many times, with state: each path its own state, or one state through all", () -> {
      same(run(Choose.all(ch -> State.run(0, st -> chosen(st, ch.up())))),
          List.of(new State.Final<>(1, 1), new State.Final<>(2, 2)));
      same(run(State.run(0, st -> Choose.all(ch -> chosen(st.up(), ch)))), new State.Final<>(3, List.of(1, 3)));
    });

    test("an endless stream, pulled", () -> {
      same(first(3, Emit.pull(Integer.class, em -> from(em, 0))), List.of(0, 1, 2));
      Iterator<Integer> stream = Emit.pull(em -> from(em, 0));
      for (int i = 0; i < 1_000_000; i++) stream.next();
      same(stream.next(), 1_000_000);
    });

    test("asynchrony: the handler returns, the answer resumes the program later", () -> {
      ArrayDeque<Runnable> waiting = new ArrayDeque<>();
      List<Integer> result = new ArrayList<>();
      Async.start(as -> later(as, waiting, 1).flatMap(a -> later(as, waiting, 2).map(b -> a + b)), result::add);
      same(result, List.of());
      while (!waiting.isEmpty()) waiting.pop().run();
      same(result, List.of(3));
    });

    test("interception and audit: an operation seen, changed, and asked again", () -> {
      List<String> seen = new ArrayList<>();
      same(run(State.run(0, st -> audit(st, seen, count(st, 5)))), new State.Final<>(2, 2));
      same(seen.stream().filter(s -> s.startsWith("Put")).count(), 5L);
    });

    test("delegation: an effect answered by the operations of another", () -> {
      var got = run(State.run(100, st ->
          onState(st, acc -> perform(acc, new Deposit(10)).then(() -> perform(acc, new Deposit(5))))));
      same(got, new State.Final<>(115, 115));
    });

    test("reader: a value of the surroundings, and changed for a part of the program", () ->
        same(run(Reader.run(2, rd -> Reader.ask(rd).flatMap(a ->
            Reader.local(rd, v -> v * 10, Reader.ask(rd)).map(b -> new Pair<>(a, b))))), new Pair<>(2, 20)));

    test("a failure recovered; the first of the choices; fresh numbers; a clock that a test sets", () -> {
      same(run(Throws.recover((String e) -> pure(e.length()), th -> Throws.raise(th, "no"))), 2);
      same(run(Choose.first(ch -> oddOnly(ch))), Optional.of(5));
      same(run(Choose.all(ch -> oddOnly(ch))), List.of(5, 7));
      same(run(Fresh.run(fr -> Fresh.fresh(fr).flatMap(a -> Fresh.fresh(fr).map(b -> new Pair<>(a, b))))),
          new Pair<>(0L, 1L));
      same(run(Clock.at(() -> 42L, cl -> Clock.now(cl))), 42L);
    });

    test("streams: collected, looped over, mapped, filtered, cut short, zipped, merged", () -> {
      same(run(Emit.collect(Integer.class, em -> three(em))), new Emit.Sent<>(List.of(1, 2, 3), null));
      same(run(State.run(0, st ->
          Emit.each((Integer x) -> State.modify(st, s -> s + x), em -> three(em)))).state(), 6);
      // from the inside out: the endless stream, its even values, each doubled, the first three
      var evens = run(Emit.collect(Integer.class, out ->
          Emit.take(out, 3, cut ->
              Emit.map(cut, (Integer x) -> x * 2, doubled ->
                  Emit.filter(doubled, x -> x % 2 == 0, all -> from(all, 0))))));
      same(evens.values(), List.of(0, 4, 8));
      Iterator<Pair<Integer, Integer>> zipped = Emit.pull(em -> Emit.zip(em, a -> from(a, 0), b -> three(b)));
      same(first(9, zipped), List.of(new Pair<>(0, 1), new Pair<>(1, 2), new Pair<>(2, 3)));
      same(first(5, Emit.pull(Integer.class, em -> Emit.merge(em, a -> three(a), b -> from(b, 10)))),
          List.of(1, 10, 2, 11, 3));
    });

    test("asynchrony: both at once, the first of two, all, a sleep, a future", () -> {
      same(Async.runBlocking(as -> Async.both(as, a -> after(a, 30, 1), b -> after(b, 10, 2))), new Pair<>(1, 2));
      same(Async.runBlocking(as -> Async.race(as, a -> after(a, 200, 1), b -> after(b, 10, 2))), 2);
      List<Body<Async, Nil, Integer>> three = List.of(a -> after(a, 20, 1), b -> after(b, 10, 2), c -> after(c, 1, 3));
      same(Async.runBlocking(as -> Async.all(as, three)), List.of(1, 2, 3));
      same(Async.toFuture(as -> after(as, 5, 7).map(x -> x + 1)).get(5, TimeUnit.SECONDS), 8);
      CompletableFuture<Integer> late = CompletableFuture.supplyAsync(() -> 4);
      same(Async.runBlocking(as -> Async.fromFuture(as, () -> late)), new Throws.Ok<>(4));
    });

    test("asynchrony: a million answers given at once take no stack", () ->
        same(Async.runBlocking(as -> answered(as, 1_000_000, 0)), 1_000_000));

    test("no handler: an operation that reached the top is refused by name", () -> {
      Comp<Cons<State<Integer>, Nil>, Integer> open = State.get(In.here());
      try {
        run(closed(open));
        throw new AssertionError("ran");
      } catch (NoHandler e) {
        same(e.op, new State.Get<>());
      }
    });

    test("a thousand random programs: the reference machine's digest", () -> same(Tck.digest(), Tck.DIGEST));

    System.out.println(failed == 0 ? "java: all passed" : "java: " + failed + " FAILED");
    System.exit(failed == 0 ? 0 : 1);
  }
}
