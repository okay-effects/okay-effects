# okay-direct

Repo: <https://github.com/okay-effects/okay-direct> (README.md, `docs/getting-started.md`). Java 25 direct style:
a program is ordinary Java code that receives capabilities (`Effect<E>`); continuations come from Loom
(`okay-direct-loom`), plain threads (`okay-direct-threads`, no flags) or Espresso (multi-shot). Scala 3/2 fronts
(`okay-direct-scala`) and cats/ZIO adapters exist.

```kotlin
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-direct/maven")
    maven("https://okay-effects.github.io/okay-java/maven")   // okay-java-api, the vocabulary
}
dependencies {
    implementation("io.github.okay-effects:okay-direct-std:0.1.0")
    implementation("io.github.okay-effects:okay-direct-loom:0.1.0")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
```

Loom needs, at run time: `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED
--add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`.

Effect, program, handler, run (from `tck/.../DetachedEffectsTck.java` and getting-started; the handler answers from
a map here instead of a remote service):

```java
import java.util.*;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effect;
import okay.effect.Op;
import okay.direct.Effects;
import okay.direct.InPlace;
import okay.direct.loom.LoomEffects;
import okay.direct.std.Throws;

sealed interface Prices {
    record Price(String item) implements Prices, Op<Prices, Optional<Integer>, Price> {}
}

static int total(Effect<Prices> prices, Effect<Throws<String>> fail, List<String> cart) {   // the program
    return cart.stream()
        .mapToInt(item -> prices.perform(new Prices.Price(item))
            .orElseGet(() -> Throws.raise(fail, "no price for " + item)))
        .sum();
}

static <A> A pricesFrom(Effects fx, Map<String, Integer> table, Body<Prices, A, RuntimeException> body) {
    return fx.handle(new InPlace<Prices, A, A>() {                                     // the handler
        public A ret(A result) { return result; }
        public void answer(Prices op, Answer<Prices> k) {
            switch (op) {
                case Prices.Price p -> k.answer(p, Optional.ofNullable(table.get(p.item())));
            }
        }
    }, body);
}

Effects fx = new LoomEffects();   // or new ThreadEffects(); created once, at the entry
var result = Throws.run(fx, String.class, fail -> pricesFrom(fx, table, prices -> total(prices, fail, cart)));
// Success[value=...], or Failure[error=no price for coffee]
```

Other handlers: `Handler` with a one-shot `Resume k`, `StatefulHandler`, `MultiHandler`.

Multi-shot: only on Espresso (`MultiEffects fx = new EspressoEffects();` with `MultiHandler`/`MultiResume`, run on
Espresso's `bin/java` with `--experimental-options --java.Continuum=true`). Loom and threads are one-shot, and
`Choose.all` does not compile against them. Frames are copied per resumption, the heap is shared: keep per-branch
state in a `StatefulHandler`.

```java
List<List<Boolean>> all = Choose.all(fx, choose -> List.of(Choose.flip(choose), Choose.flip(choose)));
// [[true, true], [true, false], [false, true], [false, false]]
```
