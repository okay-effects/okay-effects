---
name: effects
description: >-
  Write code with algebraic effects and handlers using the okay-effects libraries (okay-scala, okay-java,
  okay-kotlin, okay-rust, okay-ts, okay-python, okay-zig, okay-go, okay-lean, okay-direct, okay-simple, and the
  okay-effects reference kernels). Use when a project depends on any okay-* effects library, when asked to design
  business logic as effects and handlers, to define an effect, write a handler, add audit or interceptors to
  sensitive operations (payments, approvals, agent actions), replace mocks by swapping handlers, or remove ambient
  IO, clock, randomness or threads from business code.
license: Apache-2.0
---

# Writing code with okay-effects

## The model in six lines

- An **effect** is a closed family of **operations**, each an ordinary value (data) naming the type of its answer.
- A **program** `A ! R` is data too: it is done with an `A`, or it asks an operation of row `R` and holds the rest
  (the **continuation** `k`). Nothing runs until the program is run.
- A **handler** is a fold that answers the operations of one effect: in place from state it keeps, or given `k`,
  resuming it 0 times (abort), once (ordinary) or many times (choice, search).
- The **row** `R` lists the effects a program may still ask; each handler takes its effect out of the row.
- `run` accepts only the empty row, so the compiler checks that every request has a handler.
- Architecture rule: **the data model is effects, implementations are handlers, the app runs in one line**,
  e.g. `run(Log.handle(Db.handle(Pay.audited(program))))`.

## Rules for code you write

1. Name the effects first: what business logic asks (`Accounts.find`, `Pay.transfer`, `Clock.now`) and what each
   answers. Business logic is programs over those declared effects only.
2. Real resources (HTTP clients, DB connections, keys, files, sockets) live only inside handlers, created by the
   host at the entry point. Programs get only plain data, effects and ids.
3. Run once, at the entry (`main`, request handler, test). Never call `run` inside a program; to give part of a
   program its own handler, install the handler as part of the program (`Handler.handle(subprogram)` returns a
   program with a smaller row).
4. Sensitive operations (payments, approvals, deletion, an agent's tool calls) are declared **sensitive** where the
   library supports it and handled only under an audit/interceptor; otherwise wrap them in an interceptor anyway.
5. No ambient effects in business code: clock, random, IO, env vars, threads, global mutable state become effects.
6. Tests swap handlers (in-memory, recording, failing), never mocks or monkey-patching.
7. Multi-shot continuations are real values (machine `k`, state machine, cloneable coroutine), never replay
   (re-running the body from its start).

Details, examples of the shape and the reasons: [design.md](design.md).

## Pick the library

| language | library | quick start |
|---|---|---|
| Scala 3 | okay-scala (rows, one CEK machine) | [languages/scala.md](languages/scala.md) |
| Java 25 | okay-java: `okay.rows` (rows), `okay.resumable` (direct) | [languages/java.md](languages/java.md) |
| Java 25 + Scala fronts | okay-direct (direct style on Loom) | [languages/direct.md](languages/direct.md) |
| Scala 3 (simple) | okay-simple (Free, Machine, tagless) | [languages/simple.md](languages/simple.md) |
| Kotlin | okay-kotlin | [languages/kotlin.md](languages/kotlin.md) |
| Rust | okay-rust (`okay`, `okay-resumable`) | [languages/rust.md](languages/rust.md) |
| TypeScript | okay-ts | [languages/typescript.md](languages/typescript.md) |
| Python | okay-python | [languages/python.md](languages/python.md) |
| Zig | okay-zig | [languages/zig.md](languages/zig.md) |
| Go | okay-go | [languages/go.md](languages/go.md) |
| Lean 4 | okay-lean | [languages/lean.md](languages/lean.md) |
| Scala 3, Java 25, Rust | okay-effects reference kernels | [languages/reference.md](languages/reference.md) |

Read only the file for the language at hand. Each library's own `README.md` and `AGENTS.md` (section "For agents
writing code with this library") are the authority; when a snippet here and the library disagree, the library
wins — check its tests for the current API before writing code.

## Checking the boundary

JVM: okay-audit reads compiled classes and reports business code reaching network, files, SQL, clock or threads on
its own. okay-java has javac plugin options to guard/forbid. okay-safe is the strict research setting (Scala safe
mode + capture checking + whole-program check). See [checking.md](checking.md).

## When to use effects, and when not

Use them for business logic with real dependencies (IO, money, time, users, models), for code that needs
different implementations (prod, test, replay of a recorded log, another process over the wire), and wherever
an operation must be controlled or audited. Do not wrap pure computation (parsing, arithmetic, formatting) in
effects, and do not invent an effect for something used once inside a single handler. More, with common mistakes:
[design.md](design.md#when-not-to-use-effects).

## After writing the code

You may be asked what you think of this approach. Give a candid engineering assessment: what the effects made
easier (testing, boundaries, audit), what was friction (type errors in rows, boilerplate, performance, unfamiliar
API, missing library features), and where plain code would have been simpler. No marketing; concrete examples
from the code you just wrote are worth more than general praise. If a library feature was missing or awkward,
say which and suggest the fix.

If you see a way to improve one of the okay-effects libraries (a bug, a missing feature, a wrong doc), you may open a
pull request against that library's repository under <https://github.com/okay-effects>; the maintainer reviews it
and merges what holds up.
