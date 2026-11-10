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
import java.util.function.Function;
import java.util.stream.LongStream;
import okay.effects.java.Body;
import okay.effects.java.Comp;
import okay.effects.java.Cons;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.K;
import okay.effects.java.Nil;
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

    static <R> Comp<R, Json> refused(Worker.Row<R> in, String name) {
      return Throws.raise(in.fails(), new Condition("TypeError", name + ": not its arguments"));
    }

    static <R> Comp<R, Json> add(Worker.Row<R> in, List<Json> args) {
      return args.size() == 2 && args.get(0) instanceof Json.Int(long a) && args.get(1) instanceof Json.Int(long b)
          ? pure(Json.of(a + b))
          : refused(in, "t:add");
    }

    static <R> Comp<R, Json> echo(Worker.Row<R> in, List<Json> args) {
      return args.size() == 1 ? pure(args.get(0)) : refused(in, "t:echo");
    }

    static <R> Comp<R, Json> sum(Worker.Row<R> in, List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Arr(List<Json> values))) return refused(in, "t:sum");
      return pure(Json.of(values.stream().mapToLong(value -> value instanceof Json.Int(long n) ? n : 0).sum()));
    }

    static <R> Comp<R, Json> boom(Worker.Row<R> in, List<Json> args) {
      return args.isEmpty() ? Throws.raise(in.fails(), new Condition("ValueError", "no")) : refused(in, "t:boom");
    }

    static <R> Comp<R, Json> pairs(Worker.Row<R> in, List<Json> args) {
      if (!args.isEmpty()) return refused(in, "t:pairs");
      return call(in.host(), "choose", Json.of(ints(1, 2))).map(choice -> times(10, choice));
    }

    static <R> Comp<R, Json> quote(Worker.Row<R> in, List<Json> args) {
      return args.size() == 1
          ? call(in.host(), "price_of", args.get(0)).map(price -> times(2, price))
          : refused(in, "t:quote");
    }

    static <R> Comp<R, Json> total(Worker.Row<R> in, List<Json> items) { return total(in.host(), items, 0, 0); }

    private static <R> Comp<R, Json> total(In<Foreign, R> host, List<Json> items, int i, long sum) {
      if (i == items.size()) return pure(Json.of(sum));
      return call(host, "price_of", items.get(i)).flatMap(answer -> {
        if (!(answer instanceof Json.Int(long price))) throw new IllegalArgumentException("a price: " + answer.text());
        return total(host, items, i + 1, sum + price);
      });
    }

    static <R> Comp<R, Json> count(Worker.Row<R> in, List<Json> args) {
      return args.size() == 1 ? count(in.host(), args.get(0), 0) : refused(in, "t:count");
    }

    private static <R> Comp<R, Json> count(In<Foreign, R> host, Json n, long done) {
      return n.equals(Json.of(done)) ? pure(n) : call(host, "tick").then(() -> count(host, n, done + 1));
    }

    <R> Comp<R, Json> numbers(Worker.Row<R> in, List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Int(long n))) return refused(in, "t:numbers");
      sent = 0;
      return numbers(in.out(), 1, n);
    }

    private <R> Comp<R, Json> numbers(In<Emit<Json>, R> out, long next, long last) {
      if (next > last) return pure(Json.NULL);
      return Emit.send(out, Json.of(next)).then(() -> {
        sent++;
        return numbers(out, next + 1, last);
      });
    }

    <R> Comp<R, Json> sent(Worker.Row<R> in, List<Json> args) {
      return args.isEmpty() ? defer(() -> pure(Json.of(sent))) : refused(in, "t:sent");
    }

    static <R> Comp<R, Json> dedup(Worker.Row<R> in, List<Json> args) {
      return args.isEmpty() ? dedup(in, Set.of()) : refused(in, "t:dedup");
    }

    private static <R> Comp<R, Json> dedup(Worker.Row<R> in, Set<Json> seen) {
      return Fed.next(in.fed()).flatMap(next -> {
        if (next.isEmpty()) return pure(Json.NULL);
        if (seen.contains(next.get())) return dedup(in, seen);
        // a new set, the old one kept: the rest of a program is a value, and may be resumed again
        Set<Json> more = new HashSet<>(seen);
        more.add(next.get());
        return Emit.send(in.out(), next.get()).then(() -> dedup(in, more));
      });
    }

    <R> Comp<R, Json> await(Worker.Row<R> in, List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Str(String name))) return refused(in, "t:wait");
      return Async.await(in.async(), resume -> waiting.computeIfAbsent(name, none -> new ArrayList<>()).add(resume));
    }

    <R> Comp<R, Json> open(Worker.Row<R> in, List<Json> args) {
      if (!(args.size() == 1 && args.get(0) instanceof Json.Str(String name))) return refused(in, "t:open");
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
  static <R> Comp<R, List<Json>> answered(Body<Foreign, R, Json> body) {
    return handle(Foreign.class, new Handler<Foreign, Json, List<Json>, R>() {
      public Comp<R, List<Json>> ret(Json a) { return pure(List.of(a)); }
      public Comp<R, List<Json>> on(Foreign op, K<R, List<Json>> k) {
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
    }, body);
  }

  /** the rest of the program resumed with each of the values in turn: what every path found */
  private static <R> Comp<R, List<Json>> each(
      Foreign.Call c, List<Json> values, int i, List<Json> found, K<R, List<Json>> k) {
    if (i == values.size()) return pure(found);
    return k.resume(c, values.get(i)).flatMap(more -> {
      List<Json> all = new ArrayList<>(found);
      all.addAll(more);
      return each(c, values, i + 1, all, k);
    });
  }

  /** A program of a far side that may fail: made from the evidence of both. The row is written out here, once. */
  interface Script<A> extends Function<Far.Failing<Cons<Throws<Condition>, Cons<Far, Nil>>>,
      Comp<Cons<Throws<Condition>, Cons<Far, Nil>>, A>> {}

  /** the program's value on a far side, or the far side's failure */
  static <A> Throws.Result<Condition, A> attempt(Remote far, Script<A> p) {
    return far.run(fr -> Throws.run(th -> p.apply(new Far.Failing<>(fr.up(), th))));
  }

  /** the same, as a line says it */
  static <A> String on(Remote far, Script<A> p) {
    return switch (attempt(far, p)) {
      case Throws.Ok<Condition, A>(A value) -> value instanceof Json json ? json.text() : String.valueOf(value);
      case Throws.Err<Condition, A>(Condition c) -> c.kind + ": " + c.message;
    };
  }

  /** every answer of a far program, under this side's handler of what it asks */
  private static <R> Comp<R, Json> all(Body<Foreign, R, Json> program) { return answered(program).map(Json::of); }

  /** every value a stream sends */
  static <R> Comp<R, Json> sent(Body<Emit<Json>, R, Void> stream) {
    return Emit.collect(stream).map(sent -> Json.of(sent.values()));
  }

  /** THE HOST'S SCRIPT: a line for each feature. {@code open} gives a far side: over CBOR and DEFLATE or over JSON
   * lines, with a secret or with none. */
  static List<String> script(BiFunction<Boolean, Optional<String>, Remote> open) {
    List<String> lines = new ArrayList<>();
    try (Remote far = open.apply(false, Optional.empty())) {
      lines.add("pairs " + on(far, in -> all(host -> Far.program(in.up(), host, "t:pairs", List.of()))));
      List<Json> items = List.of(Json.of("tea"), Json.of("milk"));
      lines.add("total " + on(far, in -> all(host -> Far.program(in.up(), host, "shop:total", items))));
      lines.add("count " + on(far, in -> all(host -> Far.program(in.up(), host, "t:count", ints(10000)))));
      lines.add("call " + on(far, in -> Far.call(in, "t:add", Json.of(2), Json.of(3))));
      // an object kept on the far side: its method, its attribute; released, it is refused by name
      if (!(attempt(far, in -> Far.hold(in, "t:counter", Json.of(10))) instanceof Throws.Ok<Condition, Json.Ref>(
          Json.Ref counter))) {
        throw new IllegalStateException("t:counter was not held");
      }
      lines.add("held " + counter.kind() + " " + on(far, in -> Far.method(in, counter, "inc", Json.of(5))
          .flatMap(after -> Far.attr(in, counter, "n").map(n -> after.text() + " " + n.text()))));
      String released = on(far, in -> Far.release(in, counter).then(() -> Far.attr(in, counter, "n")));
      lines.add("released " + released.split(":")[0]);
      // a value kept: its ref, given to a call, is the value again
      lines.add("value " + on(far, in -> Far.hold(in, "t:echo", Json.of(ints(1, 2, 3))).flatMap(list ->
          Far.call(in, "t:sum", list).flatMap(sum ->
              Far.release(in, list).map(done -> list.kind() + " " + sum.text())))));
      lines.add("condition " + on(far, in -> Far.call(in, "t:boom")));
      Json odd = Json.of(List.of(Json.of(9007199254740993L), new Json.Dec(Double.NaN), new Json.Dec(3.0),
          new Json.Dec(0.5), Json.obj("a", Json.of(1))));
      lines.add("escapes " + on(far, in -> Far.call(in, "t:echo", odd).map(Escapes::enc)));
      lines.add("stream " + on(far, in -> sent(out -> Far.stream(in.up(), out, "t:numbers", ints(5), 2))));
      // three taken of a hundred: the far side was two ahead, and was stopped there
      lines.add("cancel " + on(far, in -> sent(out -> Emit.take(out, 3, cut ->
          Far.stream(in.<Emit<Json>>up().up(), cut, "t:numbers", ints(100), 2))))
          + " " + on(far, in -> Far.call(in, "t:sent")));
      lines.add("duplex " + on(far, in -> sent(out ->
          Far.stream(in.up(), out, "t:dedup", List.of(), 2, fed -> Emit.sendAll(fed, ints(3, 1, 3, 2, 1))))));
      // the second call is answered while the first still waits
      lines.add("mux " + on(far, in -> Far.start(in.far(), "t:wait", Json.of("g")).flatMap(waits ->
          Far.start(in.far(), "t:open", Json.of("g")).flatMap(opens ->
              Far.result(in, opens).flatMap(opened ->
                  Far.result(in, waits).map(waited -> Json.of(List.of(opened, waited))))))));
    }
    try (Remote framed = open.apply(true, Optional.empty())) {
      lines.add("cbor+deflate " + on(framed, in -> Far.call(in, "t:add", Json.of(2), Json.of(3)))
          + " " + on(framed, in -> all(host -> Far.program(in.up(), host, "t:pairs", List.of())))
          + " " + on(framed, in -> sent(out -> Far.stream(in.up(), out, "t:numbers", ints(3), 1))));
    }
    try (Remote proven = open.apply(false, Optional.of("a secret of the demonstration"))) {
      lines.add("auth " + on(proven, in -> Far.call(in, "t:add", Json.of(2), Json.of(3))));
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
