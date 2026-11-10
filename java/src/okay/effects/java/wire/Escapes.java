package okay.effects.java.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** THE VALUE ESCAPES: what a JSON tree cannot say of a value, said by an object tagged {@code t}. A value is
 * escaped as it leaves and read back as it comes; the messages around it are plain JSON. */
public final class Escapes {
  private Escapes() {}

  /** integers are exact in every JSON reader only below this */
  private static final long EXACT = 1L << 53;

  public static Json enc(Json value) {
    return switch (value) {
      case Json.Int(long n) when n <= -EXACT || n >= EXACT ->
          Json.obj("t", Json.of("int"), "v", Json.of(Long.toString(n)));
      case Json.Dec(double x) when Double.isNaN(x) -> Json.obj("t", Json.of("nan"));
      case Json.Dec(double x) when Double.isInfinite(x) ->
          throw new Condition("ValueError", "an infinity does not cross the wire");
      // a whole float would come back an integer: the tag keeps 3.0 a float
      case Json.Dec(double x) when x == Math.rint(x) && Math.abs(x) < 1e15 -> Json.obj("t", Json.of("f"), "v", value);
      case Json.Arr(List<Json> values) -> Json.of(values.stream().map(Escapes::enc).toList());
      case Json.Obj(Map<String, Json> fields) -> {
        List<Json> pairs = new ArrayList<>();
        fields.forEach((name, field) -> pairs.add(Json.of(List.of(Json.of(name), enc(field)))));
        yield Json.obj("t", Json.of("dict"), "kv", Json.of(pairs));
      }
      case Json.Ref(long id, String kind) -> Json.obj("t", Json.of("ref"), "id", Json.of(id), "type", Json.of(kind));
      default -> value;
    };
  }

  public static Json dec(Json tree) {
    return switch (tree) {
      case Json.Arr(List<Json> values) -> Json.of(values.stream().map(Escapes::dec).toList());
      case Json.Obj(Map<String, Json> fields) when tree.get("t") instanceof Json.Str(String tag) -> tagged(tag, tree);
      // an object with no tag is a dict too: some far sides send theirs plain
      case Json.Obj(Map<String, Json> fields) -> {
        Map<String, Json> values = new LinkedHashMap<>();
        fields.forEach((name, field) -> values.put(name, dec(field)));
        yield new Json.Obj(values);
      }
      default -> tree;
    };
  }

  private static Json tagged(String tag, Json tree) {
    Json v = tree.get("v");
    switch (tag) {
      case "dict" -> {
        Map<String, Json> values = new LinkedHashMap<>();
        for (Json pair : items(tree.get("kv"))) {
          List<Json> kv = items(pair);
          if (kv.size() == 2 && kv.get(0) instanceof Json.Str(String name)) values.put(name, dec(kv.get(1)));
        }
        return new Json.Obj(values);
      }
      case "int" -> { if (v instanceof Json.Str(String digits)) return Json.of(Long.parseLong(digits)); }
      // `v` spelled either way: 3.0 or 3
      case "f" -> { return v instanceof Json.Int(long n) ? new Json.Dec(n) : v; }
      case "nan" -> { return new Json.Dec(Double.NaN); }
      case "na" -> { return Json.NULL; }
      case "ref" -> {
        if (!(tree.get("id") instanceof Json.Int(long id))) {
          throw new Condition("ValueError", "a ref without a number: " + tree.text());
        }
        return new Json.Ref(id, tree.get("type") instanceof Json.Str(String kind) ? kind : "");
      }
      default -> { }
    }
    throw new Condition("ValueError", "unknown tagged value: " + tag);
  }

  /** the values of an array; none of anything else */
  public static List<Json> items(Json tree) { return tree instanceof Json.Arr(List<Json> values) ? values : List.of(); }
}
