package okay.effects.java.wire;

import static okay.effects.java.Effects.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import okay.effects.java.Body;
import okay.effects.java.Cell;
import okay.effects.java.Comp;
import okay.effects.java.Effects;
import okay.effects.java.InPlace;
import okay.effects.java.Nil;
import okay.effects.java.std.Throws;

/** THE HOST'S END of a wire to a far side. Opening it is the handshake: the far side's hello, the proof of the
 * {@code secret} if there is one, and {@code configure} when CBOR or DEFLATE is asked for; each mismatch is refused
 * by name. */
public final class Remote implements AutoCloseable {
  /** values this side feeds a far function, and the credit the far side has given for them */
  private static final class Feed {
    final Iterator<Json> values;
    long credit;

    Feed(Iterator<Json> values, long credit) { this.values = values; this.credit = credit; }
  }

  private final Link link;
  private final OutputStream out;
  private final Runnable onClose;
  private final Json hello;
  /** whether the far side answers requests as they finish, not in the order they came */
  public final boolean mux;

  private long ids = 0, numbers = 0;
  private boolean gone = false;
  private final Set<Long> awaited = new HashSet<>();
  private final Map<Long, Json> answers = new HashMap<>();
  private final Set<Long> runs = new LinkedHashSet<>();
  private final Map<Long, ArrayDeque<Json>> streams = new LinkedHashMap<>();
  private final Map<Long, Feed> feeds = new LinkedHashMap<>();

  public Remote(InputStream in, OutputStream out, boolean cbor, boolean deflate, Optional<String> secret) {
    this(in, out, cbor, deflate, secret, () -> { });
  }

  private Remote(InputStream in, OutputStream out, boolean cbor, boolean deflate, Optional<String> secret,
      Runnable onClose) {
    this.link = new Link(in, out);
    this.out = out;
    this.onClose = onClose;
    this.hello = link.receive().orElseThrow(() -> new Condition("WireError", "the far side said nothing"));
    if (hello.get("fatal") != Json.NULL) {
      throw new Condition("WireError", "the far side cannot run: " + hello.get("fatal").text());
    }
    if (!hello.get("shim").equals(Json.of(Foreign.SHIM))) {
      throw new Condition("Shim", "the far side said " + hello.text());
    }
    this.mux = hello.get("speaks").get("mux").equals(Json.of(true));
    authenticate(secret);
    if (cbor || deflate) configure(cbor, deflate);
  }

  /** a far side started as a process, speaking the wire on its standard input and output; a {@code secret} is
   * given to it as OKAY_WIRE_SECRET */
  public static Remote start(List<String> command, boolean cbor, boolean deflate, Optional<String> secret) {
    ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
    secret.ifPresent(value -> builder.environment().put("OKAY_WIRE_SECRET", value));
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      return new Remote(process.getInputStream(), process.getOutputStream(), cbor, deflate, secret, process::destroy);
    } catch (RuntimeException e) {
      process.destroy();
      throw e;
    }
  }

  private void authenticate(Optional<String> secret) {
    Json auth = hello.get("auth");
    Json nonce = auth.get("nonce");
    if (nonce instanceof Json.Str(String far) && secret.isPresent()
        && auth.get("scheme").equals(Json.of("hmac-sha256"))) {
      String mine = Auth.nonce();
      link.send(Json.obj("op", Json.of("auth"), "nonce", Json.of(mine),
          "mac", Json.of(Auth.mac(secret.get(), "client", far, mine))));
      Json proof = link.receive().map(answer -> answer.get("ok").get("mac")).orElse(Json.NULL);
      if (!(proof instanceof Json.Str(String mac) && Auth.same(mac, Auth.mac(secret.get(), "server", far, mine)))) {
        throw new Condition("PermissionError", "the far side did not prove the secret");
      }
    } else if (nonce != Json.NULL) {
      throw new Condition("PermissionError", "the far side asks for authentication: " + auth.text());
    } else if (secret.isPresent()) {
      throw new Condition("PermissionError", "this side has a secret and the far side asks for no authentication");
    }
  }

  private void configure(boolean cbor, boolean deflate) {
    if (cbor) speaks("format", "cbor");
    if (deflate) speaks("compress", "deflate");
    link.send(Json.obj("op", Json.of("configure"), "format", Json.of(cbor ? "cbor" : "json"),
        "compress", Json.of(deflate ? "deflate" : "none")));
    Optional<Json> answer = link.receive();
    if (answer.isEmpty() || answer.get().get("ok") == Json.NULL) {
      throw new Condition("WireError", "configure was refused: " + answer.map(Json::text).orElse("the wire ended"));
    }
    link.configure(cbor, deflate);
  }

  private void speaks(String what, String name) {
    if (!Escapes.items(hello.get("speaks").get(what)).contains(Json.of(name))) {
      throw new Condition("WireError", "the far side does not speak the " + what + " " + name + ": " + hello.text());
    }
  }

  private static Condition lost() { return new Condition("WorkerDied", "the far side's wire ended"); }

  /** a request written; its answer is kept only if somebody will wait for it */
  private long send(String op, Json.Obj fields) {
    Map<String, Json> request = new LinkedHashMap<>();
    request.put("id", Json.of(++ids));
    request.put("op", Json.of(op));
    request.putAll(fields.fields());
    try {
      link.send(new Json.Obj(request));
    } catch (UncheckedIOException e) {
      gone = true;
    }
    return ids;
  }

  /** THE WIRE MOVES ONE STEP, while the program waits for something: a value is fed where the far side has given
   * credit; otherwise a message is read, and put where it is waited for. */
  private void turn() {
    for (Map.Entry<Long, Feed> entry : feeds.entrySet()) {
      Json stream = Json.of(entry.getKey());
      Feed feed = entry.getValue();
      if (!feed.values.hasNext()) {
        feeds.remove(entry.getKey());
        send("end", Json.obj("stream", stream));
        return;
      }
      if (feed.credit > 0) {
        feed.credit--;
        send("chunk", Json.obj("stream", stream, "chunk", Escapes.enc(feed.values.next())));
        return;
      }
    }
    Optional<Json> message;
    try {
      message = link.receive();
    } catch (UncheckedIOException e) {
      message = Optional.empty();
    }
    if (message.isEmpty()) {
      gone = true;
      return;
    }
    Json m = message.get();
    if (m.get("id") instanceof Json.Int(long id)) {
      if (awaited.remove(id)) answers.put(id, m);
    } else if (m.get("stream") instanceof Json.Int(long stream)) {
      if (m.get("credit") instanceof Json.Int(long credit)) {
        Feed feed = feeds.get(stream);
        if (feed != null) feed.credit += credit;
      } else {
        ArrayDeque<Json> messages = streams.get(stream);
        if (messages != null) messages.add(m);
      }
    }
  }

  private Throws.Result<Condition, Json> receive(long id) {
    while (!gone && !answers.containsKey(id)) turn();
    Json answer = answers.remove(id);
    if (answer == null) return new Throws.Err<>(lost());
    Optional<Condition> failure = failure(answer);
    return failure.isPresent() ? new Throws.Err<>(failure.get()) : new Throws.Ok<>(answer.get("ok"));
  }

  private static Optional<Condition> failure(Json message) {
    Json c = message.get("condition");
    return c == Json.NULL ? Optional.empty() : Optional.of(new Condition(text(c.get("kind")), text(c.get("message"))));
  }

  private static String text(Json tree) { return tree instanceof Json.Str(String value) ? value : tree.text(); }

  private Throws.Result<Condition, Long> open(Far.Open call) {
    if (!mux) {
      return new Throws.Err<>(new Condition("NotStreaming",
          "the far side claims no mux: a stream needs answers matched by id"));
    }
    long stream = ++numbers;
    Json.Obj request = Json.obj("fn", Json.of(call.fn()),
        "args", Json.of(call.args().stream().map(Escapes::enc).toList()),
        "stream", Json.of(stream), "credit", Json.of(call.credit()));
    // the stream's own number is the far side's; this side's input to the same call has the number negated
    Json input = Json.obj("id", Json.of(-stream), "credit", Json.of(call.credit()));
    streams.put(stream, new ArrayDeque<>());
    long id = send("call", call.input().isPresent() ? request.with("input", input) : request);
    awaited.add(id);
    if (receive(id) instanceof Throws.Err<Condition, Json>(Condition c)) {
      streams.remove(stream);
      return new Throws.Err<>(c);
    }
    call.input().ifPresent(values -> feeds.put(-stream, new Feed(values, call.credit())));
    return new Throws.Ok<>(stream);
  }

  private Throws.Result<Condition, Optional<Json>> pull(long stream) {
    ArrayDeque<Json> messages = streams.get(stream);
    if (messages == null) return new Throws.Err<>(new Condition("LookupError", "stream " + stream + " is not open"));
    while (!gone && messages.isEmpty()) turn();
    Json message = messages.poll(); // null: the wire ended
    if (message != null && failure(message).isEmpty() && !message.get("end").equals(Json.of(true))) {
      // taken: the far side may send one more
      send("credit", Json.obj("stream", Json.of(stream), "credit", Json.of(1)));
      return new Throws.Ok<>(Optional.of(message.get("chunk")));
    }
    streams.remove(stream);
    feeds.remove(-stream);
    if (message == null) return new Throws.Err<>(lost());
    Optional<Condition> failure = failure(message);
    return failure.isPresent() ? new Throws.Err<>(failure.get()) : new Throws.Ok<>(Optional.empty());
  }

  /** THE PROGRAM RUN to its value, its far operations done on this wire. When it ends, what it left open on the far
   * side is closed: its runs forgotten, its streams cancelled. Refs live on, until released. */
  public <A> A run(Body<Far, Nil, A> body) {
    try {
      return Effects.run(handle(Far.class, new InPlace<Far, Void, A, A, Nil>() {
        public Comp<Nil, A> ret(Void state, A a) { return pure(a); }
        public Cell.Answered answer(Cell<Void> state, Far op) {
          return switch (op) {
            case Far.Send send -> {
              long id = send(send.op(), send.fields());
              awaited.add(id);
              yield state.answer(send, id);
            }
            case Far.Receive receive -> state.answer(receive, receive(receive.id()));
            case Far.Run run -> {
              runs.add(++numbers);
              yield state.answer(run, numbers);
            }
            case Far.Open open -> state.answer(open, open(open));
            case Far.Pull pull -> state.answer(pull, pull(pull.stream()));
          };
        }
      }, null, body));
    } finally {
      streams.keySet().forEach(stream -> send("cancel", Json.obj("stream", Json.of(stream))));
      runs.forEach(run -> send("forget", Json.obj("run", Json.of(run))));
      streams.clear();
      feeds.clear();
      runs.clear();
    }
  }

  @Override public void close() {
    try {
      out.close();
    } catch (IOException e) {
      // the far side has gone already: there is nothing left to close
    }
    onClose.run();
  }
}
