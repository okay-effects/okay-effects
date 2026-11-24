# The concept

What algebraic effects are in the okay-effects family, the invariants every library keeps, the names every
library uses for the same thing, and how the ways of writing a program (types, direct style, parameters, exceptions,
data) are one model. [SPEC.md](SPEC.md) is the model exactly — the machine, the handlers, the laws, the row; this
file is the picture around it and the vocabulary. A library of the family agrees with both, or says here where it
does not yet.

## 1. Three nouns

- An **operation** is a request, and it is DATA: a value with the question's arguments, whose type names the type of
  its answer (`record Price(String item) implements Prices, Op<Prices, Optional<Integer>, Price>`).
- A **handler** answers the operations of one effect: in place, or with the rest of the program in hand (the
  continuation `k`), which it resumes once, many times, or never.
- A **program** asks operations and does nothing else to the world. Its type lists what it may ask: its **row**.

Running a program is choosing its handlers, in one line at the entry point: `run(Reader.run(env, State.run(0, p)))`.
What a program does is in its type; how it is done is in the handlers. A test, a production run, a recording, an
audit are different handlers of one program.

## 2. The invariants

1. **An operation is data, its answer's type in its type.** A handler that answers with a value of another type does
   not compile.
2. **An effect is closed, a handler exhaustive.** An effect is a sealed family; a handler matches all of it, with no
   default: a new operation breaks, at compile time, every handler that does not answer it.
3. **The row is a type, the nearest handler first.** One effect may be in it twice: two places. The order of the
   handlers is meaning (a state outside a choice is one state through all the ways; inside, one per way), so the
   order is in the type. Only a program of the empty row (`Pure`) runs.
4. **One source of truth.** An effect, its operations and a row are written once. Every way of writing a program
   (§4) is a view of them; none has effects of its own, and the conversions between them are total.
5. **Two shapes of handler** (SPEC §4): *in place* — answered at once from a state, no `k` taken — and *general* — the
   clause given `k` as a value. Abort, stateful, tail are cases of these two.
6. **Leaving runs finalizers.** A `k` left (`unwind`), or a rest a handler drops, runs the finalizers waiting in it
   (SPEC §7).
7. **Multi-shot is never replay.** A rest resumed many times is a value that is copied (frames, levels, a state
   machine), never the program run again from its start. An implementation that cannot copy a rest is one-shot and
   says so in a TYPE, not by failing late.
8. **Real resources only in handlers.** Programs get data, effects and ids — never live clients, the clock, randomness,
   threads or the environment, which are effects (`Clock`, `Fresh`, ...).
9. **Sensitive effects are handled only under audit.** An effect its author marks `Sensitive` is refused by `handle`;
   only `audit(interceptor)(program)` leaves `Audited` in its place, and only `handleAudited` takes that (SPEC §9).
10. **Capabilities live while their body runs.** In direct style the capability of a place is good only inside the
    handler that gave it: anywhere else it fails at once, it never works silently.

## 3. The vocabulary

One name for one thing, in every library. The canonical names are the ones of this repository's three kernels.

| Concept | Name | Scala 3 | Java | Rust |
|---|---|---|---|---|
| an operation | operation | GADT case `Prices[X]`, `word(op)` | `Op<E, X, Self>` | variant with `Reply<X>` |
| the row | row; empty `Pure` | `A ! R`, `R + E`, `Has`/`In`/`Split` | `Eff<E, R>`, `Pure` | `eff![..]`, `()` |
| a program as data | `Comp`, value first | `Comp[R, A]` = `A ! R` | `Comp<A, R>` | `Prog<A, R>` |
| a program in direct style | `Program`, value first | context parameters | `Program<A, R>` over `Fx<R>` | `async fn` over `cx` |
| moving a program in a row | `up`, `under`, `fit`, `into` | `into` | `up`, `under`, `fit` | `embed`, `also` |
| ask / install / run | `perform`, `handle`, `run` | same | same | same |
| in-place handler | in place: `ret`, `answer`, state `Cell` | `Handler.tail`, `stateful` | `InPlace` | `Handler::in_place` |
| general handler | general: `ret`, `on(op, k)` | `Handler.general`, `control` | `Handler` | `Handler::new` |
| a handler that never resumes | `abort` | `Handler.abort` | `Handler` not resuming | `Handler::abort` |
| the continuation | `k`: resume, `unwind`, `after` | `k(x)`, `k.unwind`, `k.after` | `k.resume(op, x)`, `unwind()`, `after` | `k.resume(reply, x)` |
| finalizers | `ensuring`, `bracket` | same | same | same |
| standing between | `intercept`, `observe`, `around` | all three | `intercept`, `observe` | `observe`, `Interceptor` |
| sensitive effects | `Sensitive`, `audit`, `Audited`, `handleAudited`, `Pass` | same | `Sensitive.Op`, `Audit` | `type Kind = Sensitive` |
| standard effects | `State`, `Reader`, `Writer`, `Throws`, `Choose`, `Fresh`, `Clock`, `Emit`, `Async`, `Audit` (`record`, `trail`) | same | same; `Trail.keep` | same |
| a standard handler | `Effect.run(...)`; `Throws.run`/`recover`, `Choose.all`/`first` | same | same | same |

Type parameters: `E` (or `F`) an effect, `X` an answer, `A` a value, `B` a handler's result, `R` a ROW — never a
result.

## 4. The ways of writing a program are one model

| Way | What it is in the model |
|---|---|
| **types** | the row: what a program may ask, as a list in its type |
| **data** | the program as a value of `Ask`, `Bind`, `Handle`... (SPEC §2): inspected, stored, sent, resumed many times |
| **direct style** | the same program as ordinary code: `perform` a call that returns the answer; the machine's `k` is the rest of the code, kept by the platform (continuations, threads, a coroutine, a bytecode transform) |
| **parameters** | one place of the row given to code that needs only it: a capability of one effect |
| **exceptions** | an error is the effect `Throws<E>` with a place in the row; a language's own exceptions meet it at the ends: a call that throws becomes a `Throws` (`catching`), a `Throws` left at the end is thrown (`rethrow`) |

Direct style is the model with the program's `Bind`s written by the language instead of by `flatMap`: `perform` is
`Ask`, a handler's clause is the same `on(op, k)` or `answer`, the row is the same type — in Java the program takes
`Fx<R>`, the values of its places (`Fx.head`, `Fx.second`, `Fx.tail`), and a method that needs one effect takes that
place alone. So one program can use all the ways at once, and `reify`/`reflect` turn a direct-style program into data
and back.

## 5. How to work with effects

1. **Name the effects first**: what the program asks and what it is answered. Real resources become effects.
2. **Write the program against its row.** A function that needs one effect takes that place; one that needs several
   takes the row. No ambient effects.
3. **Write the handlers**: the production one over the host's client, the test one over a map or a `State`, a
   recording one where a run must be reproducible. Errors are `Throws`, not exceptions thrown through.
4. **Choose the order** of the handlers: it is meaning, and it is in the type.
5. **Run in one line** at the entry point. Inside a program install a handler around a sub-program and keep it a
   program; never run a sub-program to pull a value out.
6. **Control and audit at the boundary**: `observe` for a trail, `intercept` to check or change, sensitive effects
   under `audit`.
7. **Tests swap handlers**, not mocks; a conformance kit (TCK) checks an implementation, not the program.

## 6. Where the libraries stand

Surveyed 2026-11-24. What each library of the family names differently from §3, to be brought to it (a release of
the family keeps its version: fixed and republished while unused).

| Library | Differs in |
|---|---|
| okay-java | done: the vocabulary is its module okay-java-api, package `okay.effect` (`Op`, `Eff`, `Pure`, `Sensitive`, `Audited`), released 0.1.0. Left: `okay.core.Eff<L, A>` uses `Eff` for a program |
| okay-direct | done: operations and rows are okay-java-api's (`Op<E, X, Self>`, `Eff`, `Pure`), `Program<A, R>`. Left: handler clauses `value`/`operation` for `ret`/`on`/`answer`; `R` as a handler's result; `Fail` for `Throws`, `Emits` for `Emit`, `VirtualClock` for `Clock`; `Observer.watch` for `observe`; the sensitive mark on the operation (`Audited.Op`) for `Sensitive` on the effect; a dropped rest throws `Abandoned` where the model says `unwind` |
| okay-scala | `Fail` and `Yield` for `Throws` and `Emit` |
| okay-simple | `Error` and `Yield` for `Throws` and `Emit`; handlers lower case (`state(0)`) |
| okay-kotlin | `Fail`; `runEffects` for `run`; `With<E, R>`/`Nil` for the row in data style |
| okay-zig | `Fail`; `Eff(A, R)` for a program |
| okay-ts | `Eff<A, E, M>` for a program; `answering`/`deep` for in place/general |
| okay-lean | `Error` for `Throws` |
| okay-rust | the machine's `Eff<R, A>` for a program, row first |
| okay-kernel | below the vocabulary: a protocol between implementations (`Op<X>` with a key, no typed row) — not for programs |

Not differences: one-shot as a type (okay-direct's `MultiEffects`, okay-ts's `M`) is §2.7; a library's own idiom for
evidence (Scala's `Has`, Kotlin's context parameters, Zig's contexts) is its language's.
