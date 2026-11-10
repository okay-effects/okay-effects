package okay.effects.java;

import static okay.effects.java.Effects.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okay.effects.java.std.Choose;

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

  // the effects: an identity `e` in the operation — a handler answers its own (`mine`), any other passes outside
  sealed interface Log {}
  record TellOp(int e, int n) implements Log, Op<Log, Void> {}
  sealed interface Store {}
  record GetOp(int e) implements Store, Op<Store, Integer> {}
  record PutOp(int e, int n) implements Store, Op<Store, Void> {}
  sealed interface Fail {}
  record RaiseOp(int e, int n) implements Fail, Op<Fail, Integer> {}

  /** The four effects stay in the row of every program, whatever handlers it is under: the evidence of them. */
  record Row<R>(In<Log, R> log, In<Store, R> store, In<Fail, R> fail, In<Choose, R> choose) {}

  record Summed(int sum, int value) {}

  static <R> Comp<R, Integer> writer(Row<R> in, int e, Comp<R, Integer> p) {
    return intercept(Log.class, in.log(), new Handler<Log, Integer, Summed, R>() {
      public boolean mine(Log op) {
        return switch (op) {
          case TellOp tell -> tell.e() == e;
        };
      }
      public Comp<R, Summed> ret(Integer a) { return pure(new Summed(0, a)); }
      public Comp<R, Summed> on(Log op, K<R, Summed> k) {
        return switch (op) {
          case TellOp tell -> k.resume(tell, null).map(l -> new Summed(l.sum() + tell.n(), l.value()));
        };
      }
    }, p).map(l -> l.value() * 100 + l.sum());
  }

  /** the TCK's state is a cell made where the handler is installed: one for every resumption taken past it —
   * not the state of {@code std.State}, which each path has its own */
  static <R> Comp<R, Integer> state(Row<R> in, int e, int s0, Comp<R, Integer> p) {
    int[] s = {s0};
    return intercept(Store.class, in.store(), new InPlace<Store, Void, Integer, Integer, R>() {
      public boolean mine(Store op) {
        return switch (op) {
          case GetOp get -> get.e() == e;
          case PutOp put -> put.e() == e;
        };
      }
      public Comp<R, Integer> ret(Void state, Integer a) { return pure(a * 10 + s[0]); }
      public Cell.Answered answer(Cell<Void> state, Store op) {
        return switch (op) {
          case GetOp get -> state.answer(get, s[0]);
          case PutOp put -> { s[0] = put.n(); yield state.answer(put, null); }
        };
      }
    }, null, p);
  }

  static <R> Comp<R, Integer> fails(Row<R> in, int e, Comp<R, Integer> p) {
    return intercept(Fail.class, in.fail(), new Handler<Fail, Integer, Integer, R>() {
      public boolean mine(Fail op) {
        return switch (op) {
          case RaiseOp raise -> raise.e() == e;
        };
      }
      public Comp<R, Integer> ret(Integer a) { return pure(a); }
      public Comp<R, Integer> on(Fail op, K<R, Integer> k) {
        return switch (op) {
          case RaiseOp raise -> pure(-1 - raise.n());
        };
      }
    }, p);
  }

  static <R> Comp<R, Integer> build(Row<R> in, T t) {
    return switch (t) {
      case Pure pure -> pure(pure.n());
      case Tell tell -> perform(in.log(), new TellOp(tell.e(), tell.n())).map(done -> tell.n());
      case Get get -> perform(in.store(), new GetOp(get.e()));
      case Put put -> perform(in.store(), new PutOp(put.e(), put.n())).map(done -> put.n());
      case Among among -> perform(in.choose(), new Choose.Among<>(among.ns()));
      case Raise raise -> perform(in.fail(), new RaiseOp(raise.e(), raise.n()));
      case Seq seq -> build(in, seq.a()).then(() -> build(in, seq.b()));
      case Add add -> build(in, add.a()).flatMap(i -> build(in, add.b()).map(j -> i + j));
      case WriterH w -> writer(in, w.e(), build(in, w.body()));
      case StateH s -> state(in, s.e(), s.s0(), build(in, s.body()));
      case ThrowsH f -> fails(in, f.e(), build(in, f.body()));
      case ChooseH c -> {
        // the body's row has a choice already: this handler is nearer, and answers it
        Comp<R, List<Integer>> all = Choose.all(nearer -> build(in, c.body()).up());
        yield all.map(vs -> vs.stream().mapToInt(v -> v).sum());
      }
    };
  }

  static <R> int outcome(Row<R> in, T t) {
    return switch (step(build(in, t))) {
      case Step.Done<R, Integer> done -> done.value();
      case Step.Asked<R, ?, Integer> asked -> -1000000; // no handler
    };
  }

  static long digest() {
    Row<Cons<Log, Cons<Store, Cons<Fail, Cons<Choose, Nil>>>>> in =
        new Row<>(In.here(), up(In.here()), up(up(In.here())), up(up(up(In.here()))));
    Random r = new Random(20261010L);
    long d = 0;
    for (int i = 0; i < 1000; i++) {
      T t = gen(r, 6);
      t = new StateH(0, 0, new StateH(1, 0, new WriterH(0, new WriterH(1, t))));
      t = new ChooseH(new ThrowsH(0, new ThrowsH(1, t)));
      d = d * 31 + outcome(in, t);
    }
    return d;
  }

  static final long DIGEST = -8623788747993822161L;
}
