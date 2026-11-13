# okay-lean

Repo: <https://github.com/okay-effects/okay-lean> (README.md, AGENTS.md). Lean 4 (`v4.35.0`), no dependencies; the
okay-simple Free model with its laws proved. The README gives no `require` line; from Lake, presumably:

```toml
[[require]]
name = "okay-lean"
git = "https://github.com/okay-effects/okay-lean"
rev = "master"
```

Effect, program, handler, run (from `Tests/Main.lean`):

```lean
import Okay
open Okay.Effects Okay.Effects.Free

inductive Console : Type → Type                     -- an effect: a GADT, the index is the answer
  | readLine : Console String
  | print (s : String) : Console Unit

def echo [In Console R] : Unit ! R := do            -- a program over any row with Console
  let s ← perform Console.readLine
  perform (Console.print s)

def scripted [Split Console R Rest] (input : String) : A ! R → (A × List String) ! Rest :=
  answerWith (E := Console) (S := List String) (fun a out => pure (a, out.reverse))
    (fun out op => match op with
      | .readLine => (input, out)
      | .print s => ((), s :: out))
    []

#eval run (scripted "hi" (echo (R := [Console])))   -- ((), ["hi"]);  run accepts only `A ! []`
```

Handler forms: `handle ret clause p` (deep, gets `k`), `answer`, `answerWith`, `handleWith`. Multi-shot: `k` from
`handle` is a function, resumed any number of times (`Okay/Effects/Std.lean`, `all` over `Choose`).

Control and audit (`Okay.Effects.Intercept`, `Okay.Effects.Audit`): `intercept`, `around`, `observe`, `trail`. A
sensitive effect is asked as `sensitive op` under `[In (Sensitive Pay) R]`, handled only as `Audited Pay`, and the
only bridge between them is `audit describe p` or `auditWith describe ret clause p` (README, "Control and audit";
`Tests/InterceptTests.lean`).
