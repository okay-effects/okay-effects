package okay.effects.java.wire;

import static okay.effects.java.Effects.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.List;
import okay.effects.java.Comp;
import okay.effects.java.In;

/** THE HOST'S VIEW of a far side: a process speaking the wire on its standard input and output. A program of it is a
 * program here — what it asks is asked here as a {@link Foreign.Call}, and answered by this side's handlers. */
public final class Remote implements AutoCloseable {
  private final Process process;
  private final BufferedReader in;
  private final PrintStream out;
  private long ids = 0, runs = 0;

  public Remote(List<String> command) throws IOException {
    process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    in = process.inputReader();
    out = new PrintStream(process.getOutputStream(), true);
    Json hello = Json.parse(in.readLine());
    if (!hello.get("shim").equals(Json.of(Foreign.SHIM))) throw new Foreign.Condition("Shim", hello.text());
  }

  private Json ask(String op, Object... fields) {
    Object[] all = new Object[fields.length + 4];
    all[0] = "id"; all[1] = Json.of(++ids); all[2] = "op"; all[3] = Json.of(op);
    System.arraycopy(fields, 0, all, 4, fields.length);
    out.println(Json.obj(all).text());
    try {
      Json reply = Json.parse(in.readLine());
      Json failed = reply.get("condition");
      if (failed != Json.NULL) throw new Foreign.Condition(failed.get("kind").text(), failed.get("message").text());
      return reply.get("ok");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** the far side's program {@code fn}, as a program of this side */
  public <R> Comp<R, Json> program(In<Foreign, R> far, String fn, Json... args) {
    return defer(() -> {
      Json run = Json.of(++runs);
      return node(far, run, ask("program", "run", run, "fn", Json.of(fn), "args", Json.of(List.of(args))));
    });
  }

  private <R> Comp<R, Json> node(In<Foreign, R> far, Json run, Json ok) {
    if (ok.get("perform") instanceof Json.Str name && ok.get("args") instanceof Json.Arr args) {
      return perform(far, new Foreign.Call(name.value(), args.values()))
          .flatMap(answer -> node(far, run, ask("continue", "run", run, "k", ok.get("k"), "answer", answer)));
    }
    return pure(ok.get("done"));
  }

  @Override public void close() { process.destroy(); }
}
