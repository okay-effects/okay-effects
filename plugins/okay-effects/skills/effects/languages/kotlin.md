# okay-kotlin

Repo: <https://github.com/okay-effects/okay-kotlin> (README.md, AGENTS.md). Kotlin 2.4, JVM/JS/native, no
dependencies. Effects are interfaces of `suspend` functions passed as context parameters; handlers are delimited
continuations on coroutines. Package `okay.kotlin`.

```kotlin
repositories { maven("https://okay-effects.github.io/okay-kotlin/maven") }
dependencies { implementation("io.github.okay-effects:okay-kotlin:0.1.0") }
```

Effect, program, handler, run (from `core/src/commonTest/kotlin/okay/kotlin/CoreTest.kt`):

```kotlin
import okay.kotlin.*

fun interface Ask {                       // the effect: an interface of suspend operations
    suspend fun ask(): Int
}

context(a: Ask, f: Fail<String>)          // the program: its row is its context parameters
suspend fun checked(): Int {
    val x = a.ask()
    ensure(x <= 5) { "big $x" }
    return x * 2
}

// a handler that takes the rest (`k`); an in-place handler is just `context(object : Ask { ... }) { body() }`
suspend fun <A> answering(n: Int, body: suspend context(Ask) () -> A): A = handle { prompt ->
    context(Ask { prompt.shift { k -> k(n) } }) { body() }
}

runEffects { either<String, _> { answering(3) { checked() } } }   // Either.Right(6)
runEffects { answering(9) { either<String, _> { checked() } } }   // Either.Left("big 9")
```

A larger worked example (Accounts, transfer, a journaled store, an in-memory handler for tests):
`core/src/commonTest/kotlin/okay/kotlin/ReadmeTest.kt`.

Multi-shot: on JVM and JS (not native), by copying the rest's frames with `k.copy()` taken before `k` is resumed
(never replay). `all { ... }` over `Choose` collects every path (`Choose.kt`, `MultiShotTest.kt`). Limits from the
README: a delimiting handler between the choice and its handler refuses the copy (`either { all { } }` works,
`all { either { } }` is refused); heap objects are shared between copies; on the JVM a `for` over a JDK collection
needs `--add-opens java.base/java.util=ALL-UNNAMED`.
