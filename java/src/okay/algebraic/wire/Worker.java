package okay.algebraic.wire;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import okay.algebraic.Comp;
import okay.algebraic.NoHandler;
import okay.algebraic.Rest;
import okay.algebraic.Step;

/** THE FAR SIDE: programs by name, run a step at a time for a host; their continuations kept until forgotten. */
public final class Worker {
  private record Key(long run, long k) {}

  private final Map<String, Function<List<Json>, Comp<Json>>> programs;
  private final Map<Key, Rest<Json, Json>> kept = new HashMap<>();
  private long counter = 0;

  public Worker(Map<String, Function<List<Json>, Comp<Json>>> programs) { this.programs = programs; }

  public void serve(BufferedReader in, PrintStream out) throws IOException {
    out.println(Json.obj("shim", Json.of(Foreign.SHIM), "java", Json.of("okay-algebraic 0.1.0"),
        "speaks", Json.obj("format", Json.of(List.of(Json.of("json"))))).text());
    for (String line = in.readLine(); line != null; line = in.readLine()) {
      if (line.isEmpty()) continue;
      Json m = Json.parse(line);
      Json reply;
      try {
        reply = answer(m);
      } catch (RuntimeException e) {
        reply = condition(e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
      }
      Map<String, Json> fields = new LinkedHashMap<>();
      fields.put("id", m.get("id"));
      fields.putAll(((Json.Obj) reply).fields());
      out.println(new Json.Obj(fields).text());
    }
  }

  private Json answer(Json m) {
    if (!(m.get("op") instanceof Json.Str op && m.get("run") instanceof Json.Int run)) {
      return condition("LookupError", "no operation " + m.get("op").text());
    }
    switch (op.value()) {
      case "program" -> {
        if (!(m.get("fn") instanceof Json.Str fn) || !programs.containsKey(fn.value())) {
          return condition("LookupError", "no program " + m.get("fn").text());
        }
        List<Json> args = m.get("args") instanceof Json.Arr arr ? arr.values() : List.of();
        return node(run.value(), programs.get(fn.value()).apply(args));
      }
      case "continue" -> {
        if (!(m.get("k") instanceof Json.Int k) || !kept.containsKey(new Key(run.value(), k.value()))) {
          return condition("LookupError", "no continuation " + m.get("k").text() + " of run " + run.value());
        }
        return node(run.value(), kept.get(new Key(run.value(), k.value())).resume(m.get("answer")));
      }
      case "forget" -> {
        kept.keySet().removeIf(key -> key.run() == run.value());
        return Json.obj("ok", Json.NULL);
      }
      default -> { return condition("LookupError", "no operation " + op.value()); }
    }
  }

  private Json node(long run, Comp<Json> p) {
    return switch (p.step()) {
      case Step.Done<Json> done -> Json.obj("ok", Json.obj("done", done.value()));
      case Step.Asked<?, Json> asked -> asked(run, asked);
    };
  }

  private <X> Json asked(long run, Step.Asked<X, Json> asked) {
    if (!(asked.op() instanceof Foreign.Call call)) throw new NoHandler(asked.op());
    @SuppressWarnings("unchecked") // a Call is an Op<Json>: its rest waits for a Json
    Rest<Json, Json> k = (Rest<Json, Json>) asked.k();
    kept.put(new Key(run, ++counter), k);
    Json perform = Json.obj("perform", Json.of(call.name()), "args", Json.of(call.args()), "k", Json.of(counter));
    return Json.obj("ok", perform);
  }

  private static Json condition(String kind, String message) {
    return Json.obj("condition", Json.obj("kind", Json.of(kind), "message", Json.of(message)));
  }
}
