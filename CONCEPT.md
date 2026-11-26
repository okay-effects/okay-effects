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
   A PROGRAM may say its row as a SET instead — the effects it asks, in no order — where its language can check
   that (okay; okay-java's `Sets`: `<R extends Prices & Ask> Comp<String, R>`). The handlers' row stays the list,
   written once where they are put, so the order is still in a type; an operation of such a program is answered
   by the nearest handler of its effect, and one effect twice is then two scopes, not two places.
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

Surveyed and brought to §3 on 2026-11-24 (masters; a release keeps its version: fixed and republished while unused).
Old public names stay as deprecated aliases where a library has users (Scala, Kotlin, Zig, Lean, TypeScript, Rust).

| Library | State |
|---|---|
| okay-java | the vocabulary is its module okay-java-api, package `okay.effect` (`Op`, `Eff`, `Pure`, `Sensitive`, `Audited`), released 0.1.0. the capability notation `okay.core`: its program `Comp<A, L>` (at the level `L`), the same concept and order as `okay.rows.Comp<A, R>`; one name, one concept, the package the notation. In 0.1.0 as republished on 2026-11-26, `okay.rows`' light form: an effect as a GADT (`sealed interface Prices<T> extends Operation<T, Prices<?>>`, its operations plain records `implements Prices<Long>`; `Operation<T, F>` is `Op<F, T, F>`, for an effect without type parameters of its own), a handler as one function of the operation and `k` (`Clause`: `handle((op, k) -> switch (op) {..}, p)`, no class named; with a state `StateClause`: `(state, op, k)`, `k.resume(op, x, next)`), a clause that only resumes answered in place; loops over an `Iterable` (`forEach`, `fold`, `collect`); and ROWS AS SETS, `okay.rows.Sets`, its documentation's first form (docs/start.md): a program says the effects it asks as a type bounded by them, in any order (`<R extends Prices & Ask> Comp<String, R>`, `Sets.perform(op)`, blocks, and plain Java with `Direct.body(d -> .. d.perform(op) ..)`: no class declared for an effect), a program of fewer effects used in one of more as it is; an effect for that is a sealed interface with no type argument, each operation `implements Prices, Operation<Long, Prices>` (the form of its standard effects); the handlers are the same functions over the list `Eff<F, R>`, the set written as that list once where they are put (`row(of(Prices.class, Ask.class)).of(program)`; kept in a field by a class whose type parameter names the set); such an operation is found by its effect's class — the nearest handler of the effect, a handler of a place taking it too — as in okay |
| okay-direct | on okay-java-api; `Program<A, R>`; `InPlace` (`ret`, `answer`), `Handler` (`ret`, `on`), a result `B`; `Throws` (`run`, `recover`, `catching`, `rethrow`), `Emit` (`send`), `Clock`; `Observer.observe`; `Sensitive.Op` marks a sensitive effect's operations, `Audited` the audited place; `Abandoned` is the model's `unwind` |
| okay-scala | `Throws` (`run`, `recover`), `Emit` (`send`, `collect`, `each`, `take`) |
| okay-simple | `Throws`, `Emit`; handlers as `State.run(0)`, `Reader.run(e)`, `Throws.run`, `Choose.all` |
| okay-kotlin | `Throws`; data-style row `Eff<E, R>`/`Pure`. Kept: `runEffects` (a Kotlin `run` loses to the standard library's `run { }`), `Prog<F, R, A>`'s order |
| okay-zig | `Throws`; a program is `Comp(A, R)` |
| okay-ts | a program is `Comp<A, E, M>`; `inPlace`, `general`, `generalMany` |
| okay-lean | `Throws` |
| okay-rust | the machine's program `Prog<A, R>`; `Handler<E, A, B, R>`, `K<B, R>`, the row last; `Handler::in_place`/`new`/`abort`; `State::run`, `Reader::run`, `Throws::run`, `Choose::all`/`first`, `Emit::collect` |
| okay | its own model, by the author's choice: a row is a UNION of effects, a place found by the effect's class (the keyed root), the order of handlers in the result type only, one effect twice one place (two by `Lexical.handle`). Its names are §3's, the old ones deprecated aliases (`perform`, `Handler.tail`/`stateful`/`general`/`abort`, `into`, `ensuring`, `Throws.run`, `Fresh.run`; audit on `import okay.*`, `observe` as `Audit.observe`); `Emit` is `Writer`'s alias. Differs: `State.run` runs to a value; `plus[G]` adds one effect |
| okay-kernel | below the vocabulary: a protocol between implementations (`Op<X>` with a key, no typed row) — not for programs |

Not differences: one-shot as a type (okay-direct's `MultiEffects`, okay-ts's `M`) is §2.7; a library's own idiom for
evidence (Scala's `Has`, Kotlin's context parameters, Zig's contexts) is its language's.
