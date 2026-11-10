# The wire

The okay wire, as the kernels of this repository speak it. Written from the Scala implementation
(`scala/src/wire/`) for whoever ports it; where this text and that code disagree, the transcripts in this directory
decide.

The wire is not ours. It is the protocol of the okay platform (its `okay-py/src/main/resources/okay/py/wire.txt`,
`specs/foreign-one.md`, `specs/polyglot-one-wire.md`): shim version 9, the same messages. What is left out of it
here is listed at the end.

Two sides. The **far side** (worker) serves a module: functions by name. The **host** asks. The transport is a
process's standard input and output: the host starts the worker and writes to its stdin; the worker writes only
protocol messages to its stdout.

## Messages

A message is one JSON object. At first each is one line of UTF-8 text ended by `\n` (empty lines are skipped).
After a `configure` the same objects travel as frames (below).

The far side speaks first, once — the hello:

```
< {"shim": 9, "python": "scala", "speaks": {"format": ["json", "cbor"], "compress": ["deflate"], "mux": true}}
```

`shim` is the wire's version; a host refuses any other. `python` holds the far side's own name (the key is
historical, every okay far side keeps it). `speaks` is what the far side can be configured to, and `mux` (below).
A far side that needs a secret adds `"auth"` (below); one that cannot run says `"fatal": why`, and the host refuses.

Then the host sends requests. A request has an `id` (a number the host chooses) and an `op`. The answer to it
carries the same `id` and either `ok` or `condition`:

```
< {"id": 1, "ok": 5}
< {"id": 1, "condition": {"kind": "ValueError", "message": "no"}}
```

A request with no `id` is answered with `"id": null`. A request that cannot be read is answered
`{"id": null, "condition": {"kind": "ValueError", ...}}`, and the far side goes on. A failure never ends the far
side: it is always a condition, `kind` being the failure's name and `message` free text. Kinds the far side itself
uses: `LookupError` (no such function, ref, continuation, method), `TypeError` (the wrong arguments, an object
where a value must be), `ValueError` (a request that makes no sense), `PermissionError` (auth). A function's own
failure carries the kind it raised.

The far side also sends messages that answer no request — a stream's — which carry `stream` and no `id`.

### call

```
> {"id": 1, "op": "call", "fn": "t:add", "args": [2, 3]}
< {"id": 1, "ok": 5}
```

`fn` is a function's name, or the address of something held:

```
> {"id": 2, "op": "call", "fn": "t:counter", "args": [10], "held": true}
< {"id": 2, "ok": {"t": "ref", "id": 1, "type": "t.Counter"}}
> {"id": 3, "op": "call", "fn": {"ref": 1, "method": "inc"}, "args": [5]}
< {"id": 3, "ok": 15}
> {"id": 4, "op": "call", "fn": {"ref": 1, "attr": "n"}, "args": []}
< {"id": 4, "ok": 15}
> {"id": 5, "op": "release", "ref": 1}
< {"id": 5, "ok": null}
```

- `"held": true` keeps the answer on the far side and answers a **ref**: `id` is the far side's counter of refs,
  from 1; `type` is the kind of what is held — an object's own name, or `list`, `dict`, `value` for a plain value.
- A module has functions and **classes**. A class is a name that makes an object: it has methods and attributes by
  name, and state. An object crosses only as a ref: a class called without `held` is a `TypeError`.
- A ref among the arguments, at any depth, `{"t": "ref", "id": n}`, is replaced by the *value* held. A ref to an
  object as an argument is a `TypeError`; a ref not held is a `LookupError`.
- `method`/`attr` on a held value, or a name the object has not, is a `LookupError`. A method's or an attribute's
  answer may be `held` too.
- `release` drops what a ref names. Releasing twice, or a ref never given, is no failure.
- A function asked by `call` cannot ask the host anything back (no callbacks): if it does, the call's answer is a
  `LookupError`.

### program, continue, forget

A program is a function that asks the host operations by name while it runs. The far side runs it to its first
question and answers a **node**: `done`, or `perform` with the number `k` of the rest of the program.

```
> {"id": 6, "op": "program", "run": 1, "fn": "t:pairs", "args": [], "callbacks": ["choose"]}
< {"id": 6, "ok": {"perform": "choose", "args": [[1, 2]], "k": 1}}
> {"id": 7, "op": "continue", "run": 1, "k": 1, "answer": 1}
< {"id": 7, "ok": {"done": 10}}
> {"id": 8, "op": "continue", "run": 1, "k": 1, "answer": 2}
< {"id": 8, "ok": {"done": 20}}
> {"id": 9, "op": "forget", "run": 1}
< {"id": 9, "ok": null}
```

- `run` is the host's number for this run of the program. `k` is the far side's: one counter for the whole far
  side, from 1, the next number at every `perform`.
- The far side keeps the continuation under `(run, k)` until `forget` of the run. Until then the host may
  `continue` the same `k` any number of times, with different answers (a handler of choice does). Nothing but
  the number crosses.
- `continue` of a `(run, k)` not kept is `LookupError`, message `no continuation K of run R`.
- `callbacks` (optional) names what the host can answer. Our far side does not need it; our host sends it when
  given, for far sides that check.
- okay's wire lets a host continue with `"condition": {...}` in place of `"answer"`, to fail the far program at
  its question. A program kept as data cannot be failed from outside: our far side refuses it, `ValueError` (as
  okay's Python shim does for its programs as data). Our host never sends it.
- okay's direct-style far sides mark a node `"once": true`: the rest is a parked stack and can be continued once.
  Our far sides never send it — every continuation here is a value. Our host ignores the mark; continuing such a
  node twice is refused by that far side, which the host sees as a condition.
- A program that asks nothing is `done` at once. A program that fails answers its condition, by the request's id.

### Streams

A call opened with `stream` is a stream the far side feeds. It is answered at once with `ok: null`; then its
chunks follow, each a message of its own, and at last its end or its condition:

```
> {"id": 1, "op": "call", "fn": "t:numbers", "args": [5], "stream": 1, "credit": 2}
< {"id": 1, "ok": null}
< {"stream": 1, "chunk": 1}
< {"stream": 1, "chunk": 2}
> {"id": 2, "op": "credit", "stream": 1, "credit": 1}
< {"id": 2, "ok": null}
< {"stream": 1, "chunk": 3}
...
< {"stream": 1, "end": true}            or    < {"stream": 4, "condition": {"kind": ..., "message": ...}}
> {"id": 7, "op": "cancel", "stream": 2}
< {"id": 7, "ok": null}
```

- `stream` is the host's number for the stream. `credit` is how many chunks the far side may send now; each
  `credit` request adds to it. With no credit left the function waits **inside its send**: it has not run past
  it. A credit of 0 is a pause. So the far side is never more than the credit ahead of the host.
- Our host gives the first credit in the call, then 1 for every chunk its consumer takes.
- `cancel`: the function is not resumed again; nothing more is sent on the stream. Credit, or a cancel, for a
  stream that is not open is answered `ok: null` and does nothing.
- The value the function returns at its end is dropped: a stream's answer is its chunks.
- If the function cannot be started (no such function, wrong arguments) the call's own answer is the condition,
  and no stream is opened.

A stream **the host feeds** into the same call is named in the call, with the credit the host starts with:

```
> {"id": 10, "op": "call", "fn": "t:dedup", "args": [], "stream": 3, "credit": 2, "input": {"id": -3, "credit": 2}}
< {"id": 10, "ok": null}
> {"id": 11, "op": "chunk", "stream": -3, "chunk": 3}
< {"id": 11, "ok": null}
< {"stream": -3, "credit": 1}
< {"stream": 3, "chunk": 3}
> {"id": 15, "op": "end", "stream": -3}
< {"id": 15, "ok": null}
```

- The input's number is the stream's, negated (okay's hosts do so, and okay's far sides rely on it at `cancel`).
- The host sends at most as many `chunk`s as it has credit. The far side gives `{"stream": i, "credit": 1}` each
  time the function *takes* a value — before whatever the function does with it. Values that came and are not yet
  taken wait in a queue (never longer than the credit).
- `end` ends the input: the function's next take finds none. `cancel` of the call's stream ends the whole call.
- A function that takes from an input the call does not have finds its end at once.
- `chunk`, `end`, `credit`, `cancel` are requests like any other: each is answered `ok: null`. Our host does not
  wait for those answers and drops them when they come.

Order, where one request sets several messages going: the answer to the request first, then what it set going
(`streams.txt` pins this).

### mux

A far side that says `"mux": true` in its hello answers each request **when its function finishes**, not in the
order requests came; the host matches answers by `id`. A function that waits (for a callback, a timer, another
request) does not hold the far side: the next request is read and served meanwhile.

```
> {"id": 1, "op": "call", "fn": "t:wait", "args": ["g"]}
> {"id": 2, "op": "call", "fn": "t:add", "args": [2, 3]}
< {"id": 2, "ok": 5}
> {"id": 4, "op": "call", "fn": "t:open", "args": ["g"]}
< {"id": 1, "ok": "g"}
< {"id": 4, "ok": 1}
```

Streams need `mux`: our host refuses to open one on a far side without it (`NotStreaming`). Everything else works
with a far side that answers one request at a time.

### configure

The host may choose a format and a compression of those in the hello's `speaks` (`json` and `none` need not be
announced):

```
> {"op": "configure", "format": "cbor", "compress": "deflate"}
< {"id": null, "ok": {"format": "cbor", "compress": "deflate"}}
```

The answer goes as the request came; from the next message on, both sides use the choice. Any choice but
`json`/`none` turns the wire into frames. An unknown format or compression is a `ValueError`, and nothing changes.
Our host sends it once, after the hello (and after the auth), with no `id`, and refuses to open the wire if the far
side did not announce what was asked.

### auth (optional)

A far side started with a secret (our demonstration's worker: the variable `OKAY_WIRE_SECRET`, okay's name for
it) adds to its hello `"auth": {"scheme": "hmac-sha256", "nonce": Ns}` — 16 random bytes in hex. Then:

```
> {"op": "auth", "nonce": Nc, "mac": hex(HMAC-SHA256(secret, "okay-wire client|" + Ns + "|" + Nc))}
< {"id": null, "ok": {"mac": hex(HMAC-SHA256(secret, "okay-wire server|" + Ns + "|" + Nc))}}
```

`Nc` is the host's own 16 random bytes in hex; hex is lower-case; the secret is its UTF-8 bytes. The far side
compares in constant time. A wrong mac: `PermissionError`, and the far side closes the wire. Any other request
before the auth has passed: `PermissionError`, the wire stays open. The host checks the far side's mac the same
way, so each has proved the secret without sending it. Mismatches the host refuses by name: a far side that asks
for auth and a host with no secret; a host with a secret and a far side that asks for none. `configure` comes after.

Over a pipe to a process the host itself started there is nobody to authenticate; okay uses this on TCP. It is
here so that the three languages can test it against each other.

## Values and their escapes

The arguments of a call, its answer, a `perform`'s arguments, a `continue`'s answer, a `done`, a chunk: each is a
**value**. A value is JSON, with these escapes — objects tagged `t` — for what JSON cannot say. The message around
a value is plain JSON and is never escaped.

| value | on the wire |
|---|---|
| null, true, false, a string | themselves |
| an integer, `-2^53 < n < 2^53` | the number |
| any other integer (64 bits here) | `{"t": "int", "v": "9007199254740993"}` — decimal digits in a string |
| a float that is not whole, or `abs >= 1e15` | the number |
| a float that is whole, `abs < 1e15` | `{"t": "f", "v": 3.0}` — so that 3.0 does not come back the integer 3 |
| not-a-number | `{"t": "nan"}` |
| a list | a JSON array of values |
| a dict (string keys, in order) | `{"t": "dict", "kv": [["a", 1], ["b", 2]]}` |
| a ref | `{"t": "ref", "id": 1, "type": "t.Counter"}`; as an argument `type` may be absent |

Reading: a JSON number written with a fraction or an exponent is a float, any other an integer. `{"t": "f"}`
takes `v` spelled either way. An object with no `t` is a dict (some far sides send theirs plain). `{"t": "na"}`
(R's absence) is null. Any other tag is refused, `ValueError` — so `{"t": "frame"}`, `{"t": "bytes"}`. An
infinity has no escape and is refused where it would be sent.

## Frames

After a `configure` to anything but `json`/`none`, in both directions, a message is:

```
4 bytes: the length N, big-endian        N bytes: the message
```

The message is the same JSON object, encoded by the format — JSON text in UTF-8 with no newline, or CBOR — and
then, with `deflate`, compressed: raw DEFLATE (RFC 1951; no zlib header, no checksum: `java.util.zip` with
`nowrap`, flate2's `Deflate*`). `{"id":1,"op":"call","fn":"t:add","args":[2,3]}` as a CBOR frame:

```
00 00 00 1e  a4 62 69 64 01 62 6f 70 64 63 61 6c 6c 62 66 6e 65 74 3a 61 64 64 64 61 72 67 73 82 02 03
```

Compressed bytes differ between libraries: compare what they inflate to.

**CBOR** (RFC 8949), the subset: unsigned and negative integers (major types 0, 1), text strings (3), arrays (4)
and maps (5) of definite length with text keys, `false`/`true`/`null` (`f4`/`f5`/`f6`), floats. Written: integers
in the shortest head; every float as 64 bits (`fb`). Read as well: floats of 16 and 32 bits (`f9`, `fa`),
`undefined` (`f7`) as null. Anything else — byte strings, tags, indefinite lengths, bytes after the message, a
message cut short — is refused by name. The tree is the JSON's: escapes stay escapes (a whole float is still
`{"t": "f", "v": 3.0}`, with `v` a CBOR float).

## The host, as implemented here

Not protocol, but what the Scala host does, and what `expected.txt` shows:

- One thread, no reader thread. The host reads the wire only while its program waits for something (an answer by
  id, a stream's next chunk). While it waits it also feeds: if a stream it feeds has credit, it sends the next
  chunk (or `end`), else it reads one message and puts it where it is waited for — an answer by its id, a
  stream's message in the stream's queue, a credit on the input's count.
- A far failure is raised in the host's program as `Throws[Condition]`; the program's handlers decide. The same
  for a far side that has gone (`WorkerDied`).
- `Remote.run(program)` is a scope: when the program ends, is left through an effect, or the host throws, every
  run it began is forgotten (`forget`) and every stream still open is cancelled (`cancel`). Refs live until
  released.
- `Far.start` / `Far.result` send a call and wait for its answer separately: that is how a program has two
  calls in flight.

## The demonstration

`wireDemo worker` serves this module (each language re-implements it; state is the worker's own):

| name | what it does |
|---|---|
| `t:add(a, b)` | two integers: their sum |
| `t:echo(v)` | `v` |
| `t:sum(list)` | the sum of a list's integers |
| `t:boom()` | raises `ValueError`, message `no` |
| `t:counter(n)` | a class: makes a `t.Counter`. Method `inc(by)`: adds, answers the new `n`. Attribute `n` |
| `t:pairs()` | asks `choose([1, 2])`; answers the choice times 10 |
| `t:quote(sku)` | asks `price_of(sku)`; answers it times 2 (an integer or a float, as it came) |
| `shop:total(items...)` | asks `price_of(item)` for each; answers the sum |
| `t:count(n)` | asks `tick()` n times; answers n |
| `t:numbers(n)` | a stream: sends 1..n. Sets the module's count to 0, then counts each value sent |
| `t:sent()` | that count: how many values the last `t:numbers` has sent |
| `t:dedup()` | a stream: takes the host's values to their end; sends on each one not seen before |
| `t:wait(name)` | waits; answers `name` when `t:open(name)` is called |
| `t:open(name)` | wakes those waiting on `name`, in the order they came: each is answered before `t:open` itself, which answers how many |

Any other arguments: `TypeError`. A value "sent" is counted when its chunk has gone out, not while the function
waits for credit.

`wireDemo host <the worker's command...>` starts the worker three times and prints `expected.txt`, a line per
feature:

| line | what the host did |
|---|---|
| `pairs`, `total`, `count` | programs: `t:pairs` with every choice taken; `shop:total("tea", "milk")` with prices 3 and 2; `t:count(10000)` |
| `call` | `t:add(2, 3)` |
| `held` | `t:counter(10)` held: the ref's type, `inc(5)`, attribute `n` |
| `released` | the ref released, then its attribute asked: the condition's kind |
| `value` | `t:echo([1, 2, 3])` held: the ref's type; `t:sum(ref)`; released |
| `condition` | `t:boom()`: `kind: message` |
| `escapes` | `t:echo` of `[9007199254740993, nan, 3.0, 0.5, {"a": 1}]`, printed as it is on the wire |
| `stream` | `t:numbers(5)`, credit 2, every chunk |
| `cancel` | `t:numbers(100)`, credit 2, three chunks taken, the rest cancelled; then `t:sent()`: 3 + 2 |
| `duplex` | `t:dedup()` fed `3, 1, 3, 2, 1`, credit 2 both ways |
| `mux` | `t:wait("g")` sent, then `t:open("g")`; the answers of `open` and of `wait` |
| `cbor+deflate` | a second worker, configured `cbor`/`deflate`: `t:add(2, 3)`, `t:pairs`, `t:numbers(3)` with credit 1 |
| `auth` | a third worker, started with `OKAY_WIRE_SECRET`, and the host proving the same secret: `t:add(2, 3)` |

## The files

| file | what it is |
|---|---|
| `transcript.txt` | the first transcript (program, continue, forget), without the hello; `test.sh` holds every worker to it |
| `calls.txt` | call, held objects and values, refs as arguments, release, conditions, the value escapes |
| `programs.txt` | programs: multi-shot, runs interleaved, forget, the refusals |
| `streams.txt` | streams both ways under credit, cancel, a failing stream |
| `mux.txt` | answers as calls finish |
| `configure.txt` | `configure` where it stays JSON lines, and its refusals |
| `expected.txt` | what any host prints with any worker |

A transcript: `>` a line the host sends, `<` a line the far side sends, `#` a comment. Send the `>` lines in order;
the far side's whole output, in order, must be the `<` lines — compared as JSON (fields in any order, a number
however spelled), and a string `"*"` in an expected line matches any value. All but `transcript.txt` begin with
the hello. Frames, and auth (its nonces are random), are not in transcripts: the hosts and workers test them
against each other through `expected.txt`.

## Not here

- **Tables** (`"table": true`, `{"t": "frame"}`, the hello's `frames`, Arrow parts): a capability of far sides
  that have tables (okay's Python, R, Rust, Go carry data frames for the platform's `Table`). These kernels have
  no table; the hello does not claim `frames`, and a frame is refused as an unknown tag.
- **Direct style** (`once`, a function calling back from an ordinary stack) and `continue` with a `condition`:
  see "program" above.
- `{"t": "bytes"}`, the `zlib` compression, okay's `verify` and `describe` operations.
- TCP, TLS, in-process links (FFM, WebAssembly), a deadline on answers, a supervisor that restarts a far side.
