# okay-python

Repo: <https://github.com/okay-effects/okay-python> (README.md, `docs/guide.md` — every snippet there comes from a
test). Distribution `okay-effects`, import package `okay_effects`, Python >= 3.11, no dependencies, typed for
`mypy --strict`. Not on PyPI; install from git:

```sh
pip install git+https://github.com/okay-effects/okay-python
```

Effect, program, handler, run (from `tests/test_core.py`). Programs perform through a capability `Cap`, so handlers
are matched by capability, not by type:

```python
from collections.abc import Callable
from dataclasses import dataclass
from typing import Generic, TypeVar
from okay_effects import Cap, Eff, Op, answering, run

T = TypeVar("T"); A = TypeVar("A")

@dataclass(frozen=True)
class Ask(Op[T], Generic[T]):          # an operation; its type parameter is the answer
    pass

def reader(v: T, body: Callable[[Cap[Ask[T]]], Eff[A]]) -> Eff[A]:      # a handler answering in place
    return (yield from answering(Ask, lambda _: v, body))

def prog(outer: Cap[Ask[int]]) -> Eff[int]:                              # a program: a generator
    def inner_body(inner: Cap[Ask[int]]) -> Eff[int]:
        a = yield from outer.perform(Ask[int]())
        b = yield from inner.perform(Ask[int]())
        return a * 10 + b
    return (yield from reader(2, inner_body))

run(reader(1, prog))   # 12;  run_async for async hosts
```

Other handler forms (`docs/guide.md` §2): `handle(kind, answer, body)` (answer may return `Abort(v)` or `Raise(e)`),
`returning`, `stateful(kind, state, step, body)`, `resuming(kind, on, ret, body)`.

Multi-shot: only for programs written as data (`Data[A]`: `pure`, `cap.perform(op)`, `.bind`, `.map`, `.then`);
generators are one-shot (a `mypy --strict` error, `OneShot` at run time). No replay: the part before a choice runs
once (`tests/test_data.py`):

```python
from okay_effects import Cap, Data, pure, resuming, run

def body(c: Cap[Ask[int]]) -> Data[int]:
    return c.perform(Ask[int]()).map(lambda a: a + 1)

run(resuming(Ask, lambda op, k: k(1).bind(lambda x: k(10).map(lambda y: x + y)),
             lambda a: pure(str(a)), body))    # "211": k(1) gives "2", k(10) gives "11"
```

`all_paths(kind, options, body)` collects every path of a choice effect (`tests/test_data.py`).
