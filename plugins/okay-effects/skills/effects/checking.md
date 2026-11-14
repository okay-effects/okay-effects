# Checking the effect boundary

The row says what a program *declares*. These tools check that business code does not reach the world anyway.

## okay-audit (JVM bytecode, any JVM language)

Reads compiled classes (constant pool, native methods) and checks them against layers: a `business` module may
reference no API past the boundary (network, files, SQL, processes, time, randomness, threads, reflection, class
loading, native code); `handlers` and `runtime` modules are listed by provider (a dependency inventory). Presets
named after the standard effects (`state`, `reader`, `writer`, `clock`, `fresh`, `async`, `io`, `nested-run`,
`escape`; `pure`, `strict`) make each finding name the effect to use instead. Docs:
<https://github.com/okay-effects/okay/blob/master/okay-audit/README.md>, spec `specs/okay-audit.md` and
`specs/audit-cli-standalone.md` in the same repository.

```scala
// sbt
resolvers += "okay" at "https://okay-effects.github.io/okay/maven"
libraryDependencies += "io.github.okay-effects" %% "okay-audit" % "0.1.2"
```

As a CLI from any build (Maven `exec`, Gradle `JavaExec`, a CI step):

```sh
cs launch -r https://okay-effects.github.io/okay/maven io.github.okay-effects:okay-audit_3:0.1.2 \
  -M okay.audit.Main -- --manifest audit.json --report build/audit
```

```json
{
  "modules": [{
    "name": "orders",
    "layer": "business",
    "paths": ["target/classes"],
    "packages": { "com.example.orders.adapter": "handlers" }
  }],
  "allows": [{ "module": "orders", "api": "com.example.legacy.", "owner": "architecture", "reason": "ORD-42" }]
}
```

`layer` is `business`, `handlers`, `runtime` or `untracked`; paths are relative to the manifest. Optional:
`"rules": "default, state, nested-run"`, `"allowlist"`, `"types"` (only first-order data crosses into business).
It writes `report.txt` and `report.json`; exit 0 = pass, 1 = findings, 2 = malformed input. Put it in CI and keep
business packages in the `business` layer, handlers in `handlers`.

## okay-java: guard and forbid

The `okay.resumable` Gradle/Maven plugin checks the effectful code it transforms: `guard = "error"` refuses calls
that bypass effects (a handler's clause called directly), `forbid = "strict, pure"` refuses ambient APIs, naming
the effect to use instead. Syntax and presets: [languages/java.md](languages/java.md#guard-and-forbid-plugin-options).

## Compilers

Where the library has rows in types (okay-scala, okay-simple, okay-java `okay.rows`, the reference kernels,
okay-rust, okay-lean, okay-zig's `needs`), `run` on a non-empty row does not compile, and sensitive effects outside
their audit do not compile (reference kernels, okay-rust, okay-lean, okay-ts by type). Never defeat this with
casts (`asInstanceOf`, raw types, `unsafe`, `any`).

## okay-safe (the strict experiment)

<https://github.com/okay-effects/okay-safe>: research kernel in the same model on Scala 3 nightly with safe mode,
capture and separation checking, plus a whole-program TASTy check (default-deny allowlist; programs may hold only
data, effects and ids) and a gate that seals a checked deployment. Not released (0.1.0-SNAPSHOT, needs a Scala
nightly and JDK 26). Use it to see how far the guarantees go (`docs/research.md`), not as a production dependency.
