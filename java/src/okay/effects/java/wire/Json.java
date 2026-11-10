package okay.effects.java.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** JSON, as much as the wire needs: a value, its text, and the value of a text. */
public sealed interface Json {
  record Null() implements Json {}
  record Bool(boolean value) implements Json {}
  record Int(long value) implements Json {}
  record Dec(double value) implements Json {}
  record Str(String value) implements Json {}
  record Arr(List<Json> values) implements Json {}
  /** the fields in the order they were given */
  record Obj(Map<String, Json> fields) implements Json {
    /** this object and one field more */
    public Obj with(String name, Json value) {
      Map<String, Json> more = new LinkedHashMap<>(fields);
      more.put(name, value);
      return new Obj(more);
    }
  }
  /** not JSON: something the far side keeps, known here by a number. On the wire it is the escape {@code ref}. */
  record Ref(long id, String kind) implements Json {}

  Json NULL = new Null();

  static Json of(boolean value) { return new Bool(value); }
  static Json of(long value) { return new Int(value); }
  static Json of(String value) { return new Str(value); }
  static Json of(List<Json> values) { return new Arr(values); }

  /** an object of the names and values given in turn */
  static Obj obj(Object... namesAndValues) {
    Map<String, Json> fields = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      // the casts: the arguments come in turn, a name and then its value
      fields.put((String) namesAndValues[i], (Json) namesAndValues[i + 1]);
    }
    return new Obj(fields);
  }

  /** a field of an object; {@code NULL} when there is none */
  default Json get(String name) { return this instanceof Obj obj ? obj.fields().getOrDefault(name, NULL) : NULL; }

  default String text() {
    return switch (this) {
      case Null n -> "null";
      case Bool b -> String.valueOf(b.value());
      case Int i -> String.valueOf(i.value());
      case Dec d -> String.valueOf(d.value());
      case Str s -> quote(s.value());
      case Arr a -> a.values().stream().map(Json::text).collect(Collectors.joining(",", "[", "]"));
      case Obj o -> o.fields().entrySet().stream()
          .map(field -> quote(field.getKey()) + ":" + field.getValue().text())
          .collect(Collectors.joining(",", "{", "}"));
      case Ref ref -> Escapes.enc(ref).text();
    };
  }

  private static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        // the cast: a control character is written by its number
        default -> { if (c < ' ') out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
      }
    }
    return out.append('"').toString();
  }

  /** the value of the text; a text that is not JSON is an {@code IllegalArgumentException} */
  static Json parse(String text) {
    Parser parser = new Parser(text);
    Json value = parser.value();
    parser.spaces();
    if (parser.at < text.length()) throw parser.fail("the end");
    return value;
  }

  final class Parser {
    private final String text;
    private int at = 0;

    private Parser(String text) { this.text = text; }

    private IllegalArgumentException fail(String expected) {
      return new IllegalArgumentException("JSON: expected " + expected + " at " + at + " in: " + text);
    }

    private void spaces() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }

    private boolean take(String word) {
      boolean there = text.startsWith(word, at);
      if (there) at += word.length();
      return there;
    }

    private Json value() {
      spaces();
      if (take("null")) return NULL;
      if (take("true")) return new Bool(true);
      if (take("false")) return new Bool(false);
      if (take("\"")) return new Str(string());
      if (take("[")) return new Arr(items("]", this::value));
      if (take("{")) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Json> field : items("}", this::field)) fields.put(field.getKey(), field.getValue());
        return new Obj(fields);
      }
      return number();
    }

    /** the items up to the closing bracket, commas between them */
    private <T> List<T> items(String close, Supplier<T> item) {
      List<T> found = new ArrayList<>();
      spaces();
      if (take(close)) return found;
      do {
        found.add(item.get());
        spaces();
      } while (take(","));
      if (!take(close)) throw fail(close);
      return found;
    }

    private Map.Entry<String, Json> field() {
      spaces();
      if (!take("\"")) throw fail("a field's name");
      String name = string();
      spaces();
      if (!take(":")) throw fail(":");
      return Map.entry(name, value());
    }

    private String string() {
      StringBuilder out = new StringBuilder();
      while (at < text.length() && text.charAt(at) != '"') {
        char c = text.charAt(at);
        if (c == '\\') {
          at++;
          if (at >= text.length()) throw fail("the end of a string");
          switch (text.charAt(at)) {
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case 't' -> out.append('\t');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'u' -> {
              if (at + 5 > text.length()) throw fail("four hex digits");
              // the cast: a UTF-16 code unit, as JSON writes it
              out.append((char) Integer.parseInt(text.substring(at + 1, at + 5), 16));
              at += 4;
            }
            default -> out.append(text.charAt(at));
          }
        } else {
          out.append(c);
        }
        at++;
      }
      if (at >= text.length()) throw fail("the end of a string");
      at++;
      return out.toString();
    }

    /** a number written with a fraction or an exponent is a float, any other an integer */
    private Json number() {
      int from = at;
      while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) at++;
      String digits = text.substring(from, at);
      if (digits.isEmpty()) throw fail("a value");
      try {
        return new Int(Long.parseLong(digits));
      } catch (NumberFormatException notAnInteger) {
        return new Dec(Double.parseDouble(digits));
      }
    }
  }
}
