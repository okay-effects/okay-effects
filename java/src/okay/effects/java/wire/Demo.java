package okay.effects.java.wire;

import static okay.effects.java.Effects.*;
import static okay.effects.java.wire.Foreign.call;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Handler;
import okay.effects.java.K;
import okay.effects.java.Pure;
import okay.effects.java.std.Async;
import okay.effects.java.std.Emit;
import okay.effects.java.std.Throws;

/** The demonstration every language of this repository has, the same: a module served by a worker, and a host
 * that uses every feature of the wire on it, a line for each. Any host with any worker prints the same lines
 * (wire/expected.txt; the module is described in wire/README.md).
 * {@code java okay.effects.java.wire.Demo worker}, {@code java okay.effects.java.wire.Demo host <worker's command>}. */
public final class Demo {
  private Demo() {}

  /** THE MODULE the worker serves; what it remembers is the worker's, for as long as it lives */
  static Worker.Module module() {
    Served served = new Served();
    Map<String, Worker.Program> functions = new LinkedHashMap<>();
    functions.put("t:add", Served::add);
    functions.put("t:echo", Served::echo);
    functions.put("t:sum", Served::sum);
    functions.put("t:boom", Served::boom);
    functions.put("t:pairs", Served::pairs);
    functions.put("t:quote", Served::quote);
    functions.put("shop:total", Served::total);
    functions.put("t:count", Served::count);
    functions.put("t:numbers", served::numbers);
    functions.put("t:sent", served::sent);
    functions.put("t:dedup", Served::dedup);
    functions.put("t:wait", served::await);
    functions.put("t:open", served::open);
    return new Worker.Module(functions, Map.of("t:counter", Counter::made));
  }

  private static final class Counter implements Worker.Thing {
    private long n;

    private Counter(long n) { this.n = n; }

    static Worker.Thing made(List<Json> args) {
      if (args.size() == 1 && args.get(0) instanceof Json.Int(long n)) return new Counter(n);
      throw new Condition("TypeError", "t:counter: not its arguments");
    }

    public String kind() { return "t.Counter"; }

    public Optional<Json> method(String name, List<Json> args) {
      if (!(name.equals("inc") && args.size() == 1 && args.get(0) instanceof Json.Int(long by))) {
        return Optional.empty();
      }
      n += by;
      return Optional.of(Json.of(n));
    }

    public Optional<Json> attr(String name) { return name.equals("n") ? Optional.of(Json.of(n)) : Optional.empty(); }
  }

  /** The module's functions: each a program of its arguments; any other arguments are refused by name. */
  private static final class Served {
    /** how many values the last {@code t:numbers} has sent */
    private long sent = 0;
    /** those who wait for a name to be opened, in the order they came */
    private final Map<String, List<Consumer<Json>>> waiting = new HashMap<>();

    static Comp<Json, Worker.Row> refused(String name) {
      return fit(Throws.raise(new Condition("TypeError", name + ": not its arguments")));
    }

    static Comp<Json, Worker.Row> add(List<Json> args) {
      return args.size() == 2 && args.get(0) instanceof Json.Int(long a) && args.get(1) instanceof Json.Int(long b)
          ? pure(Json.of(a + b))
          : refused("t:add");
    }

    static Comp<Json, Worker.Row> echo(List<Json> args) {
      return args.size() == 1 ? pure(args.get(0)) : refused("t:echo");
    }

    static Comp<Json, Worker.Row> sum(List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Arr(List<Json> values))) return refused("t:sum");
      return pure(Json.of(values.stream().mapToLong(value -> value instanceof Json.Int(long n) ? n : 0).sum()));
    }

    static Comp<Json, Worker.Row> boom(List<Json> args) {
      return args.isEmpty() ? fit(Throws.raise(new Condition("ValueError", "no"))) : refused("t:boom");
    }

    static Comp<Json, Worker.Row> pairs(List<Json> args) {
      if (!args.isEmpty()) return refused("t:pairs");
      return up(call("choose", Json.of(ints(1, 2)))).map(choice -> times(10, choice));
    }

    static Comp<Json, Worker.Row> quote(List<Json> args) {
      return args.size() == 1
          ? up(call("price_of", args.get(0))).map(price -> times(2, price))
          : refused("t:quote");
    }

    static Comp<Json, Worker.Row> total(List<Json> items) { return fit(up(total(items, 0, 0))); }

    private static Comp<Json, Eff<Foreign, ?>> total(List<Json> items, int i, long sum) {
      if (i == items.size()) return pure(Json.of(sum));
      return call("price_of", items.get(i)).flatMap(answer -> {
        if (!(answer instanceof Json.Int(long price))) throw new IllegalArgumentException("a price: " + answer.text());
        return total(items, i + 1, sum + price);
      });
    }

    static Comp<Json, Worker.Row> count(List<Json> args) {
      return args.size() == 1 ? fit(up(count(args.get(0), 0))) : refused("t:count");
    }

    private static Comp<Json, Eff<Foreign, ?>> count(Json n, long done) {
      return n.equals(Json.of(done)) ? pure(n) : call("tick").then(() -> count(n, done + 1));
    }

    Comp<Json, Worker.Row> numbers(List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Int(long n))) return refused("t:numbers");
      sent = 0;
      return fit(up(up(numbers(1, n))));
    }

    private Comp<Json, Eff<Emit<Json>, ?>> numbers(long next, long last) {
      if (next > last) return pure(Json.NULL);
      return Emit.send(Json.of(next)).then(() -> {
        sent++;
        return numbers(next + 1, last);
      });
    }

    Comp<Json, Worker.Row> sent(List<Json> args) {
      return args.isEmpty() ? defer(() -> pure(Json.of(sent))) : refused("t:sent");
    }

    static Comp<Json, Worker.Row> dedup(List<Json> args) {
      return args.isEmpty() ? dedup(Set.of()) : refused("t:dedup");
    }

    private static Comp<Json, Worker.Row> dedup(Set<Json> seen) {
      return up(up(up(Fed.next()))).flatMap(next -> {
        if (next.isEmpty()) return pure(Json.NULL);
        if (seen.contains(next.get())) return dedup(seen);
        // a new set, the old one kept: the rest of a program is a value, and may be resumed again
        Set<Json> more = new HashSet<>(seen);
        more.add(next.get());
        return up(up(Emit.send(next.get()))).flatMap(sent -> dedup(more));
      });
    }

    Comp<Json, Worker.Row> await(List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Str(String name))) return refused("t:wait");
      Comp<Json, Eff<Async, ?>> woken = Async.await(resume ->
          waiting.computeIfAbsent(name, none -> new ArrayList<>()).add(resume));
      return fit(up(up(up(up(woken)))));
    }

    Comp<Json, Worker.Row> open(List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Str(String name))) return refused("t:open");
      // those woken are answered now, before this call's own answer
      List<Consumer<Json>> woken = Optional.ofNullable(waiting.remove(name)).orElse(List.of());
      woken.forEach(resume -> resume.accept(args.get(0)));
      return pure(Json.of(woken.size()));
    }
  }

  private static Json times(long n, Json x) {
    return switch (x) {
      case Json.Int(long value) -> Json.of(value * n);
      case Json.Dec(double value) -> new Json.Dec(value * n);
      default -> x;
    };
  }

  static List<Json> ints(long... values) { return LongStream.of(values).mapToObj(Json::of).toList(); }

  private static final Map<String, Long> PRICES = Map.of("tea", 3L, "milk", 2L);

  /** this side's answers: every choice taken, a price looked up, a tick counted */
  static <R> Comp<List<Json>, R> answered(Comp<Json, ? super Eff<Foreign, R>> p) {
    return handle(Foreign.class, new Handler<Foreign, Json, List<Json>, R>() {
      public Comp<List<Json>, R> ret(Json a) { return pure(List.of(a)); }
      public Comp<List<Json>, R> on(Foreign op, K<List<Json>, R> k) {
        return switch (op) {
          case Foreign.Call c -> {
            Json arg = c.args().isEmpty() ? Json.NULL : c.args().get(0);
            if (c.name().equals("choose") && arg instanceof Json.Arr(List<Json> values)) {
              yield each(c, values, 0, List.of(), k);
            }
            if (c.name().equals("price_of") && arg instanceof Json.Str(String item) && PRICES.containsKey(item)) {
              yield k.resume(c, Json.of(PRICES.get(item)));
            }
            if (c.name().equals("tick")) yield k.resume(c, Json.NULL);
            throw new IllegalArgumentException("no answer for " + c.name());
          }
        };
      }
    }, p);
  }

  /** the rest of the program resumed with each of the values in turn: what every path found */
  private static <R> Comp<List<Json>, R> each(
      Foreign.Call c, List<Json> values, int i, List<Json> found, K<List<Json>, R> k) {
    if (i == values.size()) return pure(found);
    return k.resume(c, values.get(i)).flatMap(more -> {
      List<Json> all = new ArrayList<>(found);
      all.addAll(more);
      return each(c, values, i + 1, all, k);
    });
  }

  /** the program's value on a far side, or the far side's failure */
  static <A> Throws.Result<Condition, A> attempt(
      Remote far, Comp<A, ? super Eff<Throws<Condition>, Eff<Far, Pure>>> p) {
    return far.run(Throws.run(p));
  }

  /** the same, as a line says it */
  static <A> String on(Remote far, Comp<A, ? super Eff<Throws<Condition>, Eff<Far, Pure>>> p) {
    return switch (attempt(far, p)) {
      case Throws.Ok<Condition, A>(A value) -> value instanceof Json json ? json.text() : String.valueOf(value);
      case Throws.Err<Condition, A>(Condition c) -> c.kind + ": " + c.message;
    };
  }

  /** every answer of a far program, under this side's handler of what it asks */
  private static <R> Comp<Json, R> all(Comp<Json, ? super Eff<Foreign, R>> program) {
    return answered(program).map(Json::of);
  }

  /** every value a stream sends */
  static <R> Comp<Json, R> sent(Comp<Void, ? super Eff<Emit<Json>, R>> stream) {
    return Emit.collect(stream).map(sent -> Json.of(sent.values()));
  }

  /** THE HOST'S SCRIPT: a line for each feature. {@code open} gives a far side: over CBOR and DEFLATE or over JSON
   * lines, with a secret or with none. */
  static List<String> script(BiFunction<Boolean, Optional<String>, Remote> open) {
    List<String> lines = new ArrayList<>();
    try (Remote far = open.apply(false, Optional.empty())) {
      lines.add("pairs " + on(far, all(Far.program("t:pairs", List.of()))));
      List<Json> items = List.of(Json.of("tea"), Json.of("milk"));
      lines.add("total " + on(far, all(Far.program("shop:total", items))));
      lines.add("count " + on(far, all(Far.program("t:count", ints(10000)))));
      lines.add("call " + on(far, Far.call("t:add", Json.of(2), Json.of(3))));
      // an object kept on the far side: its method, its attribute; released, it is refused by name
      if (!(attempt(far, Far.hold("t:counter", Json.of(10))) instanceof Throws.Ok<Condition, Json.Ref>(
          Json.Ref counter))) {
        throw new IllegalStateException("t:counter was not held");
      }
      lines.add("held " + counter.kind() + " " + on(far, Far.method(counter, "inc", Json.of(5))
          .flatMap(after -> Far.attr(counter, "n").map(n -> after.text() + " " + n.text()))));
      String released = on(far, Far.release(counter).then(() -> Far.attr(counter, "n")));
      lines.add("released " + released.split(":")[0]);
      // a value kept: its ref, given to a call, is the value again
      lines.add("value " + on(far, Far.hold("t:echo", Json.of(ints(1, 2, 3))).flatMap(list ->
          Far.call("t:sum", list).flatMap(sum ->
              Far.release(list).map(done -> list.kind() + " " + sum.text())))));
      lines.add("condition " + on(far, Far.call("t:boom")));
      Json odd = Json.of(List.of(Json.of(9007199254740993L), new Json.Dec(Double.NaN), new Json.Dec(3.0),
          new Json.Dec(0.5), Json.obj("a", Json.of(1))));
      lines.add("escapes " + on(far, Far.call("t:echo", odd).map(Escapes::enc)));
      lines.add("stream " + on(far, sent(Far.stream("t:numbers", ints(5), 2))));
      // three taken of a hundred: the far side was two ahead, and was stopped there
      lines.add("cancel " + on(far, sent(Emit.take(3, under(Far.stream("t:numbers", ints(100), 2)))))
          + " " + on(far, Far.call("t:sent")));
      lines.add("duplex " + on(far, sent(Far.stream("t:dedup", List.of(), 2, Emit.sendAll(ints(3, 1, 3, 2, 1))))));
      // the second call is answered while the first still waits
      lines.add("mux " + on(far, up(Far.start("t:wait", Json.of("g"))).flatMap(waits ->
          up(Far.start("t:open", Json.of("g"))).flatMap(opens ->
              Far.result(opens).flatMap(opened ->
                  Far.result(waits).map(waited -> Json.of(List.of(opened, waited))))))));
    }
    try (Remote framed = open.apply(true, Optional.empty())) {
      lines.add("cbor+deflate " + on(framed, Far.call("t:add", Json.of(2), Json.of(3)))
          + " " + on(framed, all(Far.program("t:pairs", List.of())))
          + " " + on(framed, sent(Far.stream("t:numbers", ints(3), 1))));
    }
    try (Remote proven = open.apply(false, Optional.of("a secret of the demonstration"))) {
      lines.add("auth " + on(proven, Far.call("t:add", Json.of(2), Json.of(3))));
    }
    return lines;
  }

  public static void main(String[] args) {
    if (args.length == 1 && args[0].equals("worker")) {
      Worker.serve(module(), System.in, System.out, Optional.ofNullable(System.getenv("OKAY_WIRE_SECRET")));
    } else if (args.length > 1 && args[0].equals("host")) {
      List<String> command = List.of(args).subList(1, args.length);
      script((framed, secret) -> Remote.start(command, framed, framed, secret)).forEach(System.out::println);
    } else {
      System.err.println("usage: Demo worker | Demo host <the worker's command>");
      System.exit(2);
    }
  }
}
