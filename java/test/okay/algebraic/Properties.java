package okay.algebraic;

import static okay.algebraic.Comp.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import okay.algebraic.std.Async;
import okay.algebraic.std.Choose;
import okay.algebraic.std.Emit;
import okay.algebraic.std.State;
import okay.algebraic.std.Throws;
import okay.algebraic.std.Writer;

/** What the model promises, each promise a test. Run: {@code java -cp out okay.algebraic.Properties}. */
final class Properties {
  static int failed = 0;

  static void test(String name, Runnable body) {
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

  static Comp<Integer> count(int n) {
    if (n == 0) return State.get();
    return State.<Integer>get().flatMap(s -> State.put(s + 1)).then(() -> count(n - 1));
  }

  static Comp<Boolean> even(int n) { return n == 0 ? pure(true) : defer(() -> odd(n - 1)); }
  static Comp<Boolean> odd(int n) { return n == 0 ? pure(false) : defer(() -> even(n - 1)); }

  static Comp<Void> from(int n) { return Emit.send(n).then(() -> from(n + 1)); }

  sealed interface Account {}
  record Deposit(int amount) implements Account, Op<Integer> {}

  record Pair(int x, int y) {}

  public static void main(String[] args) {
    test("handlers take their effects one by one, a program runs when none is left", () -> {
      Comp<Integer> p = State.<Integer>get().flatMap(s ->
          Writer.tell("was " + s).then(() -> State.put(s + 1)).map(done -> s * 2));
      var got = Throws.<String, Writer.Logged<String, State.Final<Integer, Integer>>>run(
          Writer.run(State.run(1, p))).run();
      same(got, new Throws.Ok<>(new Writer.Logged<>(List.of("was 1"), new State.Final<>(2, 2))));
    });

    test("the order of handlers is the meaning: state kept or lost at a failure", () -> {
      Comp<Integer> p = State.put(5).then(() -> Throws.raise("no"));
      same(State.run(0, Throws.<String, Integer>run(p)).run(), new State.Final<>(5, new Throws.Err<>("no")));
      same(Throws.<String, State.Final<Integer, Integer>>run(State.run(0, p)).run(), new Throws.Err<>("no"));
    });

    test("constant stack: a million operations", () ->
        same(State.run(0, count(1_000_000)).run(), new State.Final<>(1_000_000, 1_000_000)));

    test("constant stack: a hundred thousand binds nested to the left", () -> {
      Comp<Integer> p = State.get();
      for (int i = 0; i < 100_000; i++) p = p.flatMap(s -> State.put(s + 1).map(done -> s + 1));
      same(State.run(0, p).run(), new State.Final<>(100_000, 100_000));
    });

    test("mutual tail recursion without end: a million calls", () -> same(even(1_000_000).run(), true));

    test("a continuation taken many times: every path of a choice", () -> {
      Comp<Pair> p = Choose.among(List.of(1, 2, 3)).flatMap(x -> Choose.among(List.of(10, 20)).map(y -> new Pair(x, y)));
      same(Choose.all(p).run(), List.of(
          new Pair(1, 10), new Pair(1, 20), new Pair(2, 10), new Pair(2, 20), new Pair(3, 10), new Pair(3, 20)));
    });

    test("many times, with state: each path its own state, or one state through all", () -> {
      Comp<Integer> p = Choose.among(List.of(1, 2)).flatMap(x ->
          State.<Integer>get().flatMap(s -> State.put(s + x).map(done -> s + x)));
      same(Choose.all(State.run(0, p)).run(), List.of(new State.Final<>(1, 1), new State.Final<>(2, 2)));
      same(State.run(0, Choose.all(p)).run(), new State.Final<>(3, List.of(1, 3)));
    });

    test("an endless stream, pulled", () -> {
      Iterator<Integer> stream = Emit.stream(from(0));
      same(List.of(stream.next(), stream.next(), stream.next()), List.of(0, 1, 2));
      for (int i = 3; i < 1_000_000; i++) stream.next();
      same(stream.next(), 1_000_000);
    });

    test("asynchrony: the handler returns, the answer resumes the program later", () -> {
      ArrayDeque<Runnable> waiting = new ArrayDeque<>();
      Comp<Integer> one = Async.await(resume -> waiting.add(() -> resume.accept(1)));
      Comp<Integer> two = Async.await(resume -> waiting.add(() -> resume.accept(2)));
      List<Integer> result = new ArrayList<>();
      Async.start(one.flatMap(a -> two.map(b -> a + b)), result::add);
      same(result, List.of());
      while (!waiting.isEmpty()) waiting.poll().run();
      same(result, List.of(3));
    });

    test("interception and audit: an operation seen, changed, and asked again", () -> {
      List<String> seen = new ArrayList<>();
      Handler<State<Integer>, Integer, Integer> audit = Handler.of(
          State.class,
          Comp::pure,
          (op, k) -> {
            seen.add(op.toString());
            return switch (op) {
              case State.Put<Integer> put when put.value() > 2 -> State.put(2).flatMap(done -> k.resume(put, done));
              default -> k.pass();
            };
          });
      same(State.run(0, handle(audit, count(5))).run(), new State.Final<>(2, 2));
      same(seen.stream().filter(s -> s.startsWith("Put")).count(), 5L);
    });

    test("delegation: an effect answered by the operations of another", () -> {
      Handler<Account, Integer, Integer> onState = Handler.of(
          Account.class,
          Comp::pure,
          (op, k) -> switch (op) {
            case Deposit deposit -> State.<Integer>get().flatMap(s ->
                State.put(s + deposit.amount()).then(() -> k.resume(deposit, s + deposit.amount())));
          });
      Comp<Integer> p = perform(new Deposit(10)).then(() -> perform(new Deposit(5)));
      same(State.run(100, handle(onState, p)).run(), new State.Final<>(115, 115));
    });

    test("no handler: an operation that reached the top is refused by name", () -> {
      try {
        State.<Integer>get().run();
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
