# The wire, against the family

`wire/README.md` says the wire here is okay's. This directory is the check of that claim: our host and our workers
against the implementations of the same protocol in the sibling repositories, found next to this one (`../okay`,
`../okay-zig`, `../okay-lean`, `../okay-ts`).

```
wire/family/check.sh
```

A line for each feature: `  + <far side>: <feature>`, or `==> X <far side>: <feature>: <expected, and what came>`;
`  - skipped: <what> (<why>)` for a sibling that is absent or not built. It ends with
`family: N passed, M failed, K skipped` and exits 0 only if nothing failed; a skip is no failure. About 20 s once
everything is built.

Nothing is written outside `wire/family/out` (ignored by git): our host and workers are built there from `scala/src`,
`java/src` and `rust/`, which are only read. The siblings are only read and run, with one exception: okay-zig's worker
is built by okay-zig's own command when it is absent or older than its sources. okay is never built from here.

## What is checked against what

| direction | host | far side | what |
|---|---|---|---|
| 1 | ours (Scala, `host/Family.scala` over `scala/src`) | okay-zig's `zig-worker` | okay's conformance module: multi-shot `pairs`, `total`, direct-style `quote`, a condition and the worker living on, a held value by ref then released, mux, a stream under credit, the credit counted on the far side, an early stop, `sum_after` fed by the host, a duplex `dedup` of 20 000 rows |
| 1 | ours | okay-lean's `lean-worker` | the same module, what Lean serves of it: `pairs`, `total`, a condition, a held value (no direct style, no mux, no streams there) |
| 1 | ours | okay-ts (`ts/worker.ts`: okay-ts's own `serveStdio`, the programs as okay's test of okay-ts writes them) | `pairs`, `total`, `quote` as a generator, a condition |
| 1 | ours | okay's reference Python shim, serving the module of its `wire.txt`'s header | in `json`, `cbor`, `json+deflate`, `cbor+deflate`: a call, the value escapes round trip, an object with a method and an attribute then released, `t:pairs`, direct-style `t:quote`, a condition; and `wire.txt` itself, every line as written |
| 2 | — (okay's `wire.txt`, line by line) | our Scala, Java, Rust workers | every section of okay's transcript but the table call |
| 3 | okay's own (`okay.foreign.ForeignWorker`, `okay-host/OkayHost.scala`) | our Scala, Java, Rust workers | our demonstration module: the hello, a call, a held object, a held value by ref, multi-shot, programs answered by the host, a failure, the escapes, mux, a stream under credit, a cancel counted on the far side, a duplex `dedup` of 20 000 values, a stream that cannot start, `configure` to CBOR and DEFLATE, auth both ways and its refusal |

Where our workers are held to okay's transcript less than literally (direction 2), by what `wire/README.md` says is
not here: the table call is not sent; a node's `once` is not expected; and `k` is the far side's own number — okay's
Python shim answers `"k": 1` for the direct-style node of its run 2, our workers number every continuation of the
far side from one counter and answer `"k": 2`. The `continue` is sent with the `k` the far side gave. (okay's far
sides differ among themselves here: for a second run of a program as data, the Python shim and okay-lean answer
`k` 2, okay-zig `k` 1.)

Direction 3 uses okay's host without building okay: the classes okay's build left in its `target` directories
(module okay-py and its dependencies, listed in `check.sh`), on the class path of a small scala-cli program. It
is skipped if they are not there, or if an sbt is running in okay. okay's `WireConformance` suite itself is not run
on our workers: it asks for okay's conformance module (`pairs` of two choices, `total(sku, qty)`, `await_open`,
`numbers(n, size, tag)`, …), which our workers do not serve, and okay has no way to give a suite a worker's command
from outside — see below.

## What each far side needs

| far side | needs | if it is not there |
|---|---|---|
| ours | `java` (25 or later), `scala-cli`, `javac`, `cargo` | no `java` or `scala-cli`: everything skipped; no `javac` or `cargo`: that worker skipped |
| okay-zig | `../okay-zig/zig-out/bin/zig-worker`; built with `zig build worker -Doptimize=ReleaseSafe` if absent or stale and zig is on `PATH` or in `~/.zig/zig-*/` | skipped; with a stale binary and no zig, checked as it is, and said |
| okay-lean | `../okay-lean/.lake/build/bin/lean-worker` (`lake build lean-worker`; not built from here: minutes) | skipped |
| okay-ts | `../okay-ts/src/index.ts`, `node` that runs TypeScript | skipped |
| okay's Python shim | `../okay/okay-py/src/main/resources/okay/py/{shim.py,wire.txt}`, `python3` | skipped |
| okay's host | okay built by its own gate: `okay-py/target/scala-3.*/classes` and its dependencies' | skipped |

## Verified on 2026-11-10

`check.sh`, twice: `family: 130 passed, 0 failed, 4 skipped`.

| sibling | commit | what ran |
|---|---|---|
| okay-effects | `5788bb2`, with the uncommitted work of that day in `scala/`, `java/`, `rust/` | host and workers built by the script |
| okay | `df0a3f132`, clean | `shim.py` under Python 3.14.8; the host from classes of Scala 3.9.0 compiled 2026-10-23 06:17, no source of the wire's modules changed since |
| okay-zig | `41ce59e`, clean | `zig-worker` rebuilt by the script with zig 0.17.0 (the one there was older than `src/`) |
| okay-lean | `55f4bf1` | `lean-worker` as built there on 2026-10-22 (Lean 4.35.0), not older than the wire's sources |
| okay-ts | `d74338a`, clean | node v26.10.0 |

Java 26.0.2.1, scala-cli 1.17.1, rustc 1.92.0. The 4 skips: okay's Rust crate, and the table call of `wire.txt`
for each of our three workers.

## Not verified, and why

- **okay's `WireConformance` on our workers.** It would need, in okay, a suite that takes its worker's command from
  the environment — the hook that is missing, one class beside `TestRustLinks.scala`:
  `class TestExternalPipes extends WireConformance { lazy val engine = ForeignWorker.speaking(sys.env("OKAY_WIRE_WORKER").split(' ').toSeq) }`
  (ignored when the variable is not set) — and, here, workers serving okay's conformance module. okay-zig and
  okay-lean do it by copying their own test file into a clone of okay and running sbt there; not done from a check.
- **okay's Rust crate** as a far side: its conformance worker is a string inside okay's `TestRustLinks.scala`,
  written to a temporary directory and built by that test only.
- **okay's Go, Haskell and R far sides**: not looked at.
- **Our Java and Rust hosts** with the family's far sides: only the Scala host is used here; `test.sh` holds the three
  hosts to the same answers with our own workers.
- **Tables, `once` continued twice, `continue` with a condition answered by a direct-style far side, TCP, TLS, FFM**:
  not in these kernels (`wire/README.md`, "Not here").
- **okay's host at okay's HEAD by construction**: the host is whatever okay's last build left; the script says so
  when the wire's sources are newer than the classes, and cannot tell more.
- **Other machines**: one run, on macOS (arm64).
