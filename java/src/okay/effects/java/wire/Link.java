package okay.effects.java.wire;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** ONE END OF THE WIRE: messages out, messages in. JSON lines at first; after a {@code configure} to anything else,
 * FRAMES — a 4-byte big-endian length, then the message: JSON text or CBOR, raw DEFLATE over it when asked. A wire
 * that breaks is an {@code UncheckedIOException}; a message that cannot be read, an
 * {@code IllegalArgumentException}. */
public final class Link {
  private final InputStream in;
  private final OutputStream out;
  private boolean cbor = false, deflate = false;

  public Link(InputStream in, OutputStream out) {
    this.in = new BufferedInputStream(in);
    this.out = new BufferedOutputStream(out);
  }

  public void configure(boolean cbor, boolean deflate) {
    this.cbor = cbor;
    this.deflate = deflate;
  }

  public void send(Json message) {
    try {
      if (!cbor && !deflate) {
        out.write((message.text() + "\n").getBytes(UTF_8));
      } else {
        byte[] plain = cbor ? Cbor.encode(message) : message.text().getBytes(UTF_8);
        byte[] bytes = deflate ? deflated(plain) : plain;
        out.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.write(bytes);
      }
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** the next message; none when the other end has closed */
  public Optional<Json> receive() {
    try {
      if (!cbor && !deflate) return line().map(Json::parse);
      return frame().map(bytes -> {
        byte[] plain = deflate ? inflated(bytes) : bytes;
        return cbor ? Cbor.decode(plain) : Json.parse(new String(plain, UTF_8));
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** the next line that is not empty */
  private Optional<String> line() throws IOException {
    while (true) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      int next = in.read();
      while (next >= 0 && next != '\n') {
        bytes.write(next);
        next = in.read();
      }
      String text = bytes.toString(UTF_8).trim();
      if (!text.isEmpty()) return Optional.of(text);
      if (next < 0) return Optional.empty();
    }
  }

  private Optional<byte[]> frame() throws IOException {
    byte[] length = in.readNBytes(4);
    if (length.length < 4) return Optional.empty();
    byte[] bytes = in.readNBytes(ByteBuffer.wrap(length).getInt());
    // a frame cut short is the end of the wire, as a line cut short is
    return bytes.length < ByteBuffer.wrap(length).getInt() ? Optional.empty() : Optional.of(bytes);
  }

  /** raw DEFLATE (RFC 1951): no zlib header, no checksum */
  public static byte[] deflated(byte[] bytes) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    deflater.setInput(bytes);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
    deflater.end();
    return out.toByteArray();
  }

  public static byte[] inflated(byte[] bytes) {
    Inflater inflater = new Inflater(true);
    inflater.setInput(bytes);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try {
      while (!inflater.finished()) {
        int n = inflater.inflate(buffer);
        if (n == 0 && !inflater.finished() && inflater.needsInput()) {
          throw new IllegalArgumentException("DEFLATE: the message is cut short");
        }
        out.write(buffer, 0, n);
      }
    } catch (DataFormatException e) {
      throw new IllegalArgumentException("DEFLATE: " + e.getMessage());
    } finally {
      inflater.end();
    }
    return out.toByteArray();
  }
}
