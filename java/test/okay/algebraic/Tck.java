package okay.algebraic;

import static okay.algebraic.Comp.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okay.algebraic.std.Choose;

/** THE FAMILY'S TCK (okay-kernel, tck/Tck.scala): a thousand random programs — handlers of logs, states, failures and
 * choices nested in any order, each effect under two identities — and the digest of their outcomes. The digest is the
 * reference machine's; the Scala and the Rust kernels of this repository come to the same number. */
final class Tck {
  sealed interface T {}
  record Pure(int n) implements T {}
  record Tell(int e, int n) implements T {}
  record Get(int e) implements T {}
  record Put(int e, int n) implements T {}
  record Among(List<Integer> ns) implements T {}
  record Raise(int e, int n) implements T {}
  record Seq(T a, T b) implements T {}
  record Add(T a, T b) implements T {}
  record WriterH(int e, T body) implements T {}
  record StateH(int e, int s0, T body) implements T {}
  record ThrowsH(int e, T body) implements T {}
  record ChooseH(T body) implements T {}

  static T gen(Random r, int depth) {
    if (depth <= 0 || r.nextInt(4) == 0) {
      return switch (r.nextInt(6)) {
        case 0 -> new Pure(r.nextInt(10));
        case 1 -> new Tell(r.nextInt(2), r.nextInt(10));
        case 2 -> new Get(r.nextInt(2));
        case 3 -> new Put(r.nextInt(2), r.nextInt(10));
        case 4 -> {
          List<Integer> ns = new ArrayList<>();
          for (int i = 1 + r.nextInt(2); i > 0; i--) ns.add(r.nextInt(10));
          yield new Among(ns);
        }
        default -> new Raise(r.nextInt(2), r.nextInt(10));
      };
    }
    return switch (r.nextInt(6)) {
      case 0 -> new Seq(gen(r, depth - 1), gen(r, depth - 1));
      case 1 -> new Add(gen(r, depth - 1), gen(r, depth - 1));
      case 2 -> new WriterH(r.nextInt(2), gen(r, depth - 1));
      case 3 -> new StateH(r.nextInt(2), r.nextInt(10), gen(r, depth - 1));
      case 4 -> new ThrowsH(r.nextInt(2), gen(r, depth - 1));
      default -> new ChooseH(gen(r, depth - 1));
    };
  }

  // the effects: an identity `e` in the operation — a handler answers its own, and asks any other again outside
  sealed interface Log {}
  record TellOp(int e, int n) implements Log, Op<Void> {}
  sealed interface Cell {}
  record GetOp(int e) implements Cell, Op<Integer> {}
  record PutOp(int e, int n) implements Cell, Op<Void> {}
  sealed interface Fail {}
  record RaiseOp(int e, int n) implements Fail, Op<Integer> {}

  record Summed(int sum, int value) {}

  static Comp<Integer> writer(int e, Comp<Integer> p) {
    Handler<Log, Integer, Summed> h = Handler.of(
        Log.class,
        a -> pure(new Summed(0, a)),
        (op, k) -> switch (op) {
          case TellOp tell when tell.e() == e ->
              k.resume(tell, null).map(l -> new Summed(l.sum() + tell.n(), l.value()));
          case TellOp other -> k.pass();
        });
    return handle(h, p).map(l -> l.value() * 100 + l.sum());
  }

  /** the TCK's state is a cell, made where the handler is installed: one for every resumption taken past it */
  static Comp<Integer> state(int e, int s0, Comp<Integer> p) {
    int[] s = {s0};
    Handler<Cell, Integer, Integer> h = Handler.of(
        Cell.class,
        a -> pure(a * 10 + s[0]),
        (op, k) -> switch (op) {
          case GetOp get when get.e() == e -> k.resume(get, s[0]);
          case PutOp put when put.e() == e -> { s[0] = put.n(); yield k.resume(put, null); }
          case GetOp other -> k.pass();
          case PutOp other -> k.pass();
        });
    return handle(h, p);
  }

  static Comp<Integer> fails(int e, Comp<Integer> p) {
    Handler<Fail, Integer, Integer> h = Handler.of(
        Fail.class,
        Comp::pure,
        (op, k) -> switch (op) {
          case RaiseOp raise when raise.e() == e -> pure(-1 - raise.n());
          case RaiseOp other -> k.pass();
        });
    return handle(h, p);
  }

  static Comp<Integer> build(T t) {
    return switch (t) {
      case Pure pure -> pure(pure.n());
      case Tell tell -> perform(new TellOp(tell.e(), tell.n())).map(done -> tell.n());
      case Get get -> perform(new GetOp(get.e()));
      case Put put -> perform(new PutOp(put.e(), put.n())).map(done -> put.n());
      case Among among -> Choose.among(among.ns());
      case Raise raise -> perform(new RaiseOp(raise.e(), raise.n()));
      case Seq seq -> build(seq.a()).then(() -> build(seq.b()));
      case Add add -> build(add.a()).flatMap(i -> build(add.b()).map(j -> i + j));
      case WriterH w -> writer(w.e(), build(w.body()));
      case StateH s -> state(s.e(), s.s0(), build(s.body()));
      case ThrowsH f -> fails(f.e(), build(f.body()));
      case ChooseH c -> Choose.all(build(c.body())).map(vs -> vs.stream().mapToInt(v -> v).sum());
    };
  }

  static int outcome(T t) {
    return switch (build(t).step()) {
      case Step.Done<Integer> done -> done.value();
      case Step.Asked<?, Integer> asked -> -1000000; // no handler
    };
  }

  static long digest() {
    Random r = new Random(20261010L);
    long d = 0;
    for (int i = 0; i < 1000; i++) {
      T t = gen(r, 6);
      t = new StateH(0, 0, new StateH(1, 0, new WriterH(0, new WriterH(1, t))));
      t = new ChooseH(new ThrowsH(0, new ThrowsH(1, t)));
      d = d * 31 + outcome(t);
    }
    return d;
  }

  static final long DIGEST = -8623788747993822161L;
}
