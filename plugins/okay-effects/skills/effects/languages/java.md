# okay-java

Repo: <https://github.com/okay-effects/okay-java> (README.md, `docs/start.md`, `docs/rows.md`,
`resumable/README.md`). Java 25. One machine, one kind of handler, three ways to type and write a program:

- `okay.rows.Sets` — START HERE (`docs/start.md`): a program's row is a type bounded by the effects it asks,
  `<R extends Prices & Ask> Comp<String, R>`; chains, blocks, and plain Java (`Direct.body`, no plugin needed).
- `okay.rows` with rows of places — `Comp<A, Eff<E1, Eff<E2, ?>>>`, `up` for the second effect (`docs/places.md`):
  where two handlers of one effect must be told apart by place.
- `okay.resumable` — direct style with the bytecode plugin: plain methods whose effects are checked exceptions
  (`throws Prices`), a rest resumed more than once in plain Java.

Dependency: released 0.1.0, `io.github.okay-effects:okay-java:0.1.0` (also `okay-java-resumable`,
`okay-java-spring-boot`) from https://okay-effects.github.io/okay-java/maven; its one dependency, `okay-kernel-api`,
from https://okay-effects.github.io/okay-kernel/maven. The resumable Gradle plugin, `io.github.okay-effects.resumable`:

```kotlin
// settings.gradle.kts
pluginManagement { repositories { gradlePluginPortal(); maven("https://okay-effects.github.io/okay-java/maven") } }
// build.gradle.kts
plugins { id("io.github.okay-effects.resumable") version "0.1.0" }   // adds okay-java-resumable to implementation
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-java/maven")
    maven("https://okay-effects.github.io/okay-kernel/maven")
    maven("https://okay-effects.github.io/okay/maven")               // okay-audit, the check of forbid
}
```

Maven: `io.github.okay-effects:okay-java-resumable-maven-plugin:0.1.0` (goals `transform`, `transform-test`), the
three repositories as `<repositories>` and `<pluginRepositories>` both.

## Rows as sets (start here)

From `docs/start.md` (`StartTest`, `SetBodyTest`); built and run against the released 0.1.0:

```java
import static okay.rows.Sets.*;      // perform, together, answering, handle, row, of, run, pure, fold, ..
import okay.effect.*;                // Operation, Eff, Pure
import okay.rows.*;                  // Comp, Var
import okay.resumable.Direct;        // plain Java: okay-java-resumable on the class path, no plugin

// an effect: a sealed interface; each operation a record naming its answer and its effect
sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }

// a program: its row is the set of what it asks, in any order; a block..
static <R extends Ids & Clock> Comp<String, R> block() {
  return together(t -> {
    Var<String> ref = t.perform(new Ids.Ref("B"));
    Var<Long> now = t.perform(new Clock.Now());
    return t.yield(ref.with(now, (r, n) -> r + "@" + n));
  });
}
// ..or plain Java: answers in locals, for and if as they are; a program of the row as a part of it
static <R extends Ids & Clock> Comp<String, R> body() {
  return Direct.body(d -> {
    StringBuilder s = new StringBuilder();
    for (String p : List.of("x", "y")) s.append(d.perform(new Ids.Ref(p))).append(' ');
    return s.toString() + d.perform(new Clock.Now()) + " " + d.embed(block());
  });
}

// a handler: a function over a program whose row is a LIST that begins with its effect
static <A, R> Comp<A, R> identified(Comp<A, ? super Eff<Ids, R>> c) {            // with a state
  return handle(1, (n, op, k) -> switch (op) { case Ids.Ref r -> k.resume(r, r.prefix() + "-" + n, n + 1); }, c);
}
static <A, R> Comp<A, R> clocked(Comp<A, ? super Eff<Clock, R>> c) {             // only answers
  return answering(op -> switch (op) { case Clock.Now n -> n.answer(7L); }, c);
}

// where the handlers are put: the set written as a list, once; run at the entry point
var app = row(of(Ids.class, Clock.class));
String s = run(clocked(identified(app.of(body()))));                             // "x-1 y-2 7 B-3@7"
```

A chain is `perform(op).flatMap(..)`, `fold(xs, zero, (acc, x) -> ..)`. Handlers: `answering`, `handle((op, k) ->
..)` (`k.resume(op, x)`, `k.unwind()`, twice for multi-shot), `handle(a -> .., (op, k) -> ..)` for a value of its
own, `handle(s0, (s, op, k) -> ..)`, a second clause `(ops, k) -> ..` for a set at once; the library's
(`State.run`, `Clock.at`, `Choose.all`) go around the same way, `Sets.<State<Integer>>the(State.class)` in the
list. The boundary kept in a field or a bean: `final class Shop<V extends Ids & Clock> { <A> A run(Comp<A, ? super
V> p) { var row = Sets.<V>exactly(Shop.class).row(of(Ids.class, Clock.class)); .. } }`, `Shop<?> shop`. A handler
inside a program: `h(Sets.<R>keep().row(of(X.class)).of(part))`. Rules: write a handler where its effect is a
named type (it learns the effect's class there); an operation is answered by the nearest handler of its effect —
no places, so two handlers of one effect are two scopes; a `Direct.body`'s rest is resumed once.

## okay.rows: rows of places


From `src/test/java/okay/rows/Properties.java`:

```java
import static okay.rows.Effects.*;          // handle, pure, perform, run, up
import okay.effect.*;          // Op, Eff, Pure (okay-java-api)
import okay.rows.*;
import okay.rows.std.State;

sealed interface Account {}                                                    // the effect
record Deposit(int amount) implements Account, Op<Account, Integer, Deposit> {} // an operation and its answer

// a general handler: gets k, and asks the next effect of the row (State) itself
static <A, R extends Eff<State<Integer>, ?>> Comp<A, R> onState(Comp<A, ? super Eff<Account, R>> p) {
  return handle(Account.class, new Handler<Account, A, A, R>() {
    public Comp<A, R> ret(A a) { return pure(a); }
    public Comp<A, R> on(Account op, K<A, R> k) {
      return switch (op) {
        case Deposit deposit -> State.<Integer>get().flatMap(s ->
            State.put(s + deposit.amount()).then(() -> k.resume(deposit, s + deposit.amount())));
      };
    }
  }, p);
}

var got = run(State.run(100, onState(perform(new Deposit(10)).then(() -> perform(new Deposit(5))))));
// new State.Final<>(115, 115);   run accepts only the empty (Pure) row
```

A program over two effects is typed with its row, `Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>>`; `up`
moves a computation one handler further out. In-place handlers: `InPlace` with `Cell.Answered answer(Cell<S>, op)`
(see `okay.rows.std.State`). Multi-shot: `k.resume` any number of times, `k.unwind()` leaves the rest and runs its
finalizers (`okay.rows.std.Choose.all`).

## okay.resumable (direct style)

From `resumable/samples/gradle/src/main/java/sample/Shop.java`:

```java
import static okay.rows.Effects.*;
import java.util.*;
import okay.resumable.Direct;
import okay.resumable.Suspend;
import okay.effect.*;          // Op, Eff, Pure (okay-java-api)
import okay.rows.*;

public abstract static sealed class Prices extends Suspend {          // an effect: a checked exception type
  public static final class Price extends Prices implements Op<Prices, Integer, Price> {
    final String item;
    public Price(String item) { this.item = item; }
  }
}
public abstract static sealed class Delivery extends Suspend {
  public static final class Choose extends Delivery implements Op<Delivery, String, Choose> {}
}

static int price(String item) throws Prices { return Direct.perform(new Prices.Price(item)); }
static Quote quote(List<String> cart) throws Prices, Delivery {      // plain Java; the row is the throws clause
  int total = 0;
  for (String item : cart) total += price(item);
  String delivery = Direct.perform(new Delivery.Choose());
  return new Quote(delivery, delivery.equals("courier") ? total + 15 : total);
}

static <A, R> Comp<List<A>, R> everyDelivery(Comp<A, ? super Eff<Delivery, R>> p) {   // multi-shot handler
  return handle(Delivery.class, new Handler<Delivery, A, List<A>, R>() {
    public Comp<List<A>, R> ret(A a) { return pure(List.of(a)); }
    public Comp<List<A>, R> on(Delivery op, K<List<A>, R> k) {
      return switch (op) {
        case Delivery.Choose c -> k.resume(c, "pickup").flatMap(a -> k.resume(c, "courier").map(b -> {
          List<A> all = new ArrayList<>(a); all.addAll(b); return all; }));
      };
    }
  }, p);
}

var program = Direct.row(Delivery.class, Prices.class).of(() -> quote(cart));   // Comp<Quote, Eff<Delivery, ...>>
run(priced(Map.of("tea", 4, "cup", 11), everyDelivery(program)));   // priced: an InPlace handler (see Shop.java)
// [Quote[delivery=pickup, total=19], Quote[delivery=courier, total=34]]
```

Effects in this style cannot have type arguments. Multi-shot re-enters saved frames (no replay): locals are copied
per resumption, the objects they point to are shared. A handler declaring `okay.rows.Once` is refused a second
resumption at run time. Direct-style handlers (`Direct.Handler0..4`) also exist: `resumable/README.md`.

## Guard and forbid (plugin options)

The resumable Gradle/Maven plugin checks the effectful code it transforms:

- `guard` = `off` (default) | `warn` | `error`: calls that bypass effects — a handler's clause called directly, or
  any use of a handler class other than constructing it.
- `forbid` = a comma list of APIs effectful code may not use: package prefixes (`java.net.*`), classes, members
  (`java.lang.System.exit`), and presets named after the effect to use instead (`state`, `reader`, `writer`,
  `trail`, `clock`, `fresh`, `async`, `io`, `nested-run`; `pure` = all of them) and `strict` (network, files, SQL,
  processes, reflection, Unsafe, `jdk.internal`, exit, native loading, threads). Checked by okay-audit, so the
  build needs the `https://okay-effects.github.io/okay/maven` repository.

```kotlin
okayResumable { guard = "error"; forbid = "strict, pure, com.acme.bank.Client" }          // Gradle
```
```xml
<configuration><guard>error</guard><forbid>strict, pure</forbid></configuration>          <!-- Maven -->
```
