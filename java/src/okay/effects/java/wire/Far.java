package okay.effects.java.wire;

import static okay.effects.java.Effects.*;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import okay.effects.java.Body;
import okay.effects.java.Comp;
import okay.effects.java.Cons;
import okay.effects.java.In;
import okay.effects.java.Nil;
import okay.effects.java.Op;
import okay.effects.java.std.Emit;
import okay.effects.java.std.Throws;

/** A FAR SIDE, as an effect: what a program of this side asks of it. The operations are the wire's moves and
 * nothing more; calls, objects, programs and streams are programs over them (below), and {@code Remote.run} is
 * their handler — the only place where bytes move. */
public sealed interface Far {
  /** a request sent, not waited for: the number its answer will carry */
  record Send(String op, Json.Obj fields) implements Far, Op<Far, Long> {}
  /** the answer to a request, waited for: {@code ok}'s value, or the far side's condition */
  record Receive(long id) implements Far, Op<Far, Throws.Result<Condition, Json>> {}
  /** the number of a new run: its continuations are forgotten when the program ends */
  record Run() implements Far, Op<Far, Long> {}
  /** a call opened as a stream, with the values this side feeds it, if any: the stream's number */
  record Open(String fn, List<Json> args, int credit, Optional<Iterator<Json>> input)
      implements Far, Op<Far, Throws.Result<Condition, Long>> {}
  /** the stream's next chunk, waited for and paid back with a credit; none at its end */
  record Pull(long stream) implements Far, Op<Far, Throws.Result<Condition, Optional<Json>>> {}

  /** EVIDENCE for a program of a far side: a far side's failure is raised here, and a handler of
   * {@code Throws<Condition>} decides what it means. */
  record Failing<R>(In<Far, R> far, In<Throws<Condition>, R> fails) {
    /** the same evidence, under one handler more */
    public <F> Failing<Cons<F, R>> up() { return new Failing<>(far.up(), fails.up()); }
  }

  private static <R, A> Comp<R, A> orRaise(In<Throws<Condition>, R> fails, Throws.Result<Condition, A> answer) {
    return switch (answer) {
      case Throws.Ok<Condition, A>(A value) -> pure(value);
      case Throws.Err<Condition, A>(Condition c) -> Throws.raise(fails, c);
    };
  }

  private static <R> Comp<R, Json> ask(Failing<R> in, String op, Json.Obj fields) {
    return perform(in.far(), new Send(op, fields)).flatMap(id -> answer(in, id));
  }

  /** the answer to a request, as it is on the wire; raised when it is a condition */
  private static <R> Comp<R, Json> answer(Failing<R> in, long id) {
    return perform(in.far(), new Receive(id)).flatMap(answer -> orRaise(in.fails(), answer));
  }

  private static Json arguments(List<Json> args) { return Json.of(args.stream().map(Escapes::enc).toList()); }

  /** the far function's answer */
  static <R> Comp<R, Json> call(Failing<R> in, String fn, Json... args) {
    return start(in.far(), fn, args).flatMap(id -> result(in, id));
  }

  /** a call begun and not waited for: on a far side that speaks {@code mux}, others may be answered before it */
  static <R> Comp<R, Long> start(In<Far, R> far, String fn, Json... args) {
    return perform(far, new Send("call", Json.obj("fn", Json.of(fn), "args", arguments(List.of(args)))));
  }

  /** the answer of a call begun */
  static <R> Comp<R, Json> result(Failing<R> in, long id) {
    return answer(in, id).map(Escapes::dec);
  }

  /** the far function's answer kept on the far side: a ref to it */
  static <R> Comp<R, Json.Ref> hold(Failing<R> in, String fn, Json... args) {
    Json.Obj call = Json.obj("fn", Json.of(fn), "args", arguments(List.of(args)), "held", Json.of(true));
    return ask(in, "call", call).flatMap(answer -> Escapes.dec(answer) instanceof Json.Ref ref
        ? pure(ref)
        : Throws.raise(in.fails(), new Condition("WireError", "a ref was expected: " + answer.text())));
  }

  /** a method of an object held */
  static <R> Comp<R, Json> method(Failing<R> in, Json.Ref ref, String name, Json... args) {
    Json at = Json.obj("ref", Json.of(ref.id()), "method", Json.of(name));
    return ask(in, "call", Json.obj("fn", at, "args", arguments(List.of(args)))).map(Escapes::dec);
  }

  /** an attribute of an object held */
  static <R> Comp<R, Json> attr(Failing<R> in, Json.Ref ref, String name) {
    Json at = Json.obj("ref", Json.of(ref.id()), "attr", Json.of(name));
    return ask(in, "call", Json.obj("fn", at, "args", arguments(List.of()))).map(Escapes::dec);
  }

  /** what the ref names is dropped on the far side */
  static <R> Comp<R, Void> release(Failing<R> in, Json.Ref ref) {
    return ask(in, "release", Json.obj("ref", Json.of(ref.id()))).map(done -> null);
  }

  /** THE FAR PROGRAM {@code fn}, as a program of this side: what it asks is asked here as a {@code Foreign.Call},
   * and answered by this side's handlers — once, many times, or never. */
  static <R> Comp<R, Json> program(Failing<R> in, In<Foreign, R> host, String fn, List<Json> args) {
    return program(in, host, fn, args, List.of());
  }

  /** the same; {@code callbacks} names what this side offers, for a far side that checks */
  static <R> Comp<R, Json> program(
      Failing<R> in, In<Foreign, R> host, String fn, List<Json> args, List<String> callbacks) {
    return perform(in.far(), new Run()).flatMap(number -> {
      Json.Obj request = Json.obj("run", Json.of(number), "fn", Json.of(fn), "args", arguments(args));
      Json offered = Json.of(callbacks.stream().map(Json::of).toList());
      return ask(in, "program", callbacks.isEmpty() ? request : request.with("callbacks", offered))
          .flatMap(ok -> node(in, host, number, ok));
    });
  }

  /** where the far program came to: its value, or a question — answered here, and the far program continued */
  private static <R> Comp<R, Json> node(Failing<R> in, In<Foreign, R> host, long run, Json ok) {
    if (!(ok.get("perform") instanceof Json.Str(String name))) return pure(Escapes.dec(ok.get("done")));
    List<Json> args = Escapes.items(ok.get("args")).stream().map(Escapes::dec).toList();
    return perform(host, new Foreign.Call(name, args))
        .flatMap(answer -> ask(in, "continue",
            Json.obj("run", Json.of(run), "k", ok.get("k"), "answer", Escapes.enc(answer))))
        .flatMap(next -> node(in, host, run, next));
  }

  /** THE FAR FUNCTION'S STREAM, as a stream of this side: it sends what the far side sends. The far side runs at
   * most {@code credit} chunks ahead of whoever takes them here. */
  static <R> Comp<R, Void> stream(Failing<R> in, In<Emit<Json>, R> out, String fn, List<Json> args, int credit) {
    return opened(in, out, new Open(fn, args, credit, Optional.empty()));
  }

  /** the same, and the stream {@code input} is fed to the far function, under the far side's credit, while this
   * one is pulled */
  static <R, A> Comp<R, Void> stream(
      Failing<R> in, In<Emit<Json>, R> out, String fn, List<Json> args, int credit, Body<Emit<Json>, Nil, A> input) {
    return defer(() -> opened(in, out, new Open(fn, args, credit, Optional.of(Emit.pull(input)))));
  }

  private static <R> Comp<R, Void> opened(Failing<R> in, In<Emit<Json>, R> out, Open open) {
    return perform(in.far(), open).flatMap(answer -> orRaise(in.fails(), answer))
        .flatMap(stream -> chunks(in, out, stream));
  }

  private static <R> Comp<R, Void> chunks(Failing<R> in, In<Emit<Json>, R> out, long stream) {
    return perform(in.far(), new Pull(stream)).flatMap(answer -> orRaise(in.fails(), answer)).flatMap(chunk ->
        chunk.isPresent() ? Emit.send(out, Escapes.dec(chunk.get())).then(() -> chunks(in, out, stream)) : pure(null));
  }
}
