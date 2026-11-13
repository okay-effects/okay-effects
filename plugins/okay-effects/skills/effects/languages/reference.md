# okay-effects reference kernels (Scala 3, Java 25, Rust)

Repo: <https://github.com/okay-effects/okay-effects> — README.md (the guide, with the same program in all three
languages) and SPEC.md (the model, rules numbered). The minimal model of the family: the same properties as tests,
the same TCK digest, one wire, the standard effects, control and audit with sensitive effects refused by each
compiler outside their audit. **Nothing is published as an artifact**: use the kernels from source (copy the
directory, a git submodule, or a path/git dependency). Packages: `okay.effects.scala` (scala-cli,
`scala/project.scala`, Scala 3.3), `okay.effects.java` (plain `javac --release 25`), crate `okay-effects`
(lib `okay_effects`, `rust/Cargo.toml`, edition 2024), e.g.
`okay-effects = { git = "https://github.com/okay-effects/okay-effects" }` (Cargo finds the package in `rust/`).

## Rust (the doctest at the top of `rust/src/lib.rs`, run by `cargo test`)

```rust
use okay_effects::effects::Choose;
use okay_effects::{Effect, Handler, Has, Plain, Prog, Reply, eff, perform, pure};

enum Account { Deposit(i64, Reply<()>), Balance(Reply<i64>) }      // a Reply<X> witnesses the answer type
impl Effect for Account { type Kind = Plain; }                      // `Sensitive`: handled only under audit

fn deposit<R: Has<Account, I>, I>(amount: i64) -> impl Prog<(), R> { perform(|r| Account::Deposit(amount, r)) }
fn balance<R: Has<Account, I>, I>() -> impl Prog<i64, R> { perform(Account::Balance) }

fn program() -> impl Prog<i64, eff![Account, Choose]> {
    Choose::among(vec![1, 2]).flat_map(|x| deposit(x * 10)).then(|| balance())
}
fn bank<A: 'static>(start: i64, p: impl Prog<A, eff![Account, Choose]>) -> impl Prog<A, eff![Choose]> {
    let answer = |balance: &mut i64, op| match op {
        Account::Deposit(amount, r) => { *balance += amount; r.give(()) }
        Account::Balance(r) => r.give(*balance),
    };
    Handler::in_place(start, |_, a| pure(a), answer).handle(p)
}
assert_eq!(Choose::all(bank(100, program())).run(), vec![110, 120]);   // each path its own balance
```

A computation runs once, so a program is a function returning `impl Prog`. A general handler is
`Handler::new(ret, |op, k: K<B, R>| ...)` with `k.resume(reply, answer)` / `k.unwind()`; `K` is `Clone`, so
multi-shot is real.

## Scala 3 (from `scala/test/Properties.test.scala`)

```scala
import okay.effects.scala.*, std.*

enum Ask[X]:
  case Name() extends Ask[String]
def name[R[_]: Has[Ask]]: String ! R = perform[Ask](Ask.Name())

val tail = Handler.tail[Ask, Pure, String, String](pure)([X] => (op: Ask[X]) => op match
  case Ask.Name() => "Ann")
val general = Handler.general[Ask, Pure, String, List[String]](a => pure(List(a)))(
  [X] => (op: Ask[X], k: X => List[String] ! Pure) => op match
    case Ask.Name() => k("Ann").flatMap(a => k("Bob").map(a ++ _)))     // multi-shot

val twice = name[Ask].flatMap(a => name[Ask].map(b => s"$a, $b"))
run(handle(twice, tail))      // "Ann, Ann"
run(handle(twice, general))   // List("Ann, Ann", "Ann, Bob", "Bob, Ann", "Bob, Bob")
```

## Java 25 (from `java/test/okay/effects/java/Properties.java`)

```java
import static okay.effects.java.Effects.*;   // and okay.effects.java.*, okay.effects.java.std.*

sealed interface Account {}
record Deposit(int amount) implements Account, Op<Account, Integer, Deposit> {}

static <B, R> Comp<B, R> account(BiFunction<Deposit, K<B, R>, Comp<B, R>> on, Comp<B, ? super Eff<Account, R>> p) {
  return handle(Account.class, new Handler<Account, B, B, R>() {
    public Comp<B, R> ret(B a) { return pure(a); }
    public Comp<B, R> on(Account op, K<B, R> k) {
      return switch (op) { case Deposit deposit -> on.apply(deposit, k); };
    }
  }, p);
}

Comp<Integer, Eff<Account, ?>> p = perform(new Deposit(1));
Integer v = run(account((deposit, k) -> k.resume(deposit, deposit.amount() + 1), p));   // 2
```

Rows are positional in Java: `up`, `under`, `fit` move a program into a bigger row. In place: `InPlace` with
`handle(Class, inPlace, s0, p)`.

## Control and audit

README "Control and audit" has the payments example in all three languages; SPEC.md §9 the rules. In short:

- `observe` shows each operation of an effect to a program and passes it on; `intercept` puts a handler between
  a program and the real handler of its effect (the effect stays in the row); Scala's `around` gives the answer.
- A **sensitive** effect (`given Sensitive[Pay] = Sensitive()` in Scala; operations implementing
  `Sensitive.Op<...>` in Java; `type Kind = Sensitive` in Rust) is refused by `handle` and `intercept`. `audit`
  gives each operation to an `Interceptor` with `pass` (its one way on) and leaves `Audited[Pay]`, which only
  `handleAudited` takes. The trail is an ordinary effect (`Audit` / `Trail`) handled outside.
- An interceptor goes on with `k.after(c)`, not `c.flatMap(k)`, so that finalizers run if the answering handler
  leaves.
