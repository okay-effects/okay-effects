package okay.effects.java.wire;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CBOR (RFC 8949), the part the wire uses: integers, floats, text, arrays and maps of a known length, true, false,
 * null. The same tree as the JSON; anything else is refused by name. */
public final class Cbor {
  private Cbor() {}

  public static byte[] encode(Json tree) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, tree);
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, Json tree) {
    switch (tree) {
      case Json.Null n -> out.write(0xf6);
      case Json.Bool(boolean value) -> out.write(value ? 0xf5 : 0xf4);
      case Json.Int(long n) -> { if (n >= 0) head(out, 0, n); else head(out, 1, -1 - n); }
      case Json.Dec(double x) -> {
        out.write(0xfb);
        bytes(out, Double.doubleToLongBits(x), 8);
      }
      case Json.Str(String text) -> string(out, text);
      case Json.Arr(List<Json> values) -> {
        head(out, 4, values.size());
        values.forEach(value -> write(out, value));
      }
      case Json.Obj(Map<String, Json> fields) -> {
        head(out, 5, fields.size());
        fields.forEach((name, value) -> {
          string(out, name);
          write(out, value);
        });
      }
      case Json.Ref ref -> write(out, Escapes.enc(ref));
    }
  }

  private static void string(ByteArrayOutputStream out, String text) {
    byte[] bytes = text.getBytes(UTF_8);
    head(out, 3, bytes.length);
    out.writeBytes(bytes);
  }

  /** the major type and its argument, in the fewest bytes */
  private static void head(ByteArrayOutputStream out, int major, long n) {
    int m = major << 5;
    // the cast: n is below 24 here
    if (n < 24) out.write(m | (int) n);
    else if (n < (1L << 8)) { out.write(m | 24); bytes(out, n, 1); }
    else if (n < (1L << 16)) { out.write(m | 25); bytes(out, n, 2); }
    else if (n < (1L << 32)) { out.write(m | 26); bytes(out, n, 4); }
    else { out.write(m | 27); bytes(out, n, 8); }
  }

  /** the lowest {@code count} bytes of n, the highest first */
  private static void bytes(ByteArrayOutputStream out, long n, int count) {
    // the cast: `write` takes the lowest byte of what it is given
    for (int i = count - 1; i >= 0; i--) out.write((int) (n >>> (8 * i)));
  }

  public static Json decode(byte[] bytes) {
    ByteBuffer in = ByteBuffer.wrap(bytes);
    try {
      Json tree = read(in);
      if (in.hasRemaining()) throw refuse(in.remaining() + " bytes after the message");
      return tree;
    } catch (BufferUnderflowException e) {
      throw refuse("the message is cut short");
    }
  }

  private static IllegalArgumentException refuse(String why) { return new IllegalArgumentException("CBOR: " + why); }

  private static Json read(ByteBuffer in) {
    int first = in.get() & 0xff;
    int major = first >> 5;
    int info = first & 0x1f;
    if (major == 7) {
      return switch (info) {
        case 20 -> Json.of(false);
        case 21 -> Json.of(true);
        case 22, 23 -> Json.NULL;
        case 25 -> new Json.Dec(Float.float16ToFloat(in.getShort()));
        case 26 -> new Json.Dec(in.getFloat());
        case 27 -> new Json.Dec(in.getDouble());
        default -> throw refuse("the simple value " + info + " is not in the wire's subset");
      };
    }
    long n = switch (info) {
      case 24 -> in.get() & 0xffL;
      case 25 -> in.getShort() & 0xffffL;
      case 26 -> in.getInt() & 0xffffffffL;
      case 27 -> in.getLong();
      default -> {
        if (info < 24) yield info;
        throw refuse("an indefinite or reserved length (" + info + ") is not in the wire's subset");
      }
    };
    if (n < 0) throw refuse("an integer or a length beyond 63 bits");
    switch (major) {
      case 0 -> { return Json.of(n); }
      case 1 -> { return Json.of(-1 - n); }
      case 3 -> { return Json.of(text(in, n)); }
      case 4 -> {
        List<Json> values = new ArrayList<>();
        for (int left = count(in, n); left > 0; left--) values.add(read(in));
        return Json.of(values);
      }
      case 5 -> {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int left = count(in, n); left > 0; left--) {
          if (!(read(in) instanceof Json.Str(String name))) throw refuse("a map's key that is not text");
          fields.put(name, read(in));
        }
        return new Json.Obj(fields);
      }
      default -> throw refuse("the major type " + major + " (byte strings, tags) is not in the wire's subset");
    }
  }

  /** a count of items that the bytes left could hold: a lying length is refused before it is allocated */
  private static int count(ByteBuffer in, long n) {
    if (n > in.remaining()) throw new BufferUnderflowException();
    // the cast: n is no more than the bytes left, which an int counts
    return (int) n;
  }

  private static String text(ByteBuffer in, long n) {
    byte[] bytes = new byte[count(in, n)];
    in.get(bytes);
    return new String(bytes, UTF_8);
  }
}
