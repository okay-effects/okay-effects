package okay.effects.java.bench;

import static okay.effects.java.Effects.*;

import java.util.List;
import okay.effects.java.Comp;
import okay.effects.java.Effects;
import okay.effects.java.In;
import okay.effects.java.std.Choose;
import okay.effects.java.std.Reader;
import okay.effects.java.std.State;
import okay.effects.java.std.Writer;

/** A rough measure, the same in the three languages: nanoseconds an operation, the best of several rounds.
 * {@code state}: get and put in a loop. {@code reader3}: an ask answered three handlers away. {@code choose}: a
 * continuation taken twice at every step of a tree. {@code binds}: a loop of nothing but binds. */
public final class Bench {
  static <R> Comp<R, Integer> count(In<State<Integer>, R> st, int n) {
    if (n == 0) return State.get(st);
    return State.get(st).flatMap(s -> State.put(st, s + 1)).flatMap(done -> count(st, n - 1));
  }

  static <R> Comp<R, Integer> asks(In<Reader<Integer>, R> rd, int n, int sum) {
    return n == 0 ? pure(sum) : Reader.ask(rd).flatMap(x -> asks(rd, n - 1, sum + x));
  }

  static <R> Comp<R, Integer> tree(In<Choose, R> ch, int depth) {
    return depth == 0 ? pure(1) : Choose.among(ch, List.of(0, 1)).flatMap(x -> tree(ch, depth - 1));
  }

  static <R> Comp<R, Integer> binds(int n, int sum) {
    return n == 0 ? pure(sum) : Effects.<R, Integer>pure(1).flatMap(x -> binds(n - 1, sum + x));
  }

  static void measure(String name, int ops, Runnable body) {
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 7; round++) {
      long start = System.nanoTime();
      body.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    System.out.println(String.format("%-8s %7.1f ns/op", name, (double) best / ops));
  }

  public static void main(String[] args) {
    int n = 1_000_000;
    measure("state", 2 * n, () -> run(State.run(0, st -> count(st, n))));
    measure("reader3", n, () -> run(Reader.run(1, rd ->
        State.run(0, s1 -> State.run("", s2 -> Writer.run(String.class, wr -> asks(up(up(up(rd))), n, 0)))))));
    measure("choose", 1 << 18, () -> run(Choose.all(ch -> tree(ch, 18))).size());
    measure("binds", n, () -> run(binds(n, 0)));
  }
}
