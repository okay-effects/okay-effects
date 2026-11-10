package okay.effects.java.wire;

import static okay.effects.java.Effects.*;
import static okay.effects.java.wire.Foreign.call;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okay.effects.java.Body;
import okay.effects.java.Comp;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.K;
import okay.effects.java.Nil;

/** The demonstration every language of this repository has, the same: three programs served by a worker, and a
 * host that runs a worker's programs with its own handlers. Any host with any worker prints the same lines.
 * {@code java okay.effects.java.wire.Demo worker}, {@code java okay.effects.java.wire.Demo host <worker's command>}. */
public final class Demo {
  static final Map<String, Worker.Program> PROGRAMS = Map.of(
      "t:pairs", (host, args) -> call(host, "choose", Json.of(List.of(Json.of(1), Json.of(2))))
          .map(x -> Json.of(((Json.Int) x).value() * 10)),
      "shop:total", (host, items) -> total(host, items, 0, 0),
      "t:count", (host, args) -> count(host, ((Json.Int) args.get(0)).value(), 0));

  static <R> Comp<R, Json> total(In<Foreign, R> host, List<Json> items, int i, long sum) {
    if (i == items.size()) return pure(Json.of(sum));
    return call(host, "price_of", items.get(i))
        .flatMap(price -> total(host, items, i + 1, sum + ((Json.Int) price).value()));
  }

  static <R> Comp<R, Json> count(In<Foreign, R> host, long n, long done) {
    return done == n ? pure(Json.of(n)) : call(host, "tick").then(() -> count(host, n, done + 1));
  }

  static final Map<String, Long> PRICES = Map.of("tea", 3L, "milk", 2L);

  /** this side's answers: every choice taken, a price looked up, a tick counted */
  static Comp<Nil, List<Json>> answered(Body<Foreign, Nil, Json> body) {
    return handle(Foreign.class, new Handler<Foreign, Json, List<Json>, Nil>() {
      public Comp<Nil, List<Json>> ret(Json a) { return pure(List.of(a)); }
      public Comp<Nil, List<Json>> on(Foreign op, K<Nil, List<Json>> k) {
        return switch (op) {
          case Foreign.Call c when c.name().equals("choose") ->
              each(c, ((Json.Arr) c.args().get(0)).values(), 0, List.of(), k);
          case Foreign.Call c when c.name().equals("price_of") ->
              k.resume(c, Json.of(PRICES.get(((Json.Str) c.args().get(0)).value())));
          case Foreign.Call c when c.name().equals("tick") -> k.resume(c, Json.NULL);
          case Foreign.Call c -> throw new IllegalArgumentException("no answer for " + c.name());
        };
      }
    }, body);
  }

  static Comp<Nil, List<Json>> each(
      Foreign.Call c, List<Json> values, int i, List<Json> found, K<Nil, List<Json>> k) {
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
        System.out.println("pairs " + Json.of(run(answered(here -> far.program(here, "t:pairs")))).text());
        System.out.println("total " + Json.of(run(answered(here ->
            far.program(here, "shop:total", Json.of("tea"), Json.of("milk"))))).text());
        System.out.println("count "
            + Json.of(run(answered(here -> far.program(here, "t:count", Json.of(10000))))).text());
      }
    } else {
      System.err.println("usage: Demo worker | Demo host <the worker's command>");
      System.exit(2);
    }
  }
}
