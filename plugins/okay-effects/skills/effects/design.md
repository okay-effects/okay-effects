# Designing code with effects

Pseudocode below is language-neutral; the per-language files in [languages/](languages/) have real syntax.

## 1. Start from the effects

Write down what the business logic asks and what each question answers, before any class or function:

```
effect Accounts   find(id: AccountId): Option[Account]      save(a: Account): Unit
effect Pay        transfer(from, to, amount): Receipt        -- sensitive
effect Clock      now(): Instant
effect Log        info(msg: String): Unit
```

These are the data model of the feature. Operations carry plain data (ids, amounts, records), never live objects.

## 2. Business logic is a program over those effects

```
def payInvoice(inv: Invoice): Receipt ! (Accounts + Pay + Clock + Log) =
  for from <- Accounts.find(inv.payer).orFail(NotFound(inv.payer))
      t    <- Clock.now()
      r    <- Pay.transfer(from.id, inv.payee, inv.amount)
      _    <- Log.info(s"paid ${inv.id} at $t")
  yield r
```

The type says everything the code may do. No client, connection, key, `System.currentTimeMillis`, `Random`, env
lookup, thread or global state appears in it.

## 3. Implementations are handlers, created by the host

```
val accounts = Accounts.sql(pool)           -- the pool exists only in the handler
val pay      = Pay.stripe(httpClient, key)  -- the key exists only in the handler
val clock    = Clock.system
val log      = Log.toStdout

main = run(log.handle(clock.handle(accounts.handle(audited(pay)(payInvoice(inv))))))   -- one line
```

- A handler is where the outside world is touched. A handler may itself ask other effects (a `Pay` handler that
  asks `Log` and `Clock`), which keeps it testable too.
- The order of handlers is meaning: a state handler outside a choice handler shares one state across branches;
  inside, each branch gets its own. Decide it deliberately.
- Handlers in place (state, reader, writer) answer from state they keep and capture nothing: cheap. Handlers that
  take `k` (abort, choice, async, generators) are the general case.

## 4. Run only at the entry

`run` is called in `main`, in a request handler of the server, in a test. Never inside a program. If part of a
program needs a local handler (a transaction, a scoped state, a retry), install it there: `Tx.handle(sub)` is
itself a program with a smaller row, and it composes. A nested `run` hides effects from the type, breaks
audit and interception, and loses the continuation.

## 5. Sensitive operations under audit

Payments, approvals, permission changes, deletions, sending mail, an agent's tool calls: declare the effect
sensitive where the library supports it (okay-effects kernels, okay-scala, okay-rust; see the language files). Then
the compiler refuses to handle it except under an audit, and every operation passes an interceptor that can
record, change, answer or refuse it. Where the library has no sensitive marker, still put an interceptor between
the program and the handler and keep the trail as an ordinary effect (`Audit`).

## 6. No ambient effects

| ambient thing | instead |
|---|---|
| clock, timers, sleep | `Clock` / `Time` effect |
| random, UUIDs | `Random` effect (seeded handler in tests) |
| files, network, DB, subprocess | a domain effect (`Accounts`, `Mail`), the IO in its handler |
| env vars, config files | `Config` / `Reader` effect, read by the host |
| threads, futures, executors | async handler or the library's concurrency effect |
| mutable globals, singletons | `State` effect |
| logging | `Log` / `Writer` effect |
| exceptions as control flow | `Fail` / `Error` effect, or `Option`/`Either` in data |

## 7. Tests swap handlers

```
test "pays and logs":
  val (logs, result) = run(Log.collect(Clock.fixed(t0).handle(Accounts.inMemory(seed).handle(
                         Pay.recording.handle(payInvoice(inv))))))
  assert result == Receipt(...) and logs == List("paid inv-1 at t0")
```

No mocks: the in-memory handler is a real implementation of the effect. Also useful: a failing handler (to test
error paths), a recording handler (to assert the exact operations asked), a handler that replays a recorded log.

## 8. Multi-shot without replay

Choice, search, backtracking, generators resume `k` more than once. In okay libraries `k` is a value (the
machine's levels, an explicit state machine, a cloneable coroutine) and resuming it twice does not re-run the
program's prefix. Never implement multi-shot by replay (running the body again and feeding the old answers);
if a library only offers one-shot `k` (generators, coroutines), stay one-shot there and use a library with real
multi-shot `k` for search.

## When not to use effects

- Pure computation (parsing, validation, arithmetic, formatting, data transformation): plain functions. Call them
  from programs; do not lift every step into an effect.
- A detail used once inside one handler: keep it in the handler, no new effect.
- A tight inner loop where per-operation overhead matters: compute purely and ask one effect with the result.
- Throwaway scripts without IO worth isolating, unless the project's rule says everything goes through effects.

## Common mistakes

- `run` inside a program, or a handler that calls `run` on another program: install the handler instead.
- A program receiving a client, connection, file handle or key as a parameter: pass an id, ask an effect.
- An operation that returns a live object (a connection, a stream handle) to the program: return data, or keep
  the object in the handler and return an id.
- One giant `App` effect with forty operations: split by domain; rows compose.
- Handlers ordered by accident: state and choice, error and state, log and abort give different results.
- A handler that forgets to resume `k` (the program silently stops) or resumes twice when it meant once.
- `Thread.sleep`, `now()`, `random()`, `getenv` "just for a log line" in business code.
- Mocks in tests where an in-memory handler would do.
- Sensitive operations handled by a plain handler, without an interceptor and trail.
- Inventing API from memory: open the library's README, AGENTS.md and tests first; names differ per language.
