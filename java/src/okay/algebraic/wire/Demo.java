package okay.algebraic.wire;

import static okay.algebraic.Comp.*;
import static okay.algebraic.wire.Foreign.call;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import okay.algebraic.Comp;
import okay.algebraic.Handler;
import okay.algebraic.K;

/** The demonstration every language of this repository has, the same: three programs served by a worker, and a
 * host that runs a worker's programs with its own handlers. Any host with any worker prints the same lines.
 * {@code java okay.algebraic.wire.Demo worker}, {@code java okay.algebraic.wire.Demo host <worker's command>}. */
public final class Demo {
  static final Map<String, Function<List<Json>, Comp<Json>>> PROGRAMS = Map.of(
      "t:pairs", args -> call("choose", Json.of(List.of(Json.of(1), Json.of(2))))
          .map(x -> Json.of(((Json.Int) x).value() * 10)),
      "shop:total", items -> total(items, 0, 0),
      "t:count", args -> count(((Json.Int) args.get(0)).value(), 0));

  static Comp<Json> total(List<Json> items, int i, long sum) {
    if (i == items.size()) return pure(Json.of(sum));
    return call("price_of", items.get(i)).flatMap(price -> total(items, i + 1, sum + ((Json.Int) price).value()));
  }

  static Comp<Json> count(long n, long done) {
    return done == n ? pure(Json.of(n)) : call("tick").then(() -> count(n, done + 1));
  }

  static final Map<String, Long> PRICES = Map.of("tea", 3L, "milk", 2L);

  /** this side's answers: every choice taken, a price looked up, a tick counted */
  static Comp<List<Json>> answered(Comp<Json> p) {
    Handler<Foreign, Json, List<Json>> h = Handler.of(
        Foreign.class,
        a -> pure(List.of(a)),
        (op, k) -> switch (op) {
          case Foreign.Call c when c.name().equals("choose") ->
              each(c, ((Json.Arr) c.args().get(0)).values(), 0, List.of(), k);
          case Foreign.Call c when c.name().equals("price_of") ->
              k.resume(c, Json.of(PRICES.get(((Json.Str) c.args().get(0)).value())));
          case Foreign.Call c when c.name().equals("tick") -> k.resume(c, Json.NULL);
          case Foreign.Call c -> throw new IllegalArgumentException("no answer for " + c.name());
        });
    return handle(h, p);
  }

  static Comp<List<Json>> each(Foreign.Call c, List<Json> values, int i, List<Json> found, K<List<Json>> k) {
    if (i == values.size()) return pure(found);
    return k.resume(c, values.get(i)).flatMap(more -> {
      List<Json> all = new ArrayList<>(found);
      all.addAll(more);
      return each(c, values, i + 1, all, k);
    });
  }

  public static void main(String[] args) throws IOException {
    if (args.length > 0 && args[0].equals("worker")) {
      new Worker(PROGRAMS).serve(new BufferedReader(new InputStreamReader(System.in)), System.out);
    } else if (args.length > 1 && args[0].equals("host")) {
      try (Remote far = new Remote(List.of(args).subList(1, args.length))) {
        System.out.println("pairs " + Json.of(answered(far.program("t:pairs")).run()).text());
        System.out.println("total "
            + Json.of(answered(far.program("shop:total", Json.of("tea"), Json.of("milk"))).run()).text());
        System.out.println("count " + Json.of(answered(far.program("t:count", Json.of(10000))).run()).text());
      }
    } else {
      System.err.println("usage: Demo worker | Demo host <the worker's command>");
      System.exit(2);
    }
  }
}
