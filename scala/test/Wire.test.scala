package okay.effects.scala.wire

import okay.effects.scala.*
import okay.effects.scala.std.{Async, Emit, Throws}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.channels.{Channels, Pipe}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The wire: its codecs against their specifications' own examples, a worker against the transcripts of `wire/`,
 * and a host with a worker — in one process and in two — against `wire/expected.txt`. */
class WireTests extends munit.FunSuite:
  import Far.Failing

  /** the row of a far stream taken here */
  type Streaming[X] = (Failing + Emit % Json)[X]

  private val wire: Path = List("../wire", "wire").map(Paths.get(_)).find(Files.isDirectory(_)).get

  private def bytes(hex: String): Array[Byte] = hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  private val odd = Json.Arr(List(Json.Int(9007199254740993L), Json.Int(-9007199254740992L),
    Json.Int(9007199254740991L), Json.Dec(3.0), Json.Dec(-0.5), Json.Dec(1e300), Json.Null, Json.Bool(true),
    Json.Str("é \"q\" \\ \n\t 日本 😀"),
    Json.obj("b" -> Json.Int(1), "a" -> Json.Arr(Nil), "t" -> Json.Obj(Nil))))

  // ---- the codecs

  test("JSON: a text is read to the value that prints it"):
    val text = """{"a":[1,-2,3.5,1.0E-7,true,false,null],"b":{"c":"x\ny \"z\" \\ é"},"d":[]}"""
    assertEquals(Json.parse(text).toString, text)
    assertEquals(Json.parse(""" { "a" : [ 1 , 2 ] } """), Json.obj("a" -> Json.Arr(List(Json.Int(1), Json.Int(2)))))
    assertEquals(Json.parse("3.0"), Json.Dec(3.0))
    assertEquals(Json.parse("3"), Json.Int(3))
    assertEquals(Json.parse("\"\\u00e9\\ud83d\\ude00\""), Json.Str("é😀"))
    intercept[IllegalArgumentException](Json.parse("{\"a\":1} x"))

  test("the escapes: a value comes back the value it was"):
    assertEquals(Escapes.dec(Escapes.enc(odd)), odd)
    assertEquals(Escapes.dec(Escapes.enc(Json.Ref(7, "t.Counter"))), Json.Ref(7, "t.Counter"))
    assertEquals(Escapes.dec(Json.parse(Escapes.enc(odd).toString)), odd)
    assert(Escapes.dec(Escapes.enc(Json.Dec(Double.NaN))) match { case Json.Dec(x) => x.isNaN; case _ => false })

  test("the escapes: their shapes on the wire"):
    def enc(value: Json): String = Escapes.enc(value).toString
    assertEquals(enc(Json.Int(1L << 53)), """{"t":"int","v":"9007199254740992"}""")
    assertEquals(enc(Json.Int((1L << 53) - 1)), "9007199254740991")
    assertEquals(enc(Json.Dec(3.0)), """{"t":"f","v":3.0}""")
    assertEquals(enc(Json.Dec(1e15)), "1.0E15")
    assertEquals(enc(Json.Dec(Double.NaN)), """{"t":"nan"}""")
    assertEquals(enc(Json.obj("a" -> Json.Dec(2.0))), """{"t":"dict","kv":[["a",{"t":"f","v":2.0}]]}""")
    assertEquals(enc(Json.Ref(1, "t.Counter")), """{"t":"ref","id":1,"type":"t.Counter"}""")
    // what other far sides send: a whole float's `v` spelled as an integer, an object with no tag, R's absence
    assertEquals(Escapes.dec(Json.parse("""[{"t":"f","v":3},{"a":{"t":"na"}}]""")),
      Json.Arr(List(Json.Dec(3.0), Json.obj("a" -> Json.Null))))
    assertEquals(intercept[Condition](Escapes.dec(Json.parse("""{"t":"frame","cols":[]}"""))).kind, "ValueError")
    assertEquals(intercept[Condition](Escapes.enc(Json.Dec(Double.PositiveInfinity))).kind, "ValueError")

  test("CBOR: the examples of RFC 8949, appendix A"):
    val examples = List(
      "00" -> Json.Int(0), "17" -> Json.Int(23), "1818" -> Json.Int(24), "1864" -> Json.Int(100),
      "1903e8" -> Json.Int(1000), "1a000f4240" -> Json.Int(1000000), "1b000000e8d4a51000" -> Json.Int(1000000000000L),
      "20" -> Json.Int(-1), "3863" -> Json.Int(-100), "3903e7" -> Json.Int(-1000),
      "fb3ff199999999999a" -> Json.Dec(1.1), "fbc010666666666666" -> Json.Dec(-4.1),
      "f4" -> Json.Bool(false), "f5" -> Json.Bool(true), "f6" -> Json.Null,
      "60" -> Json.Str(""), "6449455446" -> Json.Str("IETF"), "62c3bc" -> Json.Str("ü"),
      "64f0908591" -> Json.Str("𐅑"),
      "80" -> Json.Arr(Nil), "83010203" -> Json.Arr(List(Json.Int(1), Json.Int(2), Json.Int(3))),
      "a0" -> Json.Obj(Nil),
      "a26161016162820203" -> Json.obj("a" -> Json.Int(1), "b" -> Json.Arr(List(Json.Int(2), Json.Int(3)))))
    for (bytesHex, tree) <- examples do
      assertEquals(hex(Cbor.encode(tree)), bytesHex)
      assertEquals(Cbor.decode(bytes(bytesHex)), tree)
    // read and never written: floats of 16 and 32 bits, `undefined`
    assertEquals(Cbor.decode(bytes("f93c00")), Json.Dec(1.0))
    assertEquals(Cbor.decode(bytes("f93e00")), Json.Dec(1.5))
    assertEquals(Cbor.decode(bytes("f9c400")), Json.Dec(-4.0))
    assertEquals(Cbor.decode(bytes("fa47c35000")), Json.Dec(100000.0))
    assertEquals(Cbor.decode(bytes("f7")), Json.Null)

  test("CBOR: a message there and back; what is not in the subset is refused by name"):
    val message = Json.obj("id" -> Json.Int(1), "ok" -> Escapes.enc(odd))
    assertEquals(Cbor.decode(Cbor.encode(message)), message)
    for refused <- List("4100", "9f01ff", "c074", "a1010", "8301", "1bffffffffffffffff", "f8ff", "0000") do
      intercept[IllegalArgumentException](Cbor.decode(bytes(refused)))

  test("DEFLATE: raw, there and back; a message cut short is refused"):
    assertEquals(hex(Link.deflated(Array.empty)), "0300")
    val text = ("the wire " * 500).getBytes(UTF_8)
    val packed = Link.deflated(text)
    assert(packed.length < text.length / 10)
    assertEquals(String(Link.inflated(packed), UTF_8), String(text, UTF_8))
    assertEquals(Link.inflated(Link.deflated(Array.empty)).length, 0)
    intercept[IllegalArgumentException](Link.inflated(packed.take(packed.length / 2)))
    intercept[IllegalArgumentException](Link.inflated("not deflate at all".getBytes(UTF_8)))

  test("frames: lines until configured, then a length and the message in the format chosen"):
    val message = Json.obj("id" -> Json.Int(1), "ok" -> Escapes.enc(odd))
    for cbor <- List(false, true); deflate <- List(false, true) do
      val out = ByteArrayOutputStream()
      val sender = Link(ByteArrayInputStream(Array.empty), out)
      sender.send(message)
      sender.configure(cbor, deflate)
      sender.send(message)
      sender.send(Json.Null)
      val receiver = Link(ByteArrayInputStream(out.toByteArray), ByteArrayOutputStream())
      assertEquals(receiver.receive(), Some(message))
      receiver.configure(cbor, deflate)
      assertEquals(receiver.receive(), Some(message))
      assertEquals(receiver.receive(), Some(Json.Null))
      assertEquals(receiver.receive(), None)
    // the frame itself: {"id":1} in CBOR is a1 62 69 64 01, five bytes
    val out = ByteArrayOutputStream()
    val link = Link(ByteArrayInputStream(Array.empty), out)
    link.configure(cbor = true, deflate = false)
    link.send(Json.obj("id" -> Json.Int(1)))
    assertEquals(hex(out.toByteArray), "00000005a162696401")

  test("auth: HMAC-SHA256 is RFC 4231's (test case 2); a proof names its side"):
    assertEquals(Auth.hmac("Jefe", "what do ya want for nothing?"),
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    assertEquals(Auth.mac("s", "client", "aa", "bb"), Auth.hmac("s", "okay-wire client|aa|bb"))
    assertNotEquals(Auth.mac("s", "client", "aa", "bb"), Auth.mac("s", "server", "aa", "bb"))
    assertEquals(Auth.nonce().length, 32)

  // ---- a worker against the transcripts

  /** equal as JSON: fields in any order, a number however it is spelled; "*" expected is anything */
  private def same(expected: Json, got: Json): Boolean = (expected, got) match
    case (Json.Str("*"), _) => true
    case (Json.Obj(a), Json.Obj(b)) => a.length == b.length && a.forall((name, value) =>
      b.exists((other, found) => other == name && same(value, found)))
    case (Json.Arr(a), Json.Arr(b)) => a.length == b.length && a.zip(b).forall(same)
    case (Json.Int(a), Json.Dec(b)) => a.toDouble == b
    case (Json.Dec(a), Json.Int(b)) => a == b.toDouble
    case _ => expected == got

  for name <- List("transcript", "calls", "programs", "streams", "mux", "configure") do
    test(s"the worker answers wire/$name.txt"):
      val lines = Files.readAllLines(wire.resolve(s"$name.txt"), UTF_8).asScala.toList
      val asked = lines.filter(_.startsWith("> ")).map(_.drop(2))
      val expected = lines.filter(_.startsWith("< ")).map(line => Json.parse(line.drop(2)))
      val out = ByteArrayOutputStream()
      Worker.serve(Demo.module(), ByteArrayInputStream(asked.mkString("", "\n", "\n").getBytes(UTF_8)), out)
      val said = out.toString(UTF_8).linesIterator.map(Json.parse).toList
      // the first transcript, the oldest, leaves the hello out
      val answers = if expected.headOption.exists(_("shim") != Json.Null) then said else said.drop(1)
      assertEquals(answers.length, expected.length, answers.mkString("\n"))
      for (want, got) <- expected.zip(answers) do assert(same(want, got), s"expected $want, got $got")

  // ---- a host and a worker

  /** a worker of the module on a thread of this process, and the host's end of the wire to it */
  private def far(module: Worker.Module = Demo.module(), framed: Boolean = false, secret: Option[String] = None,
      proving: Option[String] = None): Remote =
    val (there, back) = (Pipe.open(), Pipe.open())
    val worker = Thread(() => Worker.serve(module, Channels.newInputStream(there.source),
      Channels.newOutputStream(back.sink), secret))
    worker.setDaemon(true)
    worker.start()
    Remote(Channels.newInputStream(back.source), Channels.newOutputStream(there.sink), framed, framed, proving)

  private def expected: List[String] = Files.readAllLines(wire.resolve("expected.txt"), UTF_8).asScala.toList

  test("a host and a worker in one process: wire/expected.txt"):
    assertEquals(Demo.script((framed, secret) => far(framed = framed, secret = secret, proving = secret)), expected)

  test("a host and a worker in two processes: wire/expected.txt"):
    val java = ProcessHandle.current().info().command().get
    val worker = List(java, "-cp", System.getProperty("java.class.path"), "okay.effects.scala.wire.wireDemo", "worker")
    assertEquals(Demo.script((framed, secret) => Remote.start(worker, framed, framed, secret)), expected)

  test("each choice of format and compression carries the same answers"):
    for cbor <- List(false, true); deflate <- List(false, true) do
      val (there, back) = (Pipe.open(), Pipe.open())
      val worker = Thread(() => Worker.serve(Demo.module(), Channels.newInputStream(there.source),
        Channels.newOutputStream(back.sink)))
      worker.setDaemon(true)
      worker.start()
      val remote = Remote(Channels.newInputStream(back.source), Channels.newOutputStream(there.sink), cbor, deflate)
      try
        assertEquals(remote.run(Throws.run(Far.call[Failing]("t:echo", odd))).map(_.toString), Right(odd.toString))
        val big = Json.Str("x" * 100000)
        assertEquals(remote.run(Throws.run(Far.call[Failing]("t:echo", big))), Right(big))
      finally remote.close()

  test("a far failure is raised here, and a handler of this side decides"):
    val remote = far()
    try
      val rescued = Throws.recover((c: Condition) => pure[Json, Far](Json.Str(c.kind)))(Far.call[Failing]("t:boom"))
      assertEquals(remote.run(rescued), Json.Str("ValueError"))
      assertEquals(remote.run(Throws.run(Far.call[Failing]("t:nothing"))).left.map(_.kind), Left("LookupError"))
      // the far side lives on
      assertEquals(remote.run(Throws.run(Far.call[Failing]("t:add", Json.Int(2), Json.Int(3)))), Right(Json.Int(5)))
    finally remote.close()

  test("mux: an answer that comes later, on another thread, does not hold the next"):
    val module = Worker.Module(Map(
      "slow" -> (_ => Async.sleep[Worker.Row](100).map(_ => Json.Str("slow"))),
      "fast" -> (_ => pure(Json.Str("fast")))))
    val remote = far(module)
    try
      val order = remote.run(Throws.run(
        for
          slow <- Far.start[Failing]("slow")
          fast <- Far.start[Failing]("fast")
          first <- Far.result[Failing](fast)
          second <- Far.result[Failing](slow)
        yield List(first, second)))
      assertEquals(order, Right(List(Json.Str("fast"), Json.Str("slow"))))
    finally remote.close()

  test("a stream: the far side is never more than the credit ahead; a long one in constant stack"):
    val remote = far()
    try
      for credit <- List(1, 3, 10) do
        val taken = Emit.take(4)(Far.stream[Streaming]("t:numbers", List(Json.Int(1000)), credit))
        assertEquals(remote.run(Throws.run(Emit.collect(taken))).map(_._1.length), Right(4))
        assertEquals(remote.run(Throws.run(Far.call[Failing]("t:sent"))), Right(Json.Int(4L + credit)))
      val all = Emit.collect(Far.stream[Streaming]("t:numbers", List(Json.Int(50000)), credit = 64))
      assertEquals(remote.run(Throws.run(all)).map(_._1.length), Right(50000))
      val fed = Emit.sendAll[Json, Emit % Json](Iterator.from(0).take(20000).map(i => Json.Int(i % 1000)))
      val distinct = Emit.collect(Far.stream[Streaming]("t:dedup", Nil, credit = 2, input = Some(fed)))
      assertEquals(remote.run(Throws.run(distinct)).map(_._1), Right(List.tabulate(1000)(i => Json.Int(i))))
    finally remote.close()

  test("a stream that fails ends with its condition; a far side with no mux has no streams"):
    val remote = far()
    try
      val failed = remote.run(Throws.run(Emit.collect(Far.stream[Streaming]("t:boom", Nil, credit = 1))))
      assertEquals(failed, Left(Condition("ValueError", "no")))
    finally remote.close()
    val old = Remote(ByteArrayInputStream("""{"shim":9,"speaks":{"format":["json"]}}""".getBytes(UTF_8)),
      ByteArrayOutputStream())
    val refused = old.run(Throws.run(Emit.collect(Far.stream[Streaming]("t:numbers", List(Json.Int(1)), credit = 1))))
    assertEquals(refused.left.map(_.kind), Left("NotStreaming"))
    // and when the far side is gone, that is a failure too
    assertEquals(old.run(Throws.run(Far.call[Failing]("t:add"))).left.map(_.kind), Left("WorkerDied"))

  test("auth: the same secret on both sides, or the wire is refused by name"):
    val proven = far(secret = Some("s"), proving = Some("s"))
    try assertEquals(proven.run(Throws.run(Far.call[Failing]("t:add", Json.Int(2), Json.Int(3)))), Right(Json.Int(5)))
    finally proven.close()
    assertEquals(intercept[Condition](far(secret = Some("s"), proving = Some("another"))).kind, "PermissionError")
    assertEquals(intercept[Condition](far(secret = Some("s"), proving = None)).kind, "PermissionError")
    assertEquals(intercept[Condition](far(secret = None, proving = Some("s"))).kind, "PermissionError")

  test("auth: until the host has proved the secret, the worker serves nothing; a wrong proof ends the wire"):
    def said(requests: String*): List[Json] =
      val out = ByteArrayOutputStream()
      Worker.serve(Demo.module(), ByteArrayInputStream(requests.mkString("", "\n", "\n").getBytes(UTF_8)), out,
        Some("s"))
      out.toString(UTF_8).linesIterator.map(Json.parse).toList
    val add = """{"id":1,"op":"call","fn":"t:add","args":[2,3]}"""
    val List(hello, early) = said(add): @unchecked
    assertEquals(hello("auth")("scheme"), Json.Str("hmac-sha256"))
    assertEquals(early("condition")("kind"), Json.Str("PermissionError"))
    val wrong = said("""{"op":"auth","nonce":"00","mac":"00"}""", add)
    assertEquals(wrong.length, 2)
    assertEquals(wrong(1)("condition")("kind"), Json.Str("PermissionError"))

  test("the handshake: another shim, or a format the far side does not speak, is refused by name"):
    def hello(text: String, cbor: Boolean): Remote =
      Remote(ByteArrayInputStream(text.getBytes(UTF_8)), ByteArrayOutputStream(), cbor)
    assertEquals(intercept[Condition](hello("""{"shim":8}""", false)).kind, "Shim")
    assertEquals(intercept[Condition](hello("""{"shim":9,"speaks":{"format":["json"]}}""", true)).kind, "WireError")
    assertEquals(intercept[Condition](hello("""{"shim":9,"fatal":"no jsonlite"}""", false)).kind, "WireError")
    assertEquals(intercept[Condition](hello("", false)).kind, "WireError")
