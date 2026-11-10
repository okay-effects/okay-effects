package okay.effects.java.wire;

import static okay.effects.java.Effects.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import okay.effects.java.Comp;
import okay.effects.java.Cons;
import okay.effects.java.Handler;
import okay.effects.java.In;
import okay.effects.java.K;
import okay.effects.java.Nil;

/** THE FAR SIDE: programs by name, run a step at a time for a host; their continuations kept until forgotten. */
public final class Worker {
  /** A program served: made from its arguments, asking only what crosses. */
  public interface Program {
    Comp<Cons<Foreign, Nil>, Json> apply(In<Foreign, Cons<Foreign, Nil>> host, List<Json> args);
  }

  /** Where a program came to: its value, or a call for the host and the rest of the program. */
  private sealed interface Node {}
  private record Done(Json value) implements Node {}
  private record Asked(Foreign.Call call, Function<Json, Comp<Nil, Node>> k) implements Node {}

  private record Key(long run, long k) {}

  private final Map<String, Program> programs;
  private final Map<Key, Function<Json, Comp<Nil, Node>>> kept = new HashMap<>();
  private long counter = 0;

  public Worker(Map<String, Program> programs) { this.programs = programs; }

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
        return node(run.value(), stepped(programs.get(fn.value()), args));
      }
      case "continue" -> {
        if (!(m.get("k") instanceof Json.Int k) || !kept.containsKey(new Key(run.value(), k.value()))) {
          return condition("LookupError", "no continuation " + m.get("k").text() + " of run " + run.value());
        }
        return node(run.value(), kept.get(new Key(run.value(), k.value())).apply(m.get("answer")));
      }
      case "forget" -> {
        kept.keySet().removeIf(key -> key.run() == run.value());
        return Json.obj("ok", Json.NULL);
      }
      default -> { return condition("LookupError", "no operation " + op.value()); }
    }
  }

  /** the program under a handler that stops at every call: a step of it is a run to the next call */
  private static Comp<Nil, Node> stepped(Program program, List<Json> args) {
    return handle(Foreign.class, new Handler<Foreign, Json, Node, Nil>() {
      public Comp<Nil, Node> ret(Json value) { return pure(new Done(value)); }
      public Comp<Nil, Node> on(Foreign op, K<Nil, Node> k) {
        return switch (op) {
          case Foreign.Call call -> pure(new Asked(call, answer -> k.resume(call, answer)));
        };
      }
    }, host -> program.apply(host, args));
  }

  private Json node(long run, Comp<Nil, Node> p) {
    return switch (run(p)) {
      case Done done -> Json.obj("ok", Json.obj("done", done.value()));
      case Asked asked -> {
        kept.put(new Key(run, ++counter), asked.k());
        Foreign.Call call = asked.call();
        yield Json.obj("ok",
            Json.obj("perform", Json.of(call.name()), "args", Json.of(call.args()), "k", Json.of(counter)));
      }
    };
  }

  private static Json condition(String kind, String message) {
    return Json.obj("condition", Json.obj("kind", Json.of(kind), "message", Json.of(message)));
  }
}
