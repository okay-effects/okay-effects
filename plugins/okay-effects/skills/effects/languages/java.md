# okay-java

Repo: <https://github.com/okay-effects/okay-java> (README.md, `docs/rows.md`, `resumable/README.md`). Java 25. Two
styles over one machine:

- `okay.rows` — programs as data (`Comp<A, R>`), the row a nested type `Eff<E1, Eff<E2, ?>>`; multi-shot `k`.
- `okay.resumable` — direct style: plain Java methods whose effects are checked exceptions (`throws Prices`),
  turned into resumable code by a Gradle/Maven bytecode plugin; handlers are the same `okay.rows` handlers.

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

## okay.rows

From `src/test/java/okay/rows/Properties.java`:

```java
import static okay.rows.Effects.*;          // handle, pure, perform, run, up
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
