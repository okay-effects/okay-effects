package okay.effects.java.wire;

import static okay.effects.java.Effects.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Pure;
import okay.effects.java.Op;
import okay.effects.java.Step;
import okay.effects.java.std.Async;
import okay.effects.java.std.Emit;
import okay.effects.java.std.Throws;

/** THE FAR SIDE: a module served to a host, over the wire. Every function is a program, and what it asks decides
 * what crosses:
 *
 *   Foreign.Call    a `perform` node — the host answers, and continues the program (as often as it likes)
 *   Emit.Send       a chunk of the call's stream, when the host has given credit; the program waits for it
 *   Fed.Next        a value of the stream the host feeds, waited for; taking one gives the host a credit
 *   Async.Await     the program waits for the callback, and other requests are served meanwhile
 *   Throws.Raise    the call's condition
 *
 * A program that waits is a continuation kept: no thread waits with it. No handler takes these effects off: the
 * worker runs a program a step at a time and answers what it asks. */
public final class Worker {
  /** THE ROW of a function served: what it may ask, in this order — {@code Throws.raise(c)},
   * {@code up(Foreign.call(..))}, {@code up(up(Emit.send(..)))}, and so on. Named, so that it is written out once. */
  public interface Row extends Eff<Throws<Condition>, Eff<Foreign, Eff<Emit<Json>, Eff<Fed, Eff<Async, Pure>>>>> {}

  /** A function served: a program, made from its arguments. */
  public interface Program {
    Comp<Json, Row> apply(List<Json> args);
  }

  /** An object kept for the host: its methods and attributes, by name; none for a name it has not. */
  public interface Thing {
    String kind();
    Optional<Json> method(String name, List<Json> args);
    Optional<Json> attr(String name);
  }

  /** functions by name, and classes: the functions that make a {@code Thing} */
  public record Module(Map<String, Program> functions, Map<String, Function<List<Json>, Thing>> classes) {}

  /** the module served until the host closes the wire; with a {@code secret}, to a host that proves it has the
   * same */
  public static void serve(Module module, InputStream in, OutputStream out, Optional<String> secret) {
    Worker worker = new Worker(module, new Link(in, out), secret);
    worker.hello();
    while (worker.served()) { }
  }

  /** a function that waits, and how to go on with it */
  private abstract static class Waits {
    Runnable waiting; // null: it does not wait

    void wake() {
      Runnable go = waiting;
      waiting = null;
      if (go != null) go.run();
    }
  }

  /** a stream the host feeds: the values come and not yet taken; its function waits for one */
  private static final class Input extends Waits {
    final long id;
    final ArrayDeque<Json> values = new ArrayDeque<>();
    boolean ended = false;

    Input(long id) { this.id = id; }
  }

  /** a stream this side feeds: the credit left; its function waits for more */
  private static final class Output extends Waits {
    final long id;
    long credit;
    final Input input; // null: the host feeds nothing
    boolean open = true;

    Output(long id, long credit, Input input) { this.id = id; this.credit = credit; this.input = input; }
  }

  /** what a running function answers to */
  private sealed interface Task {
    /** a call: its value, or a ref to it when held */
    record Called(Json id, boolean held) implements Task {}
    /** a step of a program: a node */
    record Stepped(Json id, long run) implements Task {}
    /** a stream: chunks, then its end */
    record Streamed(Output out) implements Task {}
  }

  /** what a ref names */
  private sealed interface Held {
    record Plain(Json value) implements Held {}
    record Made(Thing thing) implements Held {}
  }

  private record Key(long run, long k) {}

  private final Module module;
  private final Link link;
  private final Optional<String> secret;
  private final Map<Key, Function<Json, Comp<Json, Row>>> kept = new HashMap<>();
  private long counter = 0;
  private final Map<Long, Held> held = new HashMap<>();
  private long refs = 0;
  private final Map<Long, Output> streams = new HashMap<>();
  private final Map<Long, Input> inputs = new HashMap<>();
  private final String nonce = Auth.nonce();
  private boolean proven;
  private boolean closing = false;

  private Worker(Module module, Link link, Optional<String> secret) {
    this.module = module;
    this.link = link;
    this.secret = secret;
    this.proven = secret.isEmpty();
  }

  private void hello() {
    Json.Obj speaks = Json.obj("format", Json.of(List.of(Json.of("json"), Json.of("cbor"))),
        "compress", Json.of(List.of(Json.of("deflate"))), "mux", Json.of(true));
    Json.Obj hello = Json.obj("shim", Json.of(Foreign.SHIM), "python", Json.of("java"), "speaks", speaks);
    Json.Obj auth = Json.obj("scheme", Json.of("hmac-sha256"), "nonce", Json.of(nonce));
    link.send(secret.isPresent() ? hello.with("auth", auth) : hello);
  }

  /** one request read and served; false at the end of the wire */
  private boolean served() {
    try {
      Optional<Json> request = link.receive();
      if (request.isEmpty()) return false;
      // a callback may come on another thread: one at a time
      synchronized (this) { serve(request.get()); }
      return !closing;
    } catch (UncheckedIOException e) {
      return false;
    } catch (IllegalArgumentException e) {
      synchronized (this) { failed(Json.NULL, new Condition("ValueError", String.valueOf(e.getMessage()))); }
      return true;
    }
  }

  private void serve(Json request) {
    Json id = request.get("id");
    try {
      if (!proven) {
        authenticate(id, request);
        return;
      }
      switch (request.get("op") instanceof Json.Str(String op) ? op : "") {
        case "call" -> call(id, request);
        case "program" -> {
          Task task = new Task.Stepped(id, run(request));
          advance(task, function(request.get("fn")).apply(arguments(request)));
        }
        case "continue" -> proceed(id, request);
        case "forget" -> {
          long run = run(request);
          kept.keySet().removeIf(key -> key.run() == run);
          ok(id, Json.NULL);
        }
        case "release" -> {
          held.remove(number(request.get("ref")));
          ok(id, Json.NULL);
        }
        case "credit" -> {
          ok(id, Json.NULL);
          Output out = streams.get(number(request.get("stream")));
          if (out != null) {
            out.credit += number(request.get("credit"));
            out.wake();
          }
        }
        case "chunk" -> {
          Json value = resolved(Escapes.dec(request.get("chunk")));
          ok(id, Json.NULL);
          Input in = inputs.get(number(request.get("stream")));
          if (in != null) {
            in.values.add(value);
            in.wake();
          }
        }
        case "end" -> {
          ok(id, Json.NULL);
          Input in = inputs.get(number(request.get("stream")));
          if (in != null) {
            in.ended = true;
            in.wake();
          }
        }
        case "cancel" -> {
          Output out = streams.get(number(request.get("stream")));
          if (out != null) close(out);
          ok(id, Json.NULL);
        }
        case "configure" -> configure(id, request);
        default -> throw new Condition("ValueError", "no operation " + request.get("op").text());
      }
    } catch (RuntimeException e) {
      failed(id, conditionOf(e));
    }
  }

  // ---- the answers

  private void ok(Json id, Json value) { link.send(Json.obj("id", id, "ok", value)); }

  private void failed(Json id, Condition c) { link.send(Json.obj("id", id, "condition", described(c))); }

  private static Json described(Condition c) {
    return Json.obj("kind", Json.of(c.kind), "message", Json.of(c.message));
  }

  private static Condition conditionOf(RuntimeException e) {
    return e instanceof Condition c ? c : new Condition(e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
  }

  // ---- the request's parts

  private static long number(Json tree) {
    if (tree instanceof Json.Int(long n)) return n;
    throw new Condition("ValueError", "a number was expected: " + tree.text());
  }

  private static long run(Json request) { return number(request.get("run")); }

  private Program function(Json name) {
    if (!(name instanceof Json.Str(String fn))) {
      throw new Condition("ValueError", "a function's name was expected: " + name.text());
    }
    Program function = module.functions().get(fn);
    if (function == null) throw new Condition("LookupError", "no function " + fn);
    return function;
  }

  /** the arguments, as values */
  private List<Json> arguments(Json request) {
    return Escapes.items(request.get("args")).stream().map(arg -> resolved(Escapes.dec(arg))).toList();
  }

  /** the value, with what each ref in it names: a ref crosses back as the value held */
  private Json resolved(Json value) {
    return switch (value) {
      case Json.Ref(long id, String kind) -> switch (heldAt(id)) {
        case Held.Plain(Json plain) -> plain;
        case Held.Made(Thing thing) -> throw new Condition("TypeError",
            "ref " + id + " is a " + thing.kind() + ": used by its methods, not given");
      };
      case Json.Arr(List<Json> values) -> Json.of(values.stream().map(this::resolved).toList());
      case Json.Obj(Map<String, Json> fields) -> {
        Map<String, Json> values = new LinkedHashMap<>();
        fields.forEach((name, field) -> values.put(name, resolved(field)));
        yield new Json.Obj(values);
      }
      default -> value;
    };
  }

  private Held heldAt(long ref) {
    Held what = held.get(ref);
    if (what != null) return what;
    throw new Condition("LookupError",
        "ref " + ref + " is not held by this worker (released, or held by a process that is gone)");
  }

  /** the value or the object kept, and the ref that names it */
  private Json hold(Held what) {
    held.put(++refs, what);
    return Escapes.enc(new Json.Ref(refs, switch (what) {
      case Held.Made(Thing thing) -> thing.kind();
      case Held.Plain(Json.Arr list) -> "list";
      case Held.Plain(Json.Obj dict) -> "dict";
      case Held.Plain(Json other) -> "value";
    }));
  }

  /** a call's answer: the value, or a ref to it when it is to be held */
  private void answer(Json id, boolean keep, Json value) {
    ok(id, keep ? hold(new Held.Plain(value)) : Escapes.enc(value));
  }

  // ---- the operations

  private void call(Json id, Json request) {
    List<Json> args = arguments(request);
    boolean keep = request.get("held").equals(Json.of(true));
    Json fn = request.get("fn");
    if (request.get("stream") instanceof Json.Int(long stream)) {
      open(id, stream, request, function(fn).apply(args));
    } else if (fn instanceof Json.Str(String name) && module.classes().containsKey(name)) {
      if (!keep) throw new Condition("TypeError", name + " makes an object: it crosses only held, as a ref");
      ok(id, hold(new Held.Made(module.classes().get(name).apply(args))));
    } else if (fn instanceof Json.Str) {
      advance(new Task.Called(id, keep), function(fn).apply(args));
    } else if (fn.get("ref") instanceof Json.Int(long ref) && fn.get("method") instanceof Json.Str(String name)) {
      answer(id, keep, member(ref, name, thing -> thing.method(name, args)));
    } else if (fn.get("ref") instanceof Json.Int(long ref) && fn.get("attr") instanceof Json.Str(String name)) {
      answer(id, keep, member(ref, name, thing -> thing.attr(name)));
    } else {
      throw new Condition("ValueError", "a call's address is a name, a method or an attribute: " + fn.text());
    }
  }

  /** a method's answer or an attribute, of an object held */
  private Json member(long ref, String name, Function<Thing, Optional<Json>> find) {
    return switch (heldAt(ref)) {
      case Held.Made(Thing thing) -> find.apply(thing).orElseThrow(() ->
          new Condition("LookupError", "a " + thing.kind() + " has no " + name));
      case Held.Plain plain -> throw new Condition("LookupError", "ref " + ref + " is a value: it has no " + name);
    };
  }

  /** a call as a stream: answered at once, its chunks follow as the host gives credit */
  private void open(Json id, long stream, Json request, Comp<Json, Row> p) {
    Input input = request.get("input").get("id") instanceof Json.Int(long fed) ? new Input(fed) : null;
    long credit = request.get("credit") instanceof Json.Int(long n) ? n : 1;
    Output out = new Output(stream, credit, input);
    streams.put(stream, out);
    if (input != null) inputs.put(input.id, input);
    ok(id, Json.NULL);
    advance(new Task.Streamed(out), p);
  }

  /** a program continued from a node it was left at */
  private void proceed(Json id, Json request) {
    long run = run(request);
    long k = number(request.get("k"));
    if (request.get("condition") != Json.NULL) {
      throw new Condition("ValueError", "a program as data is continued with an answer, not a condition");
    }
    Function<Json, Comp<Json, Row>> rest = kept.get(new Key(run, k));
    if (rest == null) throw new Condition("LookupError", "no continuation " + k + " of run " + run);
    advance(new Task.Stepped(id, run), rest.apply(resolved(Escapes.dec(request.get("answer")))));
  }

  private void configure(Json id, Json request) {
    Json format = request.get("format");
    Json compress = request.get("compress");
    if (!format.equals(Json.of("json")) && !format.equals(Json.of("cbor"))) {
      throw new Condition("ValueError", "this worker speaks the formats json, cbor; not " + format.text());
    }
    if (!compress.equals(Json.of("none")) && !compress.equals(Json.of("deflate"))) {
      throw new Condition("ValueError", "this worker speaks the compressions none, deflate; not " + compress.text());
    }
    // the answer goes as the request came; the next message is the first in the new way
    ok(id, Json.obj("format", format, "compress", compress));
    link.configure(format.equals(Json.of("cbor")), compress.equals(Json.of("deflate")));
  }

  /** until the host has proved the secret, only its proof is heard; a wrong one closes the wire */
  private void authenticate(Json id, Json request) {
    if (!request.get("op").equals(Json.of("auth"))) {
      throw new Condition("PermissionError", "this worker requires hmac-sha256 authentication first");
    }
    String mine = secret.orElseThrow();
    if (request.get("nonce") instanceof Json.Str(String host) && request.get("mac") instanceof Json.Str(String mac)
        && !host.isEmpty() && Auth.same(mac, Auth.mac(mine, "client", nonce, host))) {
      proven = true;
      ok(id, Json.obj("mac", Json.of(Auth.mac(mine, "server", nonce, host))));
    } else {
      closing = true;
      throw new Condition("PermissionError", "authentication refused: the mac does not prove this worker's secret");
    }
  }

  // ---- a function, run

  /** the task's program run until it ends or waits: for the host's answer, a credit, a value, a callback */
  private void advance(Task task, Comp<Json, Row> start) {
    Comp<Json, Row> next = start;
    while (next != null) {
      try {
        next = turn(task, step(next));
      } catch (RuntimeException e) {
        fail(task, conditionOf(e));
        next = null;
      }
    }
  }

  /** where the program came to: what goes to the host, and the rest of the program — null when it has ended or
   * must wait */
  private Comp<Json, Row> turn(Task task, Step<Json, Row> at) {
    if (task instanceof Task.Streamed(Output out) && !out.open) return null;
    switch (at) {
      case Step.Done<Json, Row>(Json value) -> {
        switch (task) {
          case Task.Called(Json id, boolean keep) -> answer(id, keep, value);
          case Task.Stepped(Json id, long run) -> ok(id, Json.obj("done", Escapes.enc(value)));
          case Task.Streamed(Output out) -> {
            close(out);
            link.send(Json.obj("stream", Json.of(out.id), "end", Json.of(true)));
          }
        }
        return null;
      }
      case Step.Asked<?, Json, Row> asked -> { return asked(task, asked); }
    }
  }

  private Comp<Json, Row> asked(Task task, Step.Asked<?, Json, Row> asked) {
    switch (asked.op()) {
      case Throws.Raise<?, ?>(Condition c) -> fail(task, c);
      case Foreign.Call call when task instanceof Task.Stepped(Json id, long run) -> {
        kept.put(new Key(run, ++counter), answer -> resume(asked, call, answer));
        List<Json> args = call.args().stream().map(Escapes::enc).toList();
        ok(id, Json.obj("perform", Json.of(call.name()), "args", Json.of(args), "k", Json.of(counter)));
      }
      case Emit.Send<?> send when send.value() instanceof Json value && task instanceof Task.Streamed(Output out) -> {
        if (out.credit > 0) {
          out.credit--;
          link.send(Json.obj("stream", Json.of(out.id), "chunk", Escapes.enc(value)));
          return resume(asked, send, null);
        }
        out.waiting = () -> advance(task, up(up(Emit.send(value))).flatMap(sent -> resume(asked, send, sent)));
      }
      case Fed.Next next when task instanceof Task.Streamed(Output out) && out.input != null -> {
        Input in = out.input;
        if (!in.values.isEmpty()) {
          // taken: the host may send one more
          link.send(Json.obj("stream", Json.of(in.id), "credit", Json.of(1)));
          return resume(asked, next, Optional.of(in.values.remove()));
        }
        if (in.ended) return resume(asked, next, Optional.empty());
        in.waiting = () -> advance(task, up(up(up(Fed.next()))).flatMap(value -> resume(asked, next, value)));
      }
      // a function that takes from an input the call does not have finds its end at once
      case Fed.Next next -> { return resume(asked, next, Optional.empty()); }
      case Async.Await<?> await -> awaited(task, asked, await);
      default -> throw new Condition("LookupError", "nothing here answers " + asked.op());
    }
    return null;
  }

  /** the rest of the program, given the answer of the operation it asked */
  private static <X> Comp<Json, Row> resume(Step.Asked<?, Json, Row> asked, Op<?, X> op, X answer) {
    if (op != asked.op()) throw new IllegalArgumentException("not the operation asked: " + op);
    @SuppressWarnings("unchecked") // the operation is the witness: the rest takes the answer of the operation asked
    Function<X, Comp<Json, Row>> rest = (Function<X, Comp<Json, Row>>) asked.k();
    return rest.apply(answer);
  }

  /** the program goes on when the callback comes, on whatever thread */
  private <X> void awaited(Task task, Step.Asked<?, Json, Row> asked, Async.Await<X> await) {
    await.register().accept(x -> {
      synchronized (this) { advance(task, resume(asked, await, x)); }
    });
  }

  private void fail(Task task, Condition c) {
    switch (task) {
      case Task.Called(Json id, boolean keep) -> failed(id, c);
      case Task.Stepped(Json id, long run) -> failed(id, c);
      case Task.Streamed(Output out) -> {
        if (out.open) link.send(Json.obj("stream", Json.of(out.id), "condition", described(c)));
        close(out);
      }
    }
  }

  /** the stream is over, by its end, its failure or the host's cancel: its function is not resumed */
  private void close(Output out) {
    out.open = false;
    streams.remove(out.id);
    if (out.input != null) inputs.remove(out.input.id);
  }
}
