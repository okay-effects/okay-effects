package okay.effects.java.wire;

import static java.nio.charset.StandardCharsets.UTF_8;
import static okay.effects.java.Effects.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import okay.effects.java.Comp;
import okay.effects.java.std.Async;
import okay.effects.java.std.Emit;
import okay.effects.java.std.Throws;

/** The wire: its codecs against their specifications' own examples, a worker against the transcripts of
 * {@code wire/}, and a host with a worker — in one process and in two — against {@code wire/expected.txt}.
 * Run from the repository or from {@code java/}: {@code java -cp out okay.effects.java.wire.WireTests}. */
final class WireTests {
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

  /** the failure of what must be refused */
  static <E extends RuntimeException> E refused(Class<E> by, Check body) throws Exception {
    try {
      body.run();
    } catch (RuntimeException e) {
      if (by.isInstance(e)) return by.cast(e);
      throw e;
    }
    throw new AssertionError("was not refused");
  }

  static byte[] bytes(String hex) { return HexFormat.of().parseHex(hex); }
  static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
  static Json dec(double x) { return new Json.Dec(x); }
  static Json arr(Json... values) { return Json.of(List.of(values)); }

  static final Json ODD = arr(Json.of(9007199254740993L), Json.of(-9007199254740992L), Json.of(9007199254740991L),
      dec(3.0), dec(-0.5), dec(1e300), Json.NULL, Json.of(true), Json.of("é \"q\" \\ \n\t 日本 😀"),
      Json.obj("b", Json.of(1), "a", arr(), "t", Json.obj()));

  /** a worker of the module on a thread of this process, and the host's end of the wire to it */
  static Remote far(Worker.Module module, boolean cbor, boolean deflate, Optional<String> secret,
      Optional<String> proving) {
    try {
      Pipe there = Pipe.open();
      Pipe back = Pipe.open();
      Thread worker = new Thread(() -> Worker.serve(module, Channels.newInputStream(there.source()),
          Channels.newOutputStream(back.sink()), secret));
      worker.setDaemon(true);
      worker.start();
      return new Remote(Channels.newInputStream(back.source()), Channels.newOutputStream(there.sink()), cbor,
          deflate, proving);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  static Remote far() { return far(Demo.module(), false, false, Optional.empty(), Optional.empty()); }

  /** what a worker says to the lines, all sent at once */
  static List<String> said(Optional<String> secret, List<String> asked) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] in = (String.join("\n", asked) + "\n").getBytes(UTF_8);
    Worker.serve(Demo.module(), new ByteArrayInputStream(in), out, secret);
    return out.toString(UTF_8).lines().toList();
  }

  /** the host's end of a wire on which the far side has said the text, and says no more */
  static Remote heard(String text, boolean cbor) {
    return new Remote(new ByteArrayInputStream(text.getBytes(UTF_8)), new ByteArrayOutputStream(), cbor, false,
        Optional.empty());
  }

  static <A> String kind(Throws.Result<Condition, A> result) {
    return result instanceof Throws.Err<Condition, A>(Condition c) ? c.kind : "no failure: " + result;
  }

  static <R> Comp<R, Json> slow(Worker.Row<R> in, List<Json> args) {
    return Async.sleep(in.async(), 100).map(done -> Json.of("slow"));
  }

  static <R> Comp<R, Json> fast(Worker.Row<R> in, List<Json> args) { return pure(Json.of("fast")); }

  public static void main(String[] args) {
    // ---- the codecs

    test("JSON: a text is read to the value that prints it", () -> {
      String text = "{\"a\":[1,-2,3.5,1.0E-7,true,false,null],\"b\":{\"c\":\"x\\ny \\\"z\\\" \\\\ é\"},\"d\":[]}";
      same(Json.parse(text).text(), text);
      same(Json.parse(" { \"a\" : [ 1 , 2 ] } "), Json.obj("a", arr(Json.of(1), Json.of(2))));
      same(Json.parse("3.0"), dec(3.0));
      same(Json.parse("3"), Json.of(3));
      same(Json.parse("\"\\u00e9\\ud83d\\ude00\""), Json.of("é😀"));
      refused(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
      refused(IllegalArgumentException.class, () -> Json.parse("\"cut short\\"));
    });

    test("the escapes: a value comes back the value it was", () -> {
      same(Escapes.dec(Escapes.enc(ODD)), ODD);
      same(Escapes.dec(Escapes.enc(new Json.Ref(7, "t.Counter"))), new Json.Ref(7, "t.Counter"));
      same(Escapes.dec(Json.parse(Escapes.enc(ODD).text())), ODD);
      same(Escapes.dec(Escapes.enc(dec(Double.NaN))), dec(Double.NaN));
    });

    test("the escapes: their shapes on the wire", () -> {
      same(Escapes.enc(Json.of(1L << 53)).text(), "{\"t\":\"int\",\"v\":\"9007199254740992\"}");
      same(Escapes.enc(Json.of((1L << 53) - 1)).text(), "9007199254740991");
      same(Escapes.enc(dec(3.0)).text(), "{\"t\":\"f\",\"v\":3.0}");
      same(Escapes.enc(dec(1e15)).text(), "1.0E15");
      same(Escapes.enc(dec(Double.NaN)).text(), "{\"t\":\"nan\"}");
      same(Escapes.enc(Json.obj("a", dec(2.0))).text(), "{\"t\":\"dict\",\"kv\":[[\"a\",{\"t\":\"f\",\"v\":2.0}]]}");
      same(Escapes.enc(new Json.Ref(1, "t.Counter")).text(), "{\"t\":\"ref\",\"id\":1,\"type\":\"t.Counter\"}");
      // what other far sides send: a whole float's `v` spelled as an integer, an object with no tag, R's absence
      same(Escapes.dec(Json.parse("[{\"t\":\"f\",\"v\":3},{\"a\":{\"t\":\"na\"}}]")),
          arr(dec(3.0), Json.obj("a", Json.NULL)));
      same(Escapes.dec(Json.parse("{\"t\":\"ref\",\"id\":4}")), new Json.Ref(4, ""));
      same(refused(Condition.class, () -> Escapes.dec(Json.parse("{\"t\":\"frame\",\"cols\":[]}"))).kind, "ValueError");
      same(refused(Condition.class, () -> Escapes.enc(dec(Double.POSITIVE_INFINITY))).kind, "ValueError");
    });

    test("CBOR: the examples of RFC 8949, appendix A", () -> {
      Map<String, Json> examples = new LinkedHashMap<>();
      examples.put("00", Json.of(0));
      examples.put("17", Json.of(23));
      examples.put("1818", Json.of(24));
      examples.put("1864", Json.of(100));
      examples.put("1903e8", Json.of(1000));
      examples.put("1a000f4240", Json.of(1000000));
      examples.put("1b000000e8d4a51000", Json.of(1000000000000L));
      examples.put("20", Json.of(-1));
      examples.put("3863", Json.of(-100));
      examples.put("3903e7", Json.of(-1000));
      examples.put("fb3ff199999999999a", dec(1.1));
      examples.put("fbc010666666666666", dec(-4.1));
      examples.put("f4", Json.of(false));
      examples.put("f5", Json.of(true));
      examples.put("f6", Json.NULL);
      examples.put("60", Json.of(""));
      examples.put("6449455446", Json.of("IETF"));
      examples.put("62c3bc", Json.of("ü"));
      examples.put("64f0908591", Json.of("𐅑"));
      examples.put("80", arr());
      examples.put("83010203", arr(Json.of(1), Json.of(2), Json.of(3)));
      examples.put("a0", Json.obj());
      examples.put("a26161016162820203", Json.obj("a", Json.of(1), "b", arr(Json.of(2), Json.of(3))));
      examples.forEach((bytes, tree) -> {
        same(hex(Cbor.encode(tree)), bytes);
        same(Cbor.decode(bytes(bytes)), tree);
      });
      // read and never written: floats of 16 and 32 bits, `undefined`
      same(Cbor.decode(bytes("f93c00")), dec(1.0));
      same(Cbor.decode(bytes("f93e00")), dec(1.5));
      same(Cbor.decode(bytes("f9c400")), dec(-4.0));
      same(Cbor.decode(bytes("fa47c35000")), dec(100000.0));
      same(Cbor.decode(bytes("f7")), Json.NULL);
    });

    test("CBOR: a message there and back; what is not in the subset is refused by name", () -> {
      Json message = Json.obj("id", Json.of(1), "ok", Escapes.enc(ODD));
      same(Cbor.decode(Cbor.encode(message)), message);
      same(Cbor.decode(Cbor.encode(Json.of(Long.MIN_VALUE))), Json.of(Long.MIN_VALUE));
      same(Cbor.decode(Cbor.encode(Json.of(Long.MAX_VALUE))), Json.of(Long.MAX_VALUE));
      for (String wrong : List.of("4100", "9f01ff", "c074", "a10100", "8301", "1bffffffffffffffff", "f8ff", "0000")) {
        refused(IllegalArgumentException.class, () -> Cbor.decode(bytes(wrong)));
      }
    });

    test("DEFLATE: raw, there and back; a message cut short is refused", () -> {
      same(hex(Link.deflated(new byte[0])), "0300");
      byte[] text = "the wire ".repeat(500).getBytes(UTF_8);
      byte[] packed = Link.deflated(text);
      same(packed.length < text.length / 10, true);
      same(new String(Link.inflated(packed), UTF_8), new String(text, UTF_8));
      same(Link.inflated(Link.deflated(new byte[0])).length, 0);
      refused(IllegalArgumentException.class, () -> Link.inflated(java.util.Arrays.copyOf(packed, packed.length / 2)));
      refused(IllegalArgumentException.class, () -> Link.inflated("not deflate at all".getBytes(UTF_8)));
    });

    test("frames: lines until configured, then a length and the message in the format chosen", () -> {
      Json message = Json.obj("id", Json.of(1), "ok", Escapes.enc(ODD));
      for (boolean cbor : List.of(false, true)) {
        for (boolean deflate : List.of(false, true)) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          Link sender = new Link(new ByteArrayInputStream(new byte[0]), out);
          sender.send(message);
          sender.configure(cbor, deflate);
          sender.send(message);
          sender.send(Json.NULL);
          Link receiver = new Link(new ByteArrayInputStream(out.toByteArray()), new ByteArrayOutputStream());
          same(receiver.receive(), Optional.of(message));
          receiver.configure(cbor, deflate);
          same(receiver.receive(), Optional.of(message));
          same(receiver.receive(), Optional.of(Json.NULL));
          same(receiver.receive(), Optional.empty());
        }
      }
    });

    test("frames: the example of wire/README.md, byte for byte", () -> {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Link link = new Link(new ByteArrayInputStream(new byte[0]), out);
      link.configure(true, false);
      link.send(Json.parse("{\"id\":1,\"op\":\"call\",\"fn\":\"t:add\",\"args\":[2,3]}"));
      same(hex(out.toByteArray()),
          ("00 00 00 1e  a4 62 69 64 01 62 6f 70 64 63 61 6c 6c 62 66 6e 65 74 3a 61 64 64 64 61 72 67 73 "
              + "82 02 03").replace(" ", ""));
      // and the least frame: {"id":1} in CBOR is a1 62 69 64 01, five bytes
      out.reset();
      link.send(Json.obj("id", Json.of(1)));
      same(hex(out.toByteArray()), "00000005a162696401");
    });

    test("auth: HMAC-SHA256 is RFC 4231's (test case 2); a proof names its side", () -> {
      same(Auth.hmac("Jefe", "what do ya want for nothing?"),
          "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
      same(Auth.mac("s", "client", "aa", "bb"), Auth.hmac("s", "okay-wire client|aa|bb"));
      same(Auth.mac("s", "client", "aa", "bb").equals(Auth.mac("s", "server", "aa", "bb")), false);
      same(Auth.nonce().length(), 32);
      same(Auth.same("abc", "abc") && !Auth.same("abc", "abd"), true);
    });

    // ---- a worker against the transcripts

    for (String name : Transcripts.NAMES) {
      test("the worker answers wire/" + name + ".txt", () -> {
        List<String> transcript = Files.readAllLines(Transcripts.wire(name + ".txt"), UTF_8);
        List<String> said = said(Optional.empty(), Transcripts.marked(transcript, "> "));
        same(Transcripts.differs(transcript, said), Optional.empty());
      });
    }

    // ---- a host and a worker

    List<String> expected;
    try {
      expected = Files.readAllLines(Transcripts.wire("expected.txt"), UTF_8);
    } catch (IOException e) {
      throw new AssertionError(e);
    }

    test("a host and a worker in one process: wire/expected.txt", () ->
        same(Demo.script((framed, secret) -> far(Demo.module(), framed, framed, secret, secret)), expected));

    test("a host and a worker in two processes: wire/expected.txt", () -> {
      String java = ProcessHandle.current().info().command().orElseThrow();
      List<String> worker = List.of(java, "-cp", System.getProperty("java.class.path"), Demo.class.getName(), "worker");
      same(Demo.script((framed, secret) -> Remote.start(worker, framed, framed, secret)), expected);
    });

    test("each choice of format and compression carries the same answers", () -> {
      for (boolean cbor : List.of(false, true)) {
        for (boolean deflate : List.of(false, true)) {
          try (Remote remote = far(Demo.module(), cbor, deflate, Optional.empty(), Optional.empty())) {
            same(Demo.attempt(remote, in -> Far.call(in, "t:echo", ODD)), new Throws.Ok<>(ODD));
            Json big = Json.of("x".repeat(100000));
            same(Demo.attempt(remote, in -> Far.call(in, "t:echo", big)), new Throws.Ok<>(big));
          }
        }
      }
    });

    test("a far failure is raised here, and a handler of this side decides", () -> {
      try (Remote remote = far()) {
        Json rescued = remote.run(fr -> Throws.recover((Condition c) -> pure(Json.of(c.kind)),
            th -> Far.call(new Far.Failing<>(fr.up(), th), "t:boom")));
        same(rescued, Json.of("ValueError"));
        same(kind(Demo.attempt(remote, in -> Far.call(in, "t:nothing"))), "LookupError");
        // the far side lives on
        same(Demo.attempt(remote, in -> Far.call(in, "t:add", Json.of(2), Json.of(3))), new Throws.Ok<>(Json.of(5)));
      }
    });

    test("mux: an answer that comes later, on another thread, does not hold the next", () -> {
      Worker.Module module = new Worker.Module(Map.of("slow", WireTests::slow, "fast", WireTests::fast), Map.of());
      try (Remote remote = far(module, false, false, Optional.empty(), Optional.empty())) {
        same(Demo.on(remote, in -> Far.start(in.far(), "slow").flatMap(slow ->
            Far.start(in.far(), "fast").flatMap(fast ->
                Far.result(in, fast).flatMap(first ->
                    Far.result(in, slow).map(second -> arr(first, second)))))), "[\"fast\",\"slow\"]");
      }
    });

    test("a stream: the far side is never more than the credit ahead; a long one in constant stack", () -> {
      try (Remote remote = far()) {
        for (int credit : List.of(1, 3, 10)) {
          same(Demo.on(remote, in -> Demo.sent(out -> Emit.take(out, 4, cut ->
              Far.stream(in.<Emit<Json>>up().up(), cut, "t:numbers", Demo.ints(1000), credit)))), "[1,2,3,4]");
          same(Demo.on(remote, in -> Far.call(in, "t:sent")), String.valueOf(4 + credit));
        }
        Json all = remote.run(fr -> Throws.recover((Condition c) -> pure(Json.of(c.kind)), th ->
            Demo.sent(out -> Far.stream(new Far.Failing<>(up(up(fr)), th.up()), out, "t:numbers",
                Demo.ints(50000), 64))));
        same(Escapes.items(all).size(), 50000);
        Iterable<Json> many = () -> IntStream.range(0, 20000).mapToObj(i -> Json.of(i % 1000)).iterator();
        same(Demo.attempt(remote, in -> Demo.sent(out ->
                Far.stream(in.up(), out, "t:dedup", List.of(), 2, fed -> Emit.sendAll(fed, many)))),
            new Throws.Ok<>(Json.of(IntStream.range(0, 1000).mapToObj(i -> Json.of(i)).toList())));
      }
    });

    test("a stream that fails ends with its condition; a far side with no mux has no streams", () -> {
      try (Remote remote = far()) {
        same(Demo.on(remote, in -> Demo.sent(out -> Far.stream(in.up(), out, "t:boom", List.of(), 1))),
            "ValueError: no");
      }
      try (Remote old = heard("{\"shim\":9,\"speaks\":{\"format\":[\"json\"]}}", false)) {
        same(kind(Demo.attempt(old, in -> Demo.sent(out ->
            Far.stream(in.up(), out, "t:numbers", Demo.ints(1), 1)))), "NotStreaming");
        // and when the far side is gone, that is a failure too
        same(kind(Demo.attempt(old, in -> Far.call(in, "t:add"))), "WorkerDied");
      }
    });

    test("auth: the same secret on both sides, or the wire is refused by name", () -> {
      Optional<String> s = Optional.of("s");
      Optional<String> none = Optional.empty();
      try (Remote proven = far(Demo.module(), false, false, s, s)) {
        same(Demo.on(proven, in -> Far.call(in, "t:add", Json.of(2), Json.of(3))), "5");
      }
      same(refused(Condition.class, () -> far(Demo.module(), false, false, s, Optional.of("another"))).kind,
          "PermissionError");
      same(refused(Condition.class, () -> far(Demo.module(), false, false, s, none)).kind, "PermissionError");
      same(refused(Condition.class, () -> far(Demo.module(), false, false, none, s)).kind, "PermissionError");
    });

    test("auth: until the host has proved the secret, the worker serves nothing; a wrong proof ends the wire", () -> {
      String add = "{\"id\":1,\"op\":\"call\",\"fn\":\"t:add\",\"args\":[2,3]}";
      List<String> early = said(Optional.of("s"), List.of(add));
      same(early.size(), 2);
      same(Json.parse(early.get(0)).get("auth").get("scheme"), Json.of("hmac-sha256"));
      same(Json.parse(early.get(1)).get("condition").get("kind"), Json.of("PermissionError"));
      List<String> wrong = said(Optional.of("s"), List.of("{\"op\":\"auth\",\"nonce\":\"00\",\"mac\":\"00\"}", add));
      same(wrong.size(), 2);
      same(Json.parse(wrong.get(1)).get("condition").get("kind"), Json.of("PermissionError"));
    });

    test("the handshake: another shim, or a format the far side does not speak, is refused by name", () -> {
      same(refused(Condition.class, () -> heard("{\"shim\":8}", false)).kind, "Shim");
      same(refused(Condition.class, () -> heard("{\"shim\":9,\"speaks\":{\"format\":[\"json\"]}}", true)).kind,
          "WireError");
      same(refused(Condition.class, () -> heard("{\"shim\":9,\"fatal\":\"no jsonlite\"}", false)).kind, "WireError");
      same(refused(Condition.class, () -> heard("", false)).kind, "WireError");
    });

    System.out.println(failed == 0 ? "java wire: all passed" : "java wire: " + failed + " FAILED");
    System.exit(failed == 0 ? 0 : 1);
  }
}
