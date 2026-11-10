package okay.effects.java.wire;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A worker of any language held to the transcripts of {@code wire/}: it is started for each, sent the {@code >}
 * lines, and must say the {@code <} lines. Run from the repository or from {@code java/}:
 * {@code java -cp out okay.effects.java.wire.Transcripts <the worker's command...>}. */
final class Transcripts {
  private Transcripts() {}

  static final List<String> NAMES = List.of("transcript", "calls", "programs", "streams", "mux", "configure");

  /** a file of {@code wire/}, from the repository or from {@code java/} */
  static Path wire(String file) {
    return Files.isDirectory(Path.of("wire")) ? Path.of("wire", file) : Path.of("..", "wire", file);
  }

  /** the lines of a transcript that begin with the mark, without it */
  static List<String> marked(List<String> transcript, String mark) {
    return transcript.stream().filter(line -> line.startsWith(mark)).map(line -> line.substring(2)).toList();
  }

  /** equal as JSON: fields in any order, a number however it is spelled; "*" expected is anything */
  static boolean same(Json expected, Json got) {
    if (expected.equals(Json.of("*"))) return true;
    if (expected instanceof Json.Obj(Map<String, Json> a) && got instanceof Json.Obj(Map<String, Json> b)) {
      return a.size() == b.size()
          && a.entrySet().stream().allMatch(field -> b.containsKey(field.getKey())
              && same(field.getValue(), b.get(field.getKey())));
    }
    if (expected instanceof Json.Arr(List<Json> a) && got instanceof Json.Arr(List<Json> b)) {
      if (a.size() != b.size()) return false;
      for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
      return true;
    }
    if (expected instanceof Json.Int(long a) && got instanceof Json.Dec(double b)) return a == b;
    if (expected instanceof Json.Dec(double a) && got instanceof Json.Int(long b)) return a == b;
    return expected.equals(got);
  }

  /** where what the far side said is not what the transcript expects; none when it is */
  static Optional<String> differs(List<String> transcript, List<String> said) {
    List<Json> expected = marked(transcript, "< ").stream().map(Json::parse).toList();
    List<Json> answers = new ArrayList<>();
    for (String line : said) if (!line.isBlank()) answers.add(Json.parse(line));
    // the first transcript, the oldest, leaves the hello out
    if (!answers.isEmpty() && (expected.isEmpty() || expected.get(0).get("shim") == Json.NULL)) answers.remove(0);
    for (int i = 0; i < Math.min(expected.size(), answers.size()); i++) {
      if (!same(expected.get(i), answers.get(i))) {
        return Optional.of(
            "answer " + (i + 1) + ": expected " + expected.get(i).text() + ", got " + answers.get(i).text());
      }
    }
    if (expected.size() == answers.size()) return Optional.empty();
    return Optional.of("expected " + expected.size() + " answers, got " + answers.size());
  }

  /** what a worker started by the command says to the lines */
  private static List<String> said(List<String> command, List<String> asked) throws IOException {
    Process worker = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try {
      try (OutputStream out = worker.getOutputStream()) {
        out.write((String.join("\n", asked) + "\n").getBytes(UTF_8));
      }
      return new String(worker.getInputStream().readAllBytes(), UTF_8).lines().toList();
    } finally {
      worker.destroy();
    }
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("usage: Transcripts <the worker's command...>");
      System.exit(2);
    }
    int failed = 0;
    for (String name : NAMES) {
      String file = name + ".txt";
      Optional<String> wrong;
      try {
        List<String> transcript = Files.readAllLines(wire(file), UTF_8);
        wrong = differs(transcript, said(List.of(args), marked(transcript, "> ")));
      } catch (IOException | RuntimeException e) {
        wrong = Optional.of(e.toString());
      }
      if (wrong.isEmpty()) {
        System.out.println("  + " + file);
      } else {
        failed++;
        System.out.println("==> X " + file + ": " + wrong.get());
      }
    }
    System.out.println(failed == 0 ? "transcripts: all passed" : "transcripts: " + failed + " FAILED");
    System.exit(failed == 0 ? 0 : 1);
  }
}
