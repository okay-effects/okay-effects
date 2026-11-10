package okay.effects.scala.wire

import okay.effects.scala.*
import java.io.{BufferedInputStream, BufferedOutputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{Deflater, Inflater}

/** THE OPERATION THAT CROSSES a process: a name and arguments in JSON, answered in JSON. A program written over it
 * runs in one process, in one language; its handlers may be in another. Only this data crosses — a continuation
 * stays where it was made, known outside by a number. The messages are the okay wire's (wire/README.md). */
enum Foreign[X]:
  case Call(name: String, args: List[Json]) extends Foreign[Json]

export Foreign.Call

def call[R[_]](name: String, args: Json*)(using In[Foreign, R]): Json ! R = perform[Foreign](Call(name, args.toList))

/** A failure that crosses, by name: raised on one side, handled on the other (`Throws % Condition`). */
final case class Condition(kind: String, message: String) extends RuntimeException(s"$kind: $message")

val Shim = 9L

/** THE VALUE ESCAPES: what a JSON tree cannot say of a value, said by an object tagged `t`. A value is escaped as
 * it leaves and read back as it comes; the messages around it are plain JSON. */
object Escapes:
  /** integers are exact in every JSON reader only below this */
  private val Exact = 1L << 53

  def enc(value: Json): Json = value match
    case Json.Int(n) if n <= -Exact || n >= Exact => Json.obj("t" -> Json.Str("int"), "v" -> Json.Str(n.toString))
    case Json.Dec(x) if x.isNaN => Json.obj("t" -> Json.Str("nan"))
    case Json.Dec(x) if x.isInfinite => throw Condition("ValueError", "an infinity does not cross the wire")
    // a whole float would come back an integer: the tag keeps 3.0 a float
    case Json.Dec(x) if x == math.rint(x) && x.abs < 1e15 => Json.obj("t" -> Json.Str("f"), "v" -> value)
    case Json.Arr(values) => Json.Arr(values.map(enc))
    case Json.Obj(fields) =>
      val pairs = fields.map((name, field) => Json.Arr(List(Json.Str(name), enc(field))))
      Json.obj("t" -> Json.Str("dict"), "kv" -> Json.Arr(pairs))
    case Json.Ref(id, kind) => Json.obj("t" -> Json.Str("ref"), "id" -> Json.Int(id), "type" -> Json.Str(kind))
    case plain => plain

  def dec(tree: Json): Json = tree match
    case Json.Arr(values) => Json.Arr(values.map(dec))
    case Json.Obj(fields) => (tree("t"), tree("v")) match
      case (Json.Str("dict"), _) =>
        Json.Obj(items(tree("kv")).collect { case Json.Arr(List(Json.Str(name), field)) => (name, dec(field)) })
      case (Json.Str("int"), Json.Str(digits)) => Json.Int(digits.toLong)
      case (Json.Str("f"), Json.Int(n)) => Json.Dec(n.toDouble)
      case (Json.Str("f"), whole) => whole
      case (Json.Str("nan"), _) => Json.Dec(Double.NaN)
      case (Json.Str("na"), _) => Json.Null
      case (Json.Str("ref"), _) => (tree("id"), tree("type")) match
        case (Json.Int(id), Json.Str(kind)) => Json.Ref(id, kind)
        case (Json.Int(id), _) => Json.Ref(id, "")
        case _ => throw Condition("ValueError", s"a ref without a number: $tree")
      case (Json.Str(tag), _) => throw Condition("ValueError", s"unknown tagged value: $tag")
      // an object with no tag is a dict too: some far sides send theirs plain
      case _ => Json.Obj(fields.map((name, field) => (name, dec(field))))
    case plain => plain

  /** the values of an array; none of anything else */
  def items(tree: Json): List[Json] = tree match
    case Json.Arr(values) => values
    case _ => Nil

/** ONE END OF THE WIRE: messages out, messages in. JSON lines at first; after a `configure` to anything else,
 * FRAMES — a 4-byte big-endian length, then the message: JSON text or CBOR, raw DEFLATE over it when asked. */
final class Link(in0: InputStream, out0: OutputStream):
  private val in = BufferedInputStream(in0)
  private val out = BufferedOutputStream(out0)
  private var cbor, deflate = false

  def configure(cbor: Boolean, deflate: Boolean): Unit =
    this.cbor = cbor
    this.deflate = deflate

  def send(message: Json): Unit =
    if !cbor && !deflate then out.write((message.toString + "\n").getBytes(UTF_8))
    else
      val plain = if cbor then Cbor.encode(message) else message.toString.getBytes(UTF_8)
      val bytes = if deflate then Link.deflated(plain) else plain
      out.write(ByteBuffer.allocate(4).putInt(bytes.length).array)
      out.write(bytes)
    out.flush()

  /** the next message; none when the other end has closed */
  def receive(): Option[Json] =
    if !cbor && !deflate then line().map(Json.parse)
    else frame().map: bytes =>
      val plain = if deflate then Link.inflated(bytes) else bytes
      if cbor then Cbor.decode(plain) else Json.parse(String(plain, UTF_8))

  private def line(): Option[String] =
    val bytes = ByteArrayOutputStream()
    var byte = in.read()
    while byte >= 0 && byte != '\n' do
      bytes.write(byte)
      byte = in.read()
    val text = bytes.toString(UTF_8).trim
    if text.nonEmpty then Some(text) else if byte < 0 then None else line()

  private def frame(): Option[Array[Byte]] =
    val length = in.readNBytes(4)
    if length.length < 4 then None
    else
      val bytes = in.readNBytes(ByteBuffer.wrap(length).getInt)
      // a frame cut short is the end of the wire, as a line cut short is
      if bytes.length < ByteBuffer.wrap(length).getInt then None else Some(bytes)

object Link:
  /** raw DEFLATE (RFC 1951): no zlib header, no checksum */
  def deflated(bytes: Array[Byte]): Array[Byte] =
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(bytes)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = new Array[Byte](8192)
    while !deflater.finished() do out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    out.toByteArray

  def inflated(bytes: Array[Byte]): Array[Byte] =
    val inflater = Inflater(true)
    inflater.setInput(bytes)
    val out = ByteArrayOutputStream()
    val buffer = new Array[Byte](8192)
    try
      while !inflater.finished() do
        val n = inflater.inflate(buffer)
        if n == 0 && !inflater.finished() && inflater.needsInput() then
          throw IllegalArgumentException("DEFLATE: the message is cut short")
        out.write(buffer, 0, n)
    catch case e: java.util.zip.DataFormatException => throw IllegalArgumentException(s"DEFLATE: ${e.getMessage}")
    finally inflater.end()
    out.toByteArray

/** THE AUTHENTICATION of a wire, optional: each side proves it holds the secret, by an HMAC-SHA256 over both sides'
 * nonces, and neither sends it. */
object Auth:
  /** the proof of `who` — the far side is `server`, the host `client` — over the two nonces */
  def mac(secret: String, who: String, farNonce: String, hostNonce: String): String =
    hmac(secret, s"okay-wire $who|$farNonce|$hostNonce")

  /** HMAC-SHA256 of the text under the secret, in hex */
  def hmac(secret: String, text: String): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    hex(mac.doFinal(text.getBytes(UTF_8)))

  /** 16 random bytes, in hex */
  def nonce(): String =
    val bytes = new Array[Byte](16)
    java.security.SecureRandom().nextBytes(bytes)
    hex(bytes)

  /** equal, in a time that does not say how much of a guess was right */
  def same(a: String, b: String): Boolean = java.security.MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString
