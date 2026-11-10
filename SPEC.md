# The model

What the three kernels of this repository implement, in the order one would implement it in a fourth language.
The notation is neutral. The reference for every rule is `scala/src/Machine.scala` (the machine) and
`scala/src/Effects.scala` (the typed surface).

## 1. Terms

- An **operation** is a value that asks for something and names the type of its answer: `op : Op X`. It does
  nothing. It is data.
- An **effect** is a closed family of operations (`State = Get | Put`): a signature, not an implementation.
- A **row** is the set of effects a computation may ask.
- A **computation** `Comp A` comes to an `A`, asking operations on the way.
- A **continuation** `k` is the rest of a computation after one operation. It waits for the answer.
- A **handler** answers the operations of one effect, for the computation it is installed around.

## 2. The data

```
Comp A  =  Done a                       a value
        |  Ask op                       a question
        |  Bind first next              a computation, and a function of its value
        |  Handle mark s0 body          a computation under a handler, with the handler's first state
        |  Resume k x                   a continuation taken earlier, given its answer

Mark    =  (effect, handler)            how an operation finds the handler (§4)
Frames  =  nil | next : Frames          what waits for a value inside one level
Level   =  (frames, mark or none, state)
Stack   =  the top level, and the levels below it
k       =  levels, the lowest first     immutable; called "pieces" in the code
Step A  =  Done a | Asked op k
```

`pure a = Done a`, `perform op = Ask op`, `c >>= f = Bind c f`, `defer p = Bind (Done ()) (\_ -> p)`,
`handle h c = Handle (mark of h) s0 c`, and `k x = Resume k x`. Building any of these runs nothing.

Levels and frames are never written after they are made. A continuation shares them with the stack it was taken
from. (Rust, with no collector, writes a frame or a state only while nothing else holds it, and copies a state
shared with a `k` before writing it.)

## 3. The machine

`step : Comp A -> Step A` is one loop over `(c, stack)`. It starts with `c` and one empty level: no frames, no
mark. `(F, m, s)` is the top level, `B` the levels below.

| `c` | the stack | next |
|---|---|---|
| `Bind c1 f` | `(F, m, s) · B` | `c1`, on `(f : F, m, s) · B` |
| `Done x` | `(f : F, m, s) · B` | `f x`, on `(F, m, s) · B` |
| `Done x` | `(nil, m, s) · L · B`, `m` a mark | the handler's `ret` of `x` (in place: of `s` and `x`), on `L · B` |
| `Done x` | `(nil, none, _) · L · B` | `Done x`, on `L · B`: an empty level is popped |
| `Done x` | `(nil, none, _)`, nothing below | stop: `Done x` |
| `Handle m s0 body` | `T · B` | `body`, on `(nil, m, s0) · T · B` |
| `Ask op`, in place | the nearest level whose mark accepts `op` is `(F', m', s')` | `x = m'.answer(s', op)`, which may give a new state `s''`; `Done x`, on the same stack with `s''` in that level |
| `Ask op`, general | the nearest level whose mark accepts `op` is `n` levels down | `k` = the levels from the top to that one, both included; `m'.on(op, k)`, on the levels below that one |
| `Ask op`, unanswered | no level accepts `op` | stop: `Asked op k`, `k` = every level |
| `Resume k x` | `T · B` | `Done x`, on `k`'s levels above `T · B`, `k`'s highest as the top |

Details the table leaves out:

- **Nearest.** The top level is asked first, then each level below. A mark *accepts* `op` when `op` is an
  operation of the mark's effect and the handler's `mine(op)` is true.
- **A state deeper than the top.** When the handler in place is not at the top, the stack is rebuilt down to its
  level with the new state in it; nothing is rebuilt when the state did not change. A `k` taken earlier still
  holds the old levels, and so the old state.
- **A general clause runs outside its handler.** The handler's own level is in `k`, not on the stack the clause
  runs on. When the handler was the lowest, the clause runs on one empty level.
- **Resume.** The current top `T` goes below `k`'s levels, unless it is empty and nothing is below it. Levels of
  `k` with neither frames nor mark are not put back, except its highest, which becomes the top.
- **Return.** After `ret`, the level below becomes the top; when there is none, an empty level does.

Every rule is a bounded amount of work, except the search for a handler, the replacing of a deeper state, and the
taking and putting back of `k`: these are linear in the number of levels they pass. No rule calls the machine:
the stack of the host language does not grow with the program. `run c` is `step c` when it stops at `Done`.

## 4. Handlers

A handler of the effect `F`, around a program of an `A`, comes to a `B` and may ask the row `R`. Two shapes:

```
general    ret : A -> Comp B                 on     : (op, k) -> Comp B          k : X -> Comp B, for op : Op X
in place   ret : (S, A) -> Comp B            answer : (state : S, op) -> X       and the next state
both       mine : op -> Bool                 default: true
```

- **In place.** Every operation is answered at once from a state kept in the handler's level. No continuation is
  taken. The clause cannot ask operations, drop the rest of the program or resume it twice.
- **General.** The clause is given `k` as a value and returns a computation, run outside the handler.
- **`mine`.** Of the operations of `F`, the ones this handler answers. Any other passes to the next handler out.
  This is how one effect appears twice in a program: its operations carry an identity.
- **`handle` and `intercept`.** `handle` takes `F` out of the row. `intercept` is the same node with `F` kept in
  the row: a clause may ask the operation again, and the next handler out answers it.

The handler is *deep*: `k` contains the handler's level, so the resumed program is still handled by it.

## 5. Laws

For a general handler `h` of `F`, with `k = \x -> handle h (f x)`:

```
handle h (pure a)              =  h.ret a
handle h (perform op >>= f)    =  h.on (op, k)                              op accepted by h
handle h (perform op >>= f)    =  perform op >>= k                          any other op: it passes outside
```

For a handler in place, with state `s`, writing `handle h s c`:

```
handle h s (pure a)            =  h.ret (s, a)
handle h s (perform op >>= f)  =  handle h s' (f x)        where (x, s') = h.answer (s, op)
```

The monad laws:

```
pure a >>= f       =  f a
c >>= pure         =  c
(c >>= f) >>= g    =  c >>= (\x -> f x >>= g)
```

An operation is *algebraic*: `perform op >>= f` is all there is to it, and the rest of the program is its argument.
What cannot be written so — `catch`, `local`, a scope — is not an operation. It is a handler: `Throws.recover`,
`Reader.local`.

A general clause may do any of these with `k`:

- resume it once, last (`Emit.each`) — the common case;
- not resume it (`Throws`) — the rest of the program is dropped;
- resume it many times (`Choose`) — the same value used again; each resumption starts from the levels as taken;
- keep it and return (`Async`, `Emit.pull`) — it is resumed later, from elsewhere, by a new `step`;
- ask the operation again and resume with the answer (under `intercept`) — interception, audit;
- ask operations of another effect, which is then in the row of the result — delegation.

## 6. State: per path, or a shared cell

A state in place lives in its level. If that level is above a general handler, it is part of every `k` that
handler takes, and each resumption starts from the state as it was when `k` was taken: **each path has its own
state**. If the level is below that handler, it is not in `k`, and one state goes through all paths.

```
run (Choose.all (State.run 0 p))  =  [(1, 1), (2, 2)]        p = x <- among [1, 2]; s <- get; put (s + x); s + x
run (State.run 0 (Choose.all p))  =  (3, [1, 3])
```

A handler may instead close over a mutable cell made where it is installed. Then every resumption past it sees
the one cell, wherever the handler stands: a **shared cell**. The TCK's state is this, not the first (see the
comment on `state` in `scala/test/Tck.test.scala`). A kernel must be able to say both. For the first, the state
kept in a level must be a value: in Scala and Java it is held by reference and must not be mutated; Rust asks
`Clone` of it.

## 7. The row

A handler takes its effect out of the row, and only the empty row runs:

```
handle (h of F) : Comp (F + R) A -> Comp R B          run : Comp {} A -> A
```

A row is a type only. At run time there is no value of it, and an operation finds its handler as in §3. In all
three languages a program with an effect left does not compile.

- **Scala 3.** `Comp[-E, +A]`, written `A ! E`. The row is an intersection, `State[Int] & Choose` (also written
  `+`); the empty row is `Any`. `flatMap` intersects the rows of its two sides. `handle` takes `A ! (F & R)` and
  gives `B ! R`. `run` takes `A ! Any`. The effect's class comes from a `ClassTag[F]`.
- **Java 25.** `Comp<A, R>`. The row is a nested type in the order of the handlers, nearest first:
  `Eff<State<Integer>, Eff<Choose, ?>>`; the empty row is `Pure`. `perform` gives `Comp<X, Eff<E, ?>>`. `flatMap`
  infers a row `Q extends R` that fits both sides. `handle` takes the effect's class and a
  `Comp<A, ? super Eff<F, R>>`, and gives `Comp<B, R>`. `run` takes `Comp<A, ? super Pure>`. Rows are positional:
  `up` puts a computation under one more handler, `under` puts one more handler under its first effect, `fit`
  gives it a declared row that has its effects.
- **Rust.** `Comp<R, A>`. The row is a list of types, `row![State<i64>, Choose]` = `(State<i64>, (Choose, ()))`;
  the empty row is `()`. `perform` needs `R: Has<E, I>`, the place `I` inferred. `flat_map` needs the same row
  on both sides. `handle` takes `Comp<In, A>` with `In: Has<E, I, Rest = R>` and gives `Comp<R, B>`. Only
  `Comp<(), A>` has `run`. `embed` puts a computation in a bigger row, `also` adds an effect in front.

An effect is identified at run time by the class of its operations (Scala, Java: the erased class) or their type
(Rust: the `TypeId`, type arguments included). With an erased class, two handlers of one effect at different type
arguments are not told apart: the nearest answers.

## 8. The step, and the wire

`step` stops at a value, or at an operation that no handler inside the computation accepts, with the rest as `k`.
That is the protocol between a program and whoever runs it: "here is my value", or "here is what I ask; resume me
with the answer". `Asked.k` may be resumed any number of times.

Across a process the same two moves are the okay wire's `done` and `perform` nodes, answered by `continue`. The
far side keeps each `k` under a number; only the operation's name and arguments cross one way, and its answer the
other. On the far side a program asks the one operation `Call(name, args)` of the effect `Foreign` and is run by
`step`. On the host a far program is a computation asking `Foreign`, so the host's ordinary handlers answer it —
once, many times, or never. The whole protocol — calls, held objects, value escapes, streams under credit,
frames, mux, auth — is specified in [wire/README.md](wire/README.md).

## 9. Conformance

A kernel conforms when:

1. **The properties hold** (`Properties` in each language): a row handled in order; the order of handlers as
   meaning; a million operations, a hundred thousand left-nested binds and a million mutual tail calls in
   constant stack; every path of a choice; state per path and state through all paths; an endless stream pulled;
   an answer that comes later; interception; delegation; reader and `local`; recover, first, fresh, clock; the
   stream functions; both, race, all, sleep; a million answers given at once; an operation with no handler.
2. **Programs that must not run do not compile**: an effect left in the row, an answer of the wrong type, and —
   where the language can — a handler that misses an operation (`java/negative/`, the doctests of
   `rust/src/lib.rs`).
3. **The TCK digest is the reference's.** A generator draws 1000 terms of depth 6 from `java.util.Random(20261010)`:
   pure, tell, get, put, among, raise, sequence, sum, and handlers of a writer, a state, a failure and a choice.
   Log, state and failure operations carry an identity 0 or 1, and a handler answers only its own (`mine`). Each
   term is put under choose, throws 0, throws 1, state 0, state 1, writer 0, writer 1 (outermost first) and
   stepped. Its outcome is its value, or `-1000000` if an operation is left. The digest is the fold
   `d = d * 31 + outcome` in wrapping 64 bits from 0, and must be `-8623788747993822161`. The generator and the
   handlers are `scala/test/Tck.test.scala`; the order of the draws is part of the definition.
4. **Its worker answers the transcripts** in `wire/` (six files), and **its host, with any conforming worker,
   prints `wire/expected.txt`**.

`./test.sh` checks all four for the three kernels, and the nine host–worker pairs.
