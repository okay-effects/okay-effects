//! What the model promises, each promise a test.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::rc::Rc;

use okay_algebraic::effects::*;
use okay_algebraic::*;

fn count(n: i64) -> Comp<i64> {
    if n == 0 {
        return get();
    }
    get::<i64>().flat_map(|s| put(s + 1)).then(move || count(n - 1))
}

#[test]
fn handlers_take_their_effects_one_by_one_a_program_runs_when_none_is_left() {
    let p = get::<i64>().flat_map(|s| tell(format!("was {s}")).then(move || put(s + 1)).map(move |_| s * 2));
    let got = throws::<String, _>(writer::<String, _>(state(1i64, p))).run();
    assert_eq!(got, Ok(Ok((vec!["was 1".to_string()], (2, 2)))));
}

#[test]
fn the_order_of_handlers_is_the_meaning_state_kept_or_lost_at_a_failure() {
    let p = || put(5i64).then(|| raise::<String, i64>("no".to_string()));
    assert_eq!(state(0i64, throws::<String, _>(p())).run(), Ok((5, Err("no".to_string()))));
    assert_eq!(throws::<String, _>(state(0i64, p())).run(), Ok(Err("no".to_string())));
}

#[test]
fn constant_stack_a_million_operations() {
    assert_eq!(state(0i64, count(1_000_000)).run(), Ok((1_000_000, 1_000_000)));
}

#[test]
fn constant_stack_a_hundred_thousand_binds_nested_to_the_left() {
    let p = (0..100_000).fold(get::<i64>(), |p, _| p.flat_map(|s| put(s + 1).map(move |_| s + 1)));
    assert_eq!(state(0i64, p).run(), Ok((100_000, 100_000)));
}

fn even(n: u32) -> Comp<bool> {
    if n == 0 { pure(true) } else { defer(move || odd(n - 1)) }
}
fn odd(n: u32) -> Comp<bool> {
    if n == 0 { pure(false) } else { defer(move || even(n - 1)) }
}

#[test]
fn mutual_tail_recursion_without_end_a_million_calls() {
    assert_eq!(even(1_000_000).run(), Ok(true));
}

#[test]
fn a_continuation_taken_many_times_every_path_of_a_choice() {
    let p = among(vec![1, 2, 3]).flat_map(|x| among(vec![10, 20]).map(move |y| (x, y)));
    assert_eq!(all::<i32, _>(p).run(), Ok(vec![(1, 10), (1, 20), (2, 10), (2, 20), (3, 10), (3, 20)]));
}

#[test]
fn many_times_with_state_each_path_its_own_state_or_one_state_through_all() {
    let p = || among(vec![1i64, 2]).flat_map(|x| get::<i64>().flat_map(move |s| put(s + x).map(move |_| s + x)));
    assert_eq!(all::<i64, _>(state(0i64, p())).run(), Ok(vec![(1, 1), (2, 2)]));
    assert_eq!(state(0i64, all::<i64, _>(p())).run(), Ok((3, vec![1, 3])));
}

fn from(n: u32) -> Comp<()> {
    send(n).then(move || from(n + 1))
}

#[test]
fn an_endless_stream_pulled() {
    let mut s = stream::<u32, _>(from(0));
    assert_eq!(s.by_ref().take(3).collect::<Vec<_>>(), vec![Ok(0), Ok(1), Ok(2)]);
    assert_eq!(s.nth(1_000_000 - 3), Some(Ok(1_000_000)));
}

#[test]
fn asynchrony_the_handler_returns_the_answer_resumes_the_program_later() {
    let waiting: Rc<RefCell<VecDeque<Box<dyn Fn()>>>> = Rc::default();
    let later = |n: i32| {
        let waiting = waiting.clone();
        wait(move |resume: Resume<i32>| waiting.borrow_mut().push_back(Box::new(move || resume(n))))
    };
    let result: Rc<RefCell<Vec<i32>>> = Rc::default();
    let (one, two, out) = (later(1), later(2), result.clone());
    start::<i32, _>(one.flat_map(move |a| two.clone().map(move |b| a + b)), move |a| out.borrow_mut().push(a));
    assert_eq!(*result.borrow(), vec![]);
    loop {
        let Some(next) = waiting.borrow_mut().pop_front() else { break };
        next();
    }
    assert_eq!(*result.borrow(), vec![3]);
}

#[test]
fn interception_and_audit_an_operation_seen_changed_and_asked_again() {
    let seen: Rc<RefCell<Vec<i64>>> = Rc::default();
    let log = seen.clone();
    let audit = Handler::new(|a: i64| pure(a)).on(move |op: &Put<i64>, k| {
        log.borrow_mut().push(op.0);
        if op.0 > 2 { put(2i64).flat_map(move |done| k.resume(done)) } else { k.pass() }
    });
    assert_eq!(state(0i64, audit.handle(count(5))).run(), Ok((2, 2)));
    assert_eq!(seen.borrow().len(), 5);
}

struct Deposit(i64);
impl Op for Deposit {
    type Answer = i64;
}

#[test]
fn delegation_an_effect_answered_by_the_operations_of_another() {
    let on_state = Handler::new(|a: i64| pure(a)).on(|deposit: &Deposit, k| {
        let amount = deposit.0;
        get::<i64>().flat_map(move |s| {
            let k = k.clone();
            put(s + amount).then(move || k.resume(s + amount))
        })
    });
    let p = perform(Deposit(10)).then(|| perform(Deposit(5)));
    assert_eq!(state(100i64, on_state.handle(p)).run(), Ok((115, 115)));
}

#[test]
fn no_handler_an_operation_that_reached_the_top_is_refused_by_name() {
    let refused = get::<i64>().run().unwrap_err();
    assert!(refused.op.contains("Get<i64>"), "{refused}");
}
