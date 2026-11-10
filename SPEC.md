# The model

What the three kernels of this repository implement, in the order one would implement it in a fourth language.
The notation is neutral. The reference is the Scala kernel: `scala/src/Machine.scala` (the machine; §2 says how it
writes the stack), `scala/src/Effects.scala` (the typed surface) and `scala/src/Rows.scala` (the rows).

## 1. Terms

- An **operation** is a value that asks for something and names the type of its answer: `op : Op X`. It does
  nothing. It is data.
- An **effect** is a closed family of operations (`State = Get | Put`): a signature, not an implementation.
- A **row** is the list of effects a computation may ask. One effect may be in it more than once.
- A **computation** `Comp A` comes to an `A`, asking operations on the way.
- A **continuation** `k` is the rest of a computation after one operation. It waits for the answer.
- A **handler** answers the operations of one effect, for the computation it is installed around.

## 2. The data

```
Comp A  =  Done a                       a value
        |  Ask op                       a question; with it, what its handler is found by (§7)
        |  Bind first next              a computation, and a function of its value
        |  Handle mark s0 body          a computation under a handler, with the handler's first state
        |  Resume k x                   a continuation taken earlier, given its answer

Mark    =  (handler, its address)       how an operation finds the handler (§7)
Frames  =  nil | next : Frames          what waits for a value inside one level
Level   =  (frames, mark or none, state)
Stack   =  the top level, and the levels below it
k       =  levels, the lowest first     immutable; called "pieces" in the code
Step A  =  Done a | Asked op k
```

`pure a = Done a`, `perform op = Ask op`, `c >>= f = Bind c f`, `defer p = Bind (Done ()) (\_ -> p)`,
`handle h c = Handle (mark of h) s0 c`, and `k x = Resume k x`. Building any of these runs nothing.

Frames are never written after they are made, and neither is a level that a continuation holds. In Java no level
is written, and a continuation shares its levels with the stack it was taken from. In Scala a level on the stack is
the cell of its handler's state, written in place; a level taken into a `k` is off the stack and is not written
again, and resuming `k` makes new levels from the ones it holds, each with the state as taken. (Rust, with no
collector, writes a frame or a state only while nothing else holds it, and copies a state shared with a `k` before
writing it. Its `Bind` is a struct composed by the compiler: a frame is made only where what follows must wait as a
value — at an operation answered generally, a handler, a boxed computation, a resumed `k`.)

The Scala machine writes this stack typed, and nothing in it is cast. The frames of the top are an argument of the
loop; a level holds its handler, its state, and the frames and the level outside it. A level says the row inside it
and the value its body comes to; frames say the value they take and the one they come to; a `k` says the answer it
waits for, and the row and the value it is resumed in. A level is a handler's, a renaming level (§7), or the bottom
of the run: above the bottom no level is empty, so what §3 says of popping or dropping an empty level does not arise.

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

- **Nearest.** The top level is asked first, then each level below. A mark *accepts* `op` when its handler is one
  the row gives `op`. §7 says what the model requires of this, and how each kernel decides it.
- **A state deeper than the top.** When the handler in place is not at the top, Scala writes the new state into
  the handler's level where it is. Java rebuilds the stack down to that level with the new state in it; nothing is
  rebuilt when the state did not change. Either way a `k` taken earlier holds the state as it was when taken.
- **A general clause runs outside its handler.** The handler's own level is in `k`, not on the stack the clause
  runs on. When the handler was the lowest, the clause runs on one empty level.
- **Resume.** The current top `T` goes below `k`'s levels, unless it is empty and nothing is below it. Levels of
  `k` with neither frames nor mark are not put back, except its highest, which becomes the top.
- **Return.** After `ret`, the level below becomes the top; when there is none, an empty level does.

Every rule is a bounded amount of work, except the search for a handler, the replacing of a deeper state (Java), and the
taking and putting back of `k`: these are linear in the number of levels they pass. No rule calls the machine:
the stack of the host language does not grow with the program. `run c` is `step c` when it stops at `Done`.

## 4. Handlers

A handler of the effect `F`, around a program of an `A`, comes to a `B` and may ask the row `R`. Two shapes:

```
general    ret : A -> Comp B                 on     : (op, k) -> Comp B          k : X -> Comp B, for op : Op X
in place   ret : (S, A) -> Comp B            answer : (state : S, op) -> X       and the next state
```

- **In place.** Every operation is answered at once from a state kept in the handler's level. No continuation is
  taken. The clause cannot ask operations, drop the rest of the program or resume it twice.
- **General.** The clause is given `k` as a value and returns a computation, run outside the handler.
- **`handle` and `intercept`.** `handle` takes `F` out of the row. `intercept` puts a handler and keeps `F` in the
  row: a clause may ask the operation again, and the next handler out answers it.
- **`mine`**, in Java and Rust: `mine : op -> Bool`, true by default. Of the operations that come to the handler,
  the ones it answers; any other passes to the next handler out. Any handler has it in Rust, an interceptor in
  Java. With it one effect type is handled twice in a program: its operations carry an identity. Scala has no
  `mine`: there the two are two places of the row.

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

No value of a row exists at run time. What is left of it is what an operation carries to find its handler. In all
three languages a program with an effect left does not compile.

**What the model requires.** An operation asked at one entry of the row is answered by the handler that took that
entry out: the nearest handler of that entry around it. An interceptor of the entry that stands between them is
given the operation first. Handlers of other entries are passed, whatever their effects — also another entry of the
same effect. With no handler left, `step` stops (§8). The three kernels decide "of that entry" in three ways.

- **Scala 3: by place.** `Comp[R[_], +A]`, written `A ! R`. A row is a type constructor: an effect (`Choose`,
  `State % Int`), `Pure`, or `R + E` — the effects in order. An operation is first a *word*, `word(op)`: a
  `Word[E, X]`, of no row, and at run time the operation itself. Bound in a program, or used where a program is
  expected, it is an `Ask` in that program's row and needs an `In[E, R]`; `perform[E](op)` is the `Ask` at once.
  `handle(p, h)` needs a `Split[E, R, Rest]` and gives `B ! Rest`, the row as one would write it:
  `State % Int + Choose` without the state is `Choose`. `intercept` needs an `In[E, R]` and gives `B ! R`.
  `run` takes `A ! Pure`. The evidence is found by the compiler and is the effect's *place*, data typed by the
  effect, the row, and the row without it: a `Place[E, R, Rest]`, one of `Only` (the one effect of a row of one),
  `Last` (the last of `R + E`), `First` (the first of a row of two) and `Init` (one further in than the last).
  `Ask` carries it, and a level has its handler's. Walking down, a level matches its handler's place against the
  operation's. When they are one place, the match shows the compiler that the two effects are one: the clause is
  given the operation at its own type. Any other operation goes on with its place in the row outside: past a level
  put by `handle`, the row without the handler's effect; past an interceptor, the same row. No class of an
  operation is looked at. One effect twice in a row is two places: the compiler finds the last, `Place.init` names
  the other.
  A bind, `p.flatMap(f)`, is of the row expected of it, or with none expected of the row `f` gives. `p` need not
  be of that row (`Fits`): a word is placed in it, and a program of a smaller row is put into it as `into` does.
  The same holds of `map`, and of a program used where one of a bigger row is expected. Where an `if` or a `match`
  that mixes words and programs is bound, a macro (`Branches.scala`) fits each branch on its own. `p.into[S]`
  needs a `Sub[R, S]`: for every place of `R`, its place in `S`. Into the same row it is `p` itself; one operation
  or one value is made anew at its place; any other program is a node `Embedded`, run under a *renaming level*: a
  level with no handler, past which an operation has its place in `S`. It is popped with its value and is part of
  a `k` like any level.
  This is the requirement, exactly, for the effects of a row. A row and an effect are of one kind, `F[_]`, so a row
  can be written where an effect is meant: handled as if it were one effect, or embedded so by a place named by
  hand. The types do not refuse it. No operation is of such a place, and the machine throws `NoHandler` when one
  comes to it.
- **Java 25: by position.** `Comp<A, R>`. The row is a nested type in the order of the handlers, nearest first:
  `Eff<State<Integer>, Eff<Choose, ?>>`; the empty row is `Pure`. An operation's class names its effect, its
  answer and itself: `Op<E, X, Self extends E>`. `perform` takes an `O extends Op<E, X, ? super O>` and gives
  `Comp<X, Eff<E, ?>>`: javac refuses a class that names itself and is not an `E`, and at `perform` one that names
  another class. So every operation asked is of its effect, and its handler is given it with no test. `flatMap`
  infers a row `Q extends R` that fits both sides. `handle` takes a `Comp<A, ? super Eff<F, R>>` and gives
  `Comp<B, R>`. `run` takes `Comp<A, ? super Pure>`. `Ask` carries a count of the row levels it skips, 0 from
  `perform`. A level put by `handle` is a row level: it accepts an operation whose count is 0, and takes one off
  any other. `up(c)` adds one to the count of every operation of `c`; `under(c)`, to those whose count is not 0;
  `fit` changes the type only. Around one operation they change its count. Around any other computation they put
  a *marker*: a level with no handler, which adds to the counts of the operations that pass it, is popped without
  a `ret`, and is part of a `k` like any level. So `handle` meets the requirement. `intercept` does not go by the
  row: its level is no row level, and accepts an operation that passes it when the operation is of the
  interceptor's class and `mine` — whatever entry it was going to. It takes a `Class<F>`, so an effect with type
  arguments is refused; such an effect is intercepted at its entry by `handle(.., under(p))`. The class given to
  `handle` only names the effect: the machine does not look at it.
- **Rust: by type id.** A computation is a struct that implements the trait `Prog<A, R>`; `.boxed()` forgets its
  shape and gives a `Comp<A, R>`. The row is a list of types, `eff![State<i64>, Choose]` =
  `(State<i64>, (Choose, ()))`; the empty row is `()`. `perform` needs `R: Has<E, I>`, the place `I` inferred.
  `flat_map` needs the same row on both sides. `handle` takes a computation of the row `In` with
  `In: Has<E, I, Rest = R>` and gives one of `R`. `run` asks `R: Closed`, which only `()` is. `embed` puts a
  computation in a bigger row, `also` adds an effect in front. The place `I` is a proof and is not kept: a mark
  has the `TypeId` of its effect, type arguments included, and a level accepts an operation of that type when
  `mine` says so. `handle` and `intercept` put the same mark. This is the requirement while every entry of the row
  is another type; two entries of one type are not told apart by the row, only by `mine`.

An operation with no handler stops the run with the operation and `k`. Scala adds the operation's place in the row
of the whole run, by which its effect is known (`Asked.of`). Its `run` takes only the empty row, and throws
`NoHandler` if the step stops at an operation all the same.

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
   stream functions; both, race, all, sleep; a million answers given at once; an operation with no handler; in
   Scala and Java, one effect twice in a row, each entry with its own handler; in Scala, a program put into a
   bigger row, by `into` and where it is bound, its effects there in any order, and a row handled as one effect
   refused with `NoHandler`.
2. **Programs that must not run do not compile**: an effect left in the row (`java/negative/`, the doctests of
   `rust/src/lib.rs`, `compileErrors` in the Scala `Properties`), an answer of the wrong type and a handler that
   misses an operation (`java/negative/`, the doctests), an operation whose class is not of the effect it names
   (`java/negative/WrongOperation.java`).

3. **The TCK digest is the reference's.** A generator draws 1000 terms of depth 6 from `java.util.Random(20261010)`:
   pure, tell, get, put, among, raise, sequence, sum, and handlers of a writer, a state, a failure and a choice.
   Log, state and failure operations have an identity 0 or 1, and a handler answers only its own: in Scala the
   identity is one of the effect's two places in the row, in Java and Rust a field of the operation and `mine`. Each
   term is put under choose, throws 0, throws 1, state 0, state 1, writer 0, writer 1 (outermost first) and
   stepped. Its outcome is its value, or `-1000000` if an operation is left. The digest is the fold
   `d = d * 31 + outcome` in wrapping 64 bits from 0, and must be `-8623788747993822161`. The generator and the
   handlers are `scala/test/Tck.test.scala`; the order of the draws is part of the definition.
4. **Its worker answers the transcripts** in `wire/` (six files), and **its host, with any conforming worker,
   prints `wire/expected.txt`**.

`./test.sh` checks all four for the three kernels, and the nine host–worker pairs.
