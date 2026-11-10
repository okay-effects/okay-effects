package okay.effects.scala.wire

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.{BufferUnderflowException, ByteBuffer}
import java.nio.charset.StandardCharsets.UTF_8

/** CBOR (RFC 8949), the part the wire uses: integers, floats, text, arrays and maps of a known length, true, false,
 * null. The same tree as the JSON; anything else is refused by name. */
object Cbor:
  def encode(tree: Json): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    write(DataOutputStream(bytes), tree)
    bytes.toByteArray

  private def write(out: DataOutputStream, tree: Json): Unit = tree match
    case Json.Null => out.write(0xf6)
    case Json.Bool(value) => out.write(if value then 0xf5 else 0xf4)
    case Json.Int(n) => if n >= 0 then head(out, 0, n) else head(out, 1, -1 - n)
    case Json.Dec(x) =>
      out.write(0xfb)
      out.writeDouble(x)
    case Json.Str(text) => string(out, text)
    case Json.Arr(values) =>
      head(out, 4, values.length)
      values.foreach(write(out, _))
    case Json.Obj(fields) =>
      head(out, 5, fields.length)
      fields.foreach: (name, value) =>
        string(out, name)
        write(out, value)
    case ref: Json.Ref => write(out, Escapes.enc(ref))

  private def string(out: DataOutputStream, text: String): Unit =
    val bytes = text.getBytes(UTF_8)
    head(out, 3, bytes.length)
    out.write(bytes)

  /** the major type and its argument, in the fewest bytes */
  private def head(out: DataOutputStream, major: Int, n: Long): Unit =
    val m = major << 5
    if n < 24 then out.write(m | n.toInt)
    else if n < (1L << 8) then { out.write(m | 24); out.write(n.toInt) }
    else if n < (1L << 16) then { out.write(m | 25); out.writeShort(n.toInt) }
    else if n < (1L << 32) then { out.write(m | 26); out.writeInt(n.toInt) }
    else { out.write(m | 27); out.writeLong(n) }

  def decode(bytes: Array[Byte]): Json =
    val in = ByteBuffer.wrap(bytes)
    try
      val tree = read(in)
      if in.hasRemaining then refuse(s"${in.remaining} bytes after the message")
      tree
    catch case _: BufferUnderflowException => refuse("the message is cut short")

  private def refuse(why: String): Nothing = throw IllegalArgumentException(s"CBOR: $why")

  private def read(in: ByteBuffer): Json =
    val first = in.get() & 0xff
    val (major, info) = (first >> 5, first & 0x1f)
    if major == 7 then info match
      case 20 => Json.Bool(false)
      case 21 => Json.Bool(true)
      case 22 | 23 => Json.Null
      case 25 => Json.Dec(java.lang.Float.float16ToFloat(in.getShort).toDouble)
      case 26 => Json.Dec(in.getFloat.toDouble)
      case 27 => Json.Dec(in.getDouble)
      case other => refuse(s"the simple value $other is not in the wire's subset")
    else
      val n = info match
        case small if small < 24 => small.toLong
        case 24 => in.get() & 0xffL
        case 25 => in.getShort & 0xffffL
        case 26 => in.getInt & 0xffffffffL
        case 27 => in.getLong
        case other => refuse(s"an indefinite or reserved length ($other) is not in the wire's subset")
      if n < 0 then refuse("an integer or a length beyond 63 bits")
      major match
        case 0 => Json.Int(n)
        case 1 => Json.Int(-1 - n)
        case 3 => Json.Str(text(in, n))
        case 4 => Json.Arr(List.fill(count(in, n))(read(in)))
        case 5 => Json.Obj(List.fill(count(in, n)):
          read(in) match
            case Json.Str(name) => (name, read(in))
            case other => refuse(s"a map's key that is not text: $other")
        )
        case other => refuse(s"the major type $other (byte strings, tags) is not in the wire's subset")

  /** a count of items that the bytes left could hold: a lying length is refused before it is allocated */
  private def count(in: ByteBuffer, n: Long): Int =
    if n > in.remaining then throw BufferUnderflowException() else n.toInt

  private def text(in: ByteBuffer, n: Long): String =
    val bytes = new Array[Byte](count(in, n))
    in.get(bytes)
    String(bytes, UTF_8)
