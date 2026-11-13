# okay-ts

Repo: <https://github.com/okay-effects/okay-ts> (README.md, AGENTS.md). ESM, no runtime dependencies; it ships
TypeScript source (`exports: "./src/index.ts"`), so use it through a TypeScript toolchain (tsc, a bundler, tsx).
Not on npm; install from git:

```sh
npm install github:okay-effects/okay-ts
```

Effect, program, handler, run (from `test/std.test.ts`, `test/intercept.test.ts`):

```ts
import { effect, op, answering, eff, Reader, State, of } from "okay-ts"

const Pay = effect("Pay", {
  transfer: op<[to: string, amount: number], string>(),
  balance: op<[], number>(),
}, { sensitive: true })                        // sensitive: handled only under audit(...)

const Config = Reader("Config", of<number>())  // standard effects
const Counter = State("Counter", of<number>())

const p = eff(function* () {                   // a generator program: one-shot
  const n = yield* Config.ask()
  yield* Counter.modify((s) => s + n)
  const s = yield* Counter.get()
  return s * 2
})

p.handle(Config.value(5)).handle(Counter.from(1)).run()   // [6, 12]; run() exists only on the empty row

const inPlace = answering(Pay, { transfer: (to, n) => `ok:${to}:${n}`, balance: () => 0 })
```

Other handler forms: `stateful(tag, s0, clauses)`, `deep(tag, ret, clauses)`, `deepMany(tag, ret, clauses)`
(`src/effects.ts`).

Multi-shot: only for programs written as data (`Do`, `flatMap`, `pure`); generators stay one-shot (no replay —
passing a generator to `all`/`first`/`deepMany` is a type error and throws `ResumedTwice`). From
`test/acceptance.test.ts`:

```ts
import { effect, op, deepMany, pure, Do, State, of } from "okay-ts"

const S = State("S", of<number>())
const Two = effect("Two", { pick: op<[], number>() })
const twice = deepMany(Two, (a: number) => pure([a]), {
  pick: (k) => {
    const one = k(1)
    const two = k(2)
    return two.flatMap((r2) => one.map((r1) => r1.concat(r2)))
  },
})
const picked = Do.bind("s", () => S.get()).bind("x", () => Two.pick())
  .do(({ s, x }) => S.put(s + x)).bind("t", () => S.get()).map(({ t }) => t)
picked.handle(S.from(10)).map(([, t]) => t).handle(twice).run()   // [11, 12]
```
