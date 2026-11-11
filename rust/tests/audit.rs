//! Control and audit: observe, the trail, sensitive effects — the tests of Scala's `Audits`, named alike, the trails
//! line for line. What rustc refuses is in the doctests of `src/lib.rs`.

use std::cell::RefCell;
use std::rc::Rc;

use okay_effects::effects::*;
use okay_effects::*;

fn told<R: Has<Audit, I>, I>(line: impl Into<String>) -> impl Prog<(), R> {
    Audit::record(line)
}

/// the state's operations, each recorded when it is asked and when it is answered: `who` says which auditor
fn recording<R: Has<State<i64>, I1> + Has<Audit, I2>, I1, I2, A: 'static>(
    who: &'static str,
    p: impl Prog<A, R>,
) -> impl Prog<A, R> {
    let on = move |op, k: K<A, R>| match op {
        State::Get(r) => told::<R, I2>(format!("{who}get"))
            .then(move || {
                let got = State::get::<R, I1>();
                k.after(r, got.flat_map(move |x: i64| told::<R, I2>(format!("{who}get → {x}")).map(move |_| x)))
            })
            .boxed(),
        State::Put(s, r) => told::<R, I2>(format!("{who}put {s}"))
            .then(move || {
                let put = State::put::<R, I1>(s);
                k.after(r, put.then(move || told::<R, I2>(format!("{who}put {s} → done"))))
            })
            .boxed(),
    };
    Handler::new(pure, on).intercept::<I1, _>(p)
}

fn counted<R: Has<State<i64>, I>, I>() -> impl Prog<i64, R> {
    State::get().flat_map(|s: i64| State::put(s + 1)).then(|| State::get())
}

fn chosen<R: Has<State<i64>, I1> + Has<Choose, I2>, I1, I2>() -> impl Prog<i64, R> {
    let add = |x: i64| State::get().flat_map(move |s: i64| State::put(s + x).map(move |_| s + x));
    Choose::among(vec![1, 2]).flat_map(add)
}

fn lines<const N: usize>(all: [&str; N]) -> Vec<String> {
    all.iter().map(|line| line.to_string()).collect()
}

#[test]
fn an_observer_changes_nothing() {
    let seen: Rc<RefCell<Vec<String>>> = Rc::default();
    let log = seen.clone();
    let see = move |op: &State<i64>| {
        log.borrow_mut().push(match op {
            State::Get(_) => "get".to_string(),
            State::Put(s, _) => format!("put {s}"),
        });
        pure(())
    };
    type App = eff![Choose, State<i64>];
    let observed = State::run(0, Choose::all(observe(see.clone(), chosen::<App, _, _>()))).run();
    assert_eq!(observed, State::run(0, Choose::all(chosen::<App, _, _>())).run());
    assert_eq!(Choose::all(State::run(0, observe(see, chosen::<App, _, _>()))).run(), vec![(1, 1), (2, 2)]);
    assert_eq!(seen.borrow()[..4], lines(["get", "put 1", "get", "put 3"]));
}

#[test]
fn the_trail_of_a_one_path_program_is_its_operations_in_order() {
    let p = recording("", counted::<eff![State<i64>, Audit], _>());
    assert_eq!(
        Audit::trail(State::run(5, p)).run(),
        (lines(["get", "get → 5", "put 6", "put 6 → done", "get", "get → 6"]), (6, 6))
    );
}

#[test]
fn the_trail_under_a_choice_one_trail_through_all_paths_or_each_path_its_own() {
    let through = recording("", chosen::<eff![Choose, State<i64>, Audit], _, _>());
    assert_eq!(
        Audit::trail(State::run(0, Choose::all(through))).run(),
        (
            lines(["get", "get → 0", "put 1", "put 1 → done", "get", "get → 1", "put 3", "put 3 → done"]),
            (3, vec![1, 3])
        )
    );
    let each = recording("", chosen::<eff![State<i64>, Audit, Choose], _, _>());
    assert_eq!(
        Choose::all(Audit::trail(State::run(0, each))).run(),
        vec![
            (lines(["get", "get → 0", "put 1", "put 1 → done"]), (1, 1)),
            (lines(["get", "get → 0", "put 2", "put 2 → done"]), (2, 2)),
        ]
    );
    // an answer is recorded in the rest of whoever answers it: given twice, it is recorded twice
    type Picks = eff![Choose, Audit];
    let on = |op, k: K<usize, Picks>| match op {
        Choose::Among(n, r) => told("among").then(move || {
            let pick = perform(move |r| Choose::Among(n, r));
            k.after(r, pick.flat_map(|x: usize| told(format!("→ {x}")).map(move |_| x)))
        }),
    };
    let picked = Handler::new(pure, on).intercept(perform::<Choose, usize, Picks, _>(|r| Choose::Among(2, r)));
    assert_eq!(Audit::trail(Choose::all(picked)).run(), (lines(["among", "→ 0", "→ 1"]), vec![0, 1]));
}

type Refusals = eff![Throws<String>, State<i64>, Audit];

fn refusing<A: 'static>(p: impl Prog<A, Refusals>) -> impl Prog<A, Refusals> {
    let on = |op, k: K<A, Refusals>| match op {
        State::Put(s, r) if s > 2 => told(format!("put {s} refused"))
            .then(move || k.after(r, Throws::raise(format!("put {s} refused"))))
            .boxed(),
        State::Put(s, r) => told(format!("put {s}")).then(move || k.after(r, State::put(s))).boxed(),
        State::Get(r) => k.after(r, State::get()).boxed(),
    };
    Handler::new(pure, on).intercept(p)
}

#[test]
fn an_auditor_that_refuses() {
    fn p() -> impl Prog<i64, Refusals> {
        State::put(1).then(|| State::put(5)).then(|| State::get())
    }
    let refused = Err("put 5 refused".to_string());
    assert_eq!(
        Audit::trail(State::run(0, Throws::run(refusing(p())))).run(),
        (lines(["put 1", "put 5 refused"]), (1, refused.clone()))
    );
    // refused, the program is left: its finalizer runs, and its operation passes the auditor
    let closed = p().ensuring(|| State::put(2));
    assert_eq!(
        Audit::trail(State::run(0, Throws::run(refusing(closed)))).run(),
        (lines(["put 1", "put 5 refused", "put 2"]), (2, refused))
    );
}

#[test]
fn an_auditor_that_changes_an_operation() {
    type App = eff![State<i64>, Audit];
    let on = |op, k: K<i64, App>| match op {
        State::Put(s, r) if s > 2 => told(format!("put {s} as 2")).then(move || k.after(r, State::put(2))).boxed(),
        State::Put(s, r) => told(format!("put {s}")).then(move || k.after(r, State::put(s))).boxed(),
        State::Get(r) => k.after(r, State::get()).boxed(),
    };
    let p = State::put::<App, _>(1).then(|| State::put(5)).then(|| State::get());
    let capped = Handler::new(pure, on).intercept(p);
    assert_eq!(Audit::trail(State::run(0, capped)).run(), (lines(["put 1", "put 5 as 2"]), (2, 2)));
}

#[test]
fn two_auditors_of_one_effect_inner_and_outer() {
    let p = State::get::<eff![State<i64>, Audit], _>().flat_map(|s: i64| State::put(s + 1).map(move |_| s));
    assert_eq!(
        Audit::trail(State::run(0, recording("outer ", recording("inner ", p)))).run(),
        (
            lines([
                "inner get",
                "outer get",
                "outer get → 0",
                "inner get → 0",
                "inner put 1",
                "outer put 1",
                "outer put 1 → done",
                "inner put 1 → done"
            ]),
            (1, 0)
        )
    );
}

// Rust finds a handler by the effect's type: two handlers of one type are told apart by an identity in the
// operation and `mine`, as in the TCK. So the state here is a slot, its operations naming which.
#[derive(Clone)]
enum Slot {
    Get(u8, Reply<i64>),
    Put(u8, i64, Reply<()>),
}

impl Effect for Slot {
    type Kind = Plain;
}

fn slot_get<R: Has<Slot, I>, I>(id: u8) -> impl Prog<i64, R> {
    perform(move |r| Slot::Get(id, r))
}

fn slot_put<R: Has<Slot, I>, I>(id: u8, s: i64) -> impl Prog<(), R> {
    perform(move |r| Slot::Put(id, s, r))
}

fn of(op: &Slot) -> u8 {
    match op {
        Slot::Get(id, _) | Slot::Put(id, _, _) => *id,
    }
}

fn slot_answer(state: &mut i64, op: Slot) -> Answer {
    match op {
        Slot::Get(_, r) => r.give(*state),
        Slot::Put(_, s, r) => {
            *state = s;
            r.give(())
        }
    }
}

#[test]
fn an_audit_between_two_handlers_of_one_effect() {
    type App = eff![Slot, Audit];
    let p = slot_put::<App, _>(0, 1)
        .then(|| slot_put(1, 10))
        .then(|| slot_get(0))
        .flat_map(|a: i64| slot_get(1).map(move |b: i64| (a, b)));
    let inner = Handler::in_place(0, |s, a| pure((s, a)), slot_answer).mine(|op| of(op) == 0).intercept(p);
    let on = |op, k: K<(i64, (i64, i64)), App>| match op {
        Slot::Get(id, r) => told("get")
            .then(move || k.after(r, slot_get(id).flat_map(move |x: i64| told(format!("get → {x}")).map(move |_| x))))
            .boxed(),
        Slot::Put(id, s, r) => told(format!("put {s}"))
            .then(move || k.after(r, slot_put(id, s).then(move || told(format!("put {s} → done")))))
            .boxed(),
    };
    let between = Handler::new(pure, on).mine(|op| of(op) == 1).intercept(inner);
    let outer = Handler::in_place(0, |s, a| pure((s, a)), slot_answer).handle(between);
    // between them: what the inner one answers does not come here, the outer one's operations do
    assert_eq!(
        Audit::trail(outer).run(),
        (lines(["put 10", "put 10 → done", "get", "get → 10"]), (10, (1, (1, 10))))
    );
}

// ---- a sensitive effect

#[derive(Clone)]
enum Pay {
    Transfer(String, i64, Reply<String>),
    Balance(Reply<i64>),
}

impl Effect for Pay {
    type Kind = Sensitive;
}

fn bank<A: 'static, R: 'static>(start: i64) -> Handler<Pay, A, (i64, A), R> {
    let answer = |balance: &mut i64, op| match op {
        Pay::Transfer(to, amount, r) => {
            *balance -= amount;
            r.give(format!("ok:{to}:{amount}"))
        }
        Pay::Balance(r) => r.give(*balance),
    };
    Handler::in_place(start, |s, a| pure((s, a)), answer)
}

fn payments<R: Has<Pay, I>, I>() -> impl Prog<i64, R> {
    perform(|r| Pay::Transfer("a".to_string(), 100, r))
        .then(|| perform(Pay::Balance))
        .flat_map(|b: i64| perform(|r| Pay::Transfer("b".to_string(), 50, r)).map(move |_: String| b))
}

type Outside = eff![Audited<Pay>, Audit];

/// every operation passed on, recorded when asked and when answered
fn recorded() -> Interceptor<Pay, i64, i64, Outside> {
    Interceptor::new(pure, |op, pass: Pass<Pay, Outside>, k: K<i64, Outside>| match op {
        Pay::Transfer(to, amount, r) => {
            let asked = format!("transfer {amount} to {to}");
            told(asked.clone())
                .then(move || {
                    let passed = pass.perform(move |r| Pay::Transfer(to, amount, r));
                    k.after(r, passed.flat_map(move |x: String| told(format!("{asked} → {x}")).map(move |_| x)))
                })
                .boxed()
        }
        Pay::Balance(r) => told("balance")
            .then(move || {
                let passed = pass.perform(Pay::Balance);
                k.after(r, passed.flat_map(|x: i64| told(format!("balance → {x}")).map(move |_| x)))
            })
            .boxed(),
    })
}

#[test]
fn sensitive_handled_under_its_audit_it_runs_the_trail_complete() {
    let audited = recorded().audit(payments::<eff![Pay, Audited<Pay>, Audit], _>());
    assert_eq!(
        Audit::trail(bank(1000).handle_audited(audited)).run(),
        (
            lines([
                "transfer 100 to a",
                "transfer 100 to a → ok:a:100",
                "balance",
                "balance → 900",
                "transfer 50 to b",
                "transfer 50 to b → ok:b:50"
            ]),
            (850, 900)
        )
    );
}

#[test]
fn an_audited_body_asks_past_its_sensitive_place() {
    // the audit and its handler are one place of the row: what the body asks of another place passes both
    let passing = Interceptor::new(pure, |op, pass: Pass<Pay, Outside>, k: K<i64, Outside>| match op {
        Pay::Balance(r) => k.after(r, pass.perform(Pay::Balance)).boxed(),
        Pay::Transfer(to, n, r) => k.after(r, pass.perform(move |r| Pay::Transfer(to, n, r))).boxed(),
    });
    type Inside = eff![Pay, Audited<Pay>, Audit];
    let body =
        perform::<Pay, i64, Inside, _>(Pay::Balance).flat_map(|b: i64| told(format!("after {b}")).map(move |_| b));
    assert_eq!(Audit::trail(bank(7).handle_audited(passing.audit(body))).run(), (lines(["after 7"]), (7, 7)));
}

#[test]
fn sensitive_unaudited_the_compiler_refuses_it() {
    // rustc's refusals are the compile_fail doctests of src/lib.rs: handled plainly, intercepted, handled audited
    // with no audit, asked outside its audit, `Audited<Pay>` taken as an effect, seen by whoever steps it. What runs
    // is the way they leave: the audit, then its handler.
    let passing = Interceptor::new(pure, |op, pass: Pass<Pay, eff![Audited<Pay>]>, k: K<i64, eff![Audited<Pay>]>| {
        match op {
            Pay::Balance(r) => k.after(r, pass.perform(Pay::Balance)).boxed(),
            Pay::Transfer(to, n, r) => k.after(r, pass.perform(move |r| Pay::Transfer(to, n, r))).boxed(),
        }
    });
    let audited = passing.audit(perform::<Pay, i64, eff![Pay, Audited<Pay>], _>(Pay::Balance));
    assert_eq!(bank(7).handle_audited(audited).run(), (7, 7));
}

#[derive(Clone)]
enum Ledger {
    Deposit(i64, Reply<i64>),
}

impl Effect for Ledger {
    type Kind = Plain;
}

fn deposit<R: Has<Ledger, I>, I>(amount: i64) -> impl Prog<i64, R> {
    perform(move |r| Ledger::Deposit(amount, r))
}

#[test]
fn audit_with_ensuring_a_finalizers_operations_are_in_the_trail_one_that_ends_it_is_asked_not_answered() {
    type App = eff![Throws<String>, State<i64>, Audit];
    let failing = State::put::<App, _>(1).then(|| Throws::raise::<_, _, i64>("no".to_string()));
    let p = failing.ensuring(|| State::get().flat_map(|s: i64| State::put(s + 10)));
    assert_eq!(
        Audit::trail(State::run(0, Throws::run(recording("", p)))).run(),
        (
            lines(["put 1", "put 1 → done", "get", "get → 1", "put 11", "put 11 → done"]),
            (11, Err("no".to_string()))
        )
    );
    // the ledger leaves at a deposit below nothing: the finalizer's deposit asks a handler that has left
    type Books = eff![Ledger, Audit];
    let q = deposit::<Books, _>(1).then(|| deposit(-1)).ensuring(|| deposit(5));
    let on = |op, k: K<i64, Books>| match op {
        Ledger::Deposit(n, r) => told(format!("deposit {n}"))
            .then(move || {
                k.after(r, deposit(n).flat_map(move |x: i64| told(format!("deposit {n} → {x}")).map(move |_| x)))
            })
            .boxed(),
    };
    let ledger = |op, k: K<i64, eff![Audit]>| match op {
        Ledger::Deposit(n, _) if n < 0 => k.unwind().map(|_| -1).boxed(),
        Ledger::Deposit(n, r) => k.resume(r, n).boxed(),
    };
    let books = Handler::new(|_| pure(0), ledger).handle(Handler::new(pure, on).intercept(q));
    assert_eq!(Audit::trail(books).run(), (lines(["deposit 1", "deposit 1 → 1", "deposit -1", "deposit 5"]), -1));
}

fn balances(n: i64, sum: i64) -> Comp<i64, eff![Pay, Audited<Pay>]> {
    if n == 0 {
        return pure(sum).boxed();
    }
    perform(Pay::Balance).flat_map(move |b: i64| balances(n - 1, sum + b)).boxed()
}

#[test]
#[cfg_attr(miri, ignore)]
fn constant_stack_a_hundred_thousand_audited_operations() {
    let passing = Interceptor::new(pure, |op, pass: Pass<Pay, eff![Audited<Pay>]>, k: K<i64, eff![Audited<Pay>]>| {
        match op {
            Pay::Balance(r) => k.after(r, pass.perform(Pay::Balance)).boxed(),
            Pay::Transfer(to, n, r) => k.after(r, pass.perform(move |r| Pay::Transfer(to, n, r))).boxed(),
        }
    });
    assert_eq!(bank(1).handle_audited(passing.audit(balances(100_000, 0))).run(), (1, 100_000));
}
