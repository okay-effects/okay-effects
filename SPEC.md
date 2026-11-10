# The model

What the three kernels of this repository implement, in the order one would implement it again in a fourth
language. Notation is neutral; `X -> Y` is a function, `|` a choice of shapes.

## 1. Terms

- An **operation** is a value that asks for something and names the type of its answer: `op : Op X`.
  It does nothing; it is data — it can be printed, compared, logged, sent.
- An **effect** is a closed family of operations (`State = Get | Put`). It is a signature, not an implementation.
- A **row** is a set of effects: what a computation may ask.
- A **computation** `Comp A` comes to an `A`, asking operations on the way.
- A **continuation** is the rest of a computation after one operation: it waits for that operation's answer.
- A **handler** answers the operations of one effect.

## 2. The data

```
Comp A  =  Done (a : A)                          a value
        |  Ask  (op : Op A)                      a question
        |  Bind (first : Comp X) (next : X -> Comp A)
        |  Plug (first : Comp X) (rest : Rest X A)       a continuation taken earlier, put back

Rest X A  =  End                                 (X = A)
          |  Then (next : X -> Comp Y) (rest : Rest Y A)
          |  Both (first : Rest X Y) (rest : Rest Y A)

Step A  =  Done (a : A)
        |  Asked (op : Op X) (k : Rest X A)
```

`pure a = Done a`, `perform op = Ask op`, `c >>= f = Bind c f`, `defer p = Bind (Done ()) (\_ -> p)`,
and resuming a continuation is building data: `k x = Plug (Done x) k`.

This is the free monad over the operations, with its bind kept as data (`Bind`) so that no bind recurses, and its
continuation kept as data (`Rest`) so that taking it and putting it back cost one node each.

## 3. The machine

`step : Comp A -> Step A` — one loop over a pair (computation, what waits for it), starting from `(c, End)`:

```
(Ask op,        k)                        stop: Asked op k
(Bind c f,      k)                   ->   (c, Then f k)
(Plug c r,      k)                   ->   (c, Both r k)            (c, r) when k is End
(Done x,        End)                      stop: Done x
(Done x,        Then f k)            ->   (f x, k)
(Done x,        Both End k)          ->   (Done x, k)
(Done x,        Both (Then f r) k)   ->   (f x, Both r k)
(Done x,        Both (Both a b) k)   ->   (Done x, Both a (Both b k))
```

Every rule is a constant amount of work and none of them calls the machine: the stack does not grow with the
program. A program's depth lives in `Rest`, on the heap.

`run c` is `step c` when it stops at `Done`; stopping at `Asked` means an operation with no handler.

## 4. The handler

A handler of the effect `F`, for a program of an `A`, to a `B`:

```
ret : A -> Comp B
on  : (op : Op X in F, k : X -> Comp B) -> Comp B
```

`handle h c` is the fold of the computation by the handler — the only thing a handler is:

```
handle h c = defer (case step c of
    Done a                 ->  h.ret a
    Asked op k, op in F    ->  h.on (op, \x -> handle h (k x))
    Asked op k, otherwise  ->  Ask op >>= \x -> handle h (k x)        the operation passes outside
```

So, as equations (the laws the tests hold):

```
handle h (pure a)             =  h.ret a
handle h (perform op >>= f)   =  h.on (op, \x -> handle h (f x))             op in F
handle h (perform op >>= f)   =  perform op >>= \x -> handle h (f x)         op not in F
```

together with the monad laws of `pure` and `>>=`. An operation is *algebraic*: `perform op >>= f` is all there is to
it — the rest of the program is its argument. What cannot be written so (`catch`, `local`, a transaction's scope)
is not an operation: it is a handler.

The continuation given to `on` is a value. A clause may

- resume it once, in tail position (State, Reader) — an ordinary call;
- not resume it (Throws) — the rest of the program is dropped;
- resume it many times (Choose) — each resumption is the same value used again;
- keep it and return (Async, a stream's consumer) — it is resumed later, from elsewhere;
- ask the operation again (`perform op >>= k`) — outside this handler: interception and audit;
- ask operations of another effect — delegation; they join the row of the result.

The handler is *deep*: the continuation it is given is already handled by it. State that changes between
operations is the classic parameter passing — the answer type is a function of the state (`std State.run`).

## 5. The row

Semantically a row is a set of effects, and `handle` takes one out:

```
handle (h for F) : Comp (F + R) A -> Comp R B          run : Comp {} A -> A
```

How much of this a compiler checks is the language's business; the model only requires that an operation reaching
the top is refused by name, never answered at random.

- Scala 3 keeps the row in the type as an intersection (`A ! (State[Int] & Choose)`; `Comp[-E, +A]`): asking adds,
  `handle` removes, `run` accepts only the empty row (`Any`). `intercept` is `handle` with the effect kept.
- Java and Rust check at run time: `NoHandler`.

At run time an effect is identified by the class (type) of its operations. An effect meant to appear twice in one
program is two families, or one whose operations carry an identity and whose handler passes what is not its own.

## 6. The step, and the wire

`Step` is also the protocol between a program and whoever runs it: "here is my value" or "here is what I ask, and
call me back". Across a process the same two messages are JSON lines — the okay wire's:

```
host -> {"id":n, "op":"program",  "run":r, "fn":name, "args":[…]}
host -> {"id":n, "op":"continue", "run":r, "k":k, "answer":v}
host -> {"id":n, "op":"forget",   "run":r}
far  <- {"id":n, "ok":{"done":v}}
far  <- {"id":n, "ok":{"perform":name, "args":[…], "k":k}}
far  <- {"id":n, "ok":null}                                           (forget)
far  <- {"id":n, "condition":{"kind":…, "message":…}}
```

The far side speaks first: `{"shim":9, …}`. It keeps each continuation it hands out under `(run, k)` until the run
is forgotten; the host may `continue` the same `k` any number of times. Only data crosses: the operation's name and
arguments one way, its answer the other. A continuation never does.

On the far side a program asks the one operation `Call(name, args) : Op Json`; on the host a far program *is* a
program asking `Call`s, so the host's ordinary handlers answer it. Mapping a typed effect to `Call`s and back is a
handler on each side (delegation, §4).

Not implemented here: `call`, `release`, streams, `configure` (CBOR, deflate), mux, auth, the value escapes.

## 7. Conformance

A kernel conforms when:

1. the properties hold (`Properties` in each language): a row handled in order; order as meaning; a million
   operations, a hundred thousand left-nested binds and a million mutual tail calls in constant stack; every path
   of a choice; state per path and state through all paths; an endless stream pulled; an answer that comes later;
   interception; delegation; `NoHandler`;
2. the family's TCK digest is the reference's: 1000 programs drawn by `java.util.Random(20261010)` (okay-kernel,
   `tck/Tck.scala`), outcomes folded as `d * 31 + outcome` in 64 bits, equal to `-8623788747993822161`;
3. its worker answers `wire/transcript.txt`, and its host, with any conforming worker, prints `wire/expected.txt`.

`./test.sh` checks the three for the three kernels.
