# okay-go

Repo: <https://github.com/okay-effects/okay-go> (README.md, `docs/guide.md`). Go 1.27. A program is plain Go code
that receives capabilities `okay.Cap[O]`; handlers are matched by capability identity, not by type. Core
continuations are one-shot coroutines; package `prog` (programs as data) has multi-shot `k`.

```sh
go get github.com/okay-effects/okay-go
```

Effect, program, handler, run (from `example_test.go`):

```go
import okay "github.com/okay-effects/okay-go"

type Ask struct{ okay.Answers[int] }       // an operation: a struct embedding Answers[R], R its answer

got := okay.Main(func(r *okay.Run) int {
	return okay.Answer(r, func(Ask) int { return 1 }, func(outer okay.Cap[Ask]) int {
		return okay.Answer(r, func(Ask) int { return 2 }, func(inner okay.Cap[Ask]) int {
			return okay.Perform(outer, Ask{})*10 + okay.Perform(inner, Ask{})
		})
	})
}) // 12
```

General handler: `okay.Handle(r, answer func(O) any, body)`. A host can step a program and answer from outside:

```go
run := okay.Start(func(r *okay.Run, h okay.Host) int {
	ask := okay.Outside[Ask](h)
	return okay.Perform(ask, Ask{}) + okay.Perform(ask, Ask{})
})
s := run.Step()
for s.Kind == okay.Yielded { s = run.Resume(21) }   // s.Value == 42
```

Audit and policy as middleware around an answerer (`examples/shop/middleware_test.go`); okay-go has no
"sensitive effect" marker, so put sensitive operations behind such middleware:

```go
auditing := func(op any, next okay.Answerer) (any, error) {
	v, err := next(op); audit = append(audit, fmt.Sprintf("%T %+v -> %v", op, op, v)); return v, err
}
security := okay.Only(func(op Charge, next okay.Answerer) (any, error) {
	if op.Cents > 5000 { return nil, errors.New("forbidden") }
	return next(op)
})
answer := okay.With(services.Answer, auditing, okay.Record(&recorded), security)
r, err := okay.Drive(checkout("bob", 2000), answer)   // checkout returns *okay.Computation[Receipt]
```

Multi-shot with package `prog` (import `github.com/okay-effects/okay-go/prog`; from `prog/prog_test.go`, names
unqualified there because the test is inside the package):

```go
type Flip struct{ Answers[bool] }
func all[A any](body func(Cap[Flip]) Prog[A]) Prog[[]A] {
	return Control(func(a A) Prog[[]A] { return Pure([]A{a}) },
		func(_ Flip, k func(bool) Prog[[]A]) Prog[[]A] {
			return Then(k(true), func(xs []A) Prog[[]A] {
				return Map(k(false), func(ys []A) []A { return append(append([]A{}, xs...), ys...) })
			})
		}, body)
}
```
