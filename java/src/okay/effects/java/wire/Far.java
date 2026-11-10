package okay.effects.java.wire;

import static okay.effects.java.Effects.*;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Pure;
import okay.effects.java.Op;
import okay.effects.java.std.Emit;
import okay.effects.java.std.Throws;

/** A FAR SIDE, as an effect: what a program of this side asks of it. The operations are the wire's moves and
 * nothing more; calls, objects, programs and streams are programs over them (below), and {@code Remote.run} is
 * their handler — the only place where bytes move.
 *
 * A program of a far side may fail: its row is {@code Throws<Condition>} and, under it, {@code Far} — a far
 * side's failure is raised, and a handler of {@code Throws<Condition>} decides what it means. */
public sealed interface Far {
  /** a request sent, not waited for: the number its answer will carry */
  record Send(String op, Json.Obj fields) implements Far, Op<Far, Long, Send> {}
  /** the answer to a request, waited for: {@code ok}'s value, or the far side's condition */
  record Receive(long id) implements Far, Op<Far, Throws.Result<Condition, Json>, Receive> {}
  /** the number of a new run: its continuations are forgotten when the program ends */
  record Run() implements Far, Op<Far, Long, Run> {}
  /** a call opened as a stream, with the values this side feeds it, if any: the stream's number */
  record Open(String fn, List<Json> args, int credit, Optional<Iterator<Json>> input)
      implements Far, Op<Far, Throws.Result<Condition, Long>, Open> {}
  /** the stream's next chunk, waited for and paid back with a credit; none at its end */
  record Pull(long stream) implements Far, Op<Far, Throws.Result<Condition, Optional<Json>>, Pull> {}

  private static <A> Comp<A, Eff<Throws<Condition>, ?>> orRaise(Throws.Result<Condition, A> answer) {
    return switch (answer) {
      case Throws.Ok<Condition, A>(A value) -> pure(value);
      case Throws.Err<Condition, A>(Condition c) -> Throws.raise(c);
    };
  }

  private static Comp<Json, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> ask(String op, Json.Obj fields) {
    return up(perform(new Send(op, fields))).flatMap(id -> answer(id));
  }

  /** the answer to a request, as it is on the wire; raised when it is a condition */
  private static Comp<Json, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> answer(long id) {
    return up(perform(new Receive(id))).flatMap(answer -> orRaise(answer));
  }

  private static Json arguments(List<Json> args) { return Json.of(args.stream().map(Escapes::enc).toList()); }

  /** the far function's answer */
  static Comp<Json, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> call(String fn, Json... args) {
    return up(start(fn, args)).flatMap(id -> result(id));
  }

  /** a call begun and not waited for: on a far side that speaks {@code mux}, others may be answered before it */
  static Comp<Long, Eff<Far, ?>> start(String fn, Json... args) {
    return perform(new Send("call", Json.obj("fn", Json.of(fn), "args", arguments(List.of(args)))));
  }

  /** the answer of a call begun */
  static Comp<Json, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> result(long id) {
    return answer(id).map(Escapes::dec);
  }

  /** the far function's answer kept on the far side: a ref to it */
  static Comp<Json.Ref, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> hold(String fn, Json... args) {
    Json.Obj call = Json.obj("fn", Json.of(fn), "args", arguments(List.of(args)), "held", Json.of(true));
    return ask("call", call).flatMap(answer -> Escapes.dec(answer) instanceof Json.Ref ref
        ? pure(ref)
        : Throws.raise(new Condition("WireError", "a ref was expected: " + answer.text())));
  }

  /** a method of an object held */
  static Comp<Json, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> method(Json.Ref ref, String name, Json... args) {
    Json at = Json.obj("ref", Json.of(ref.id()), "method", Json.of(name));
    return ask("call", Json.obj("fn", at, "args", arguments(List.of(args)))).map(Escapes::dec);
  }

  /** an attribute of an object held */
  static Comp<Json, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> attr(Json.Ref ref, String name) {
    Json at = Json.obj("ref", Json.of(ref.id()), "attr", Json.of(name));
    return ask("call", Json.obj("fn", at, "args", arguments(List.of()))).map(Escapes::dec);
  }

  /** what the ref names is dropped on the far side */
  static Comp<Void, Eff<Throws<Condition>, ? extends Eff<Far, ?>>> release(Json.Ref ref) {
    return ask("release", Json.obj("ref", Json.of(ref.id()))).map(done -> null);
  }

  /** THE FAR PROGRAM {@code fn}, as a program of this side: what it asks is asked here as a {@code Foreign.Call},
   * and answered by this side's handlers — once, many times, or never. Its row: {@code Foreign}, over a far side
   * that may fail. */
  static Comp<Json, Eff<Foreign, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> program(
      String fn, List<Json> args) {
    return program(fn, args, List.of());
  }

  /** the same; {@code callbacks} names what this side offers, for a far side that checks */
  static Comp<Json, Eff<Foreign, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> program(
      String fn, List<Json> args, List<String> callbacks) {
    return up(up(perform(new Run()))).flatMap(number -> {
      Json.Obj request = Json.obj("run", Json.of(number), "fn", Json.of(fn), "args", arguments(args));
      Json offered = Json.of(callbacks.stream().map(Json::of).toList());
      return up(ask("program", callbacks.isEmpty() ? request : request.with("callbacks", offered)))
          .flatMap(ok -> node(number, ok));
    });
  }

  /** where the far program came to: its value, or a question — answered here, and the far program continued */
  private static Comp<Json, Eff<Foreign, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> node(
      long run, Json ok) {
    if (!(ok.get("perform") instanceof Json.Str(String name))) return pure(Escapes.dec(ok.get("done")));
    List<Json> args = Escapes.items(ok.get("args")).stream().map(Escapes::dec).toList();
    return perform(new Foreign.Call(name, args)).flatMap(answer ->
        up(ask("continue", Json.obj("run", Json.of(run), "k", ok.get("k"), "answer", Escapes.enc(answer))))
            .flatMap(next -> node(run, next)));
  }

  /** THE FAR FUNCTION'S STREAM, as a stream of this side: it sends what the far side sends. The far side runs at
   * most {@code credit} chunks ahead of whoever takes them here. Its row: the stream, over a far side that may
   * fail. */
  static Comp<Void, Eff<Emit<Json>, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> stream(
      String fn, List<Json> args, int credit) {
    return opened(new Open(fn, args, credit, Optional.empty()));
  }

  /** the same, and the stream {@code input} is fed to the far function, under the far side's credit, while this
   * one is pulled */
  static <A> Comp<Void, Eff<Emit<Json>, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> stream(
      String fn, List<Json> args, int credit, Comp<A, ? super Eff<Emit<Json>, Pure>> input) {
    return defer(() -> opened(new Open(fn, args, credit, Optional.of(Emit.pull(input)))));
  }

  private static Comp<Void, Eff<Emit<Json>, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> opened(
      Open open) {
    return up(up(perform(open))).flatMap(answer -> up(orRaise(answer)).flatMap(stream -> chunks(stream)));
  }

  private static Comp<Void, Eff<Emit<Json>, ? extends Eff<Throws<Condition>, ? extends Eff<Far, ?>>>> chunks(
      long stream) {
    return up(up(perform(new Pull(stream)))).flatMap(answer -> up(orRaise(answer)).flatMap(chunk ->
        chunk.isPresent() ? Emit.send(Escapes.dec(chunk.get())).then(() -> chunks(stream)) : pure(null)));
  }
}
