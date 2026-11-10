//! What the model promises, each promise a test.

use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;

use okay_effects::effects::*;
use okay_effects::*;

type Counter = row![State<i64>];

fn count(n: i64) -> Comp<Counter, i64> {
    if n == 0 {
        return State::get();
    }
    State::get().flat_map(|s: i64| State::put(s + 1)).then(move || count(n - 1))
}

#[test]
fn a_row_handlers_take_their_effects_one_by_one_a_program_runs_when_none_is_left() {
    let p: Comp<row![State<i64>, Writer<String>, Throws<String>], i64> = State::get().flat_map(|s: i64| {
        Writer::tell(format!("was {s}"))
            .then(move || State::put(s + 1))
            .then(move || if s > 100 { Throws::raise("too big".to_string()) } else { pure(()) })
            .map(move |_| s * 2)
    });
    let got = Throws::run(Writer::run(State::run(1, p))).run();
    assert_eq!(got, Ok((vec!["was 1".to_string()], (2, 2))));
}

#[test]
fn the_order_of_handlers_is_the_meaning_state_kept_or_lost_at_a_failure() {
    fn p() -> Comp<row![State<i64>, Throws<String>], ()> {
        State::put(5).then(|| Throws::raise("no".to_string()))
    }
    assert_eq!(State::run(0, Throws::run(p())).run(), (5, Err("no".to_string())));
    assert_eq!(Throws::run(State::run(0, p())).run(), Err("no".to_string()));
}

#[test]
fn constant_stack_a_million_operations() {
    assert_eq!(State::run(0, count(1_000_000)).run(), (1_000_000, 1_000_000));
}

#[test]
fn constant_stack_a_hundred_thousand_binds_nested_to_the_left() {
    let p: Comp<Counter, i64> =
        (0..100_000).fold(State::get(), |p, _| p.flat_map(|s: i64| State::put(s + 1).map(move |_| s + 1)));
    assert_eq!(State::run(0, p).run(), (100_000, 100_000));
}

fn even(n: u32) -> Comp<(), bool> {
    if n == 0 { pure(true) } else { defer(move || odd(n - 1)) }
}
fn odd(n: u32) -> Comp<(), bool> {
    if n == 0 { pure(false) } else { defer(move || even(n - 1)) }
}

#[test]
fn mutual_tail_recursion_without_end_a_million_calls() {
    assert!(even(1_000_000).run());
}

#[test]
fn a_continuation_taken_many_times_every_path_of_a_choice() {
    let p: Comp<row![Choose], (i32, i32)> =
        Choose::among(vec![1, 2, 3]).flat_map(|x| Choose::among(vec![10, 20]).map(move |y| (x, y)));
    assert_eq!(Choose::all(p).run(), vec![(1, 10), (1, 20), (2, 10), (2, 20), (3, 10), (3, 20)]);
}

#[test]
fn many_times_with_state_each_path_its_own_state_or_one_state_through_all() {
    fn p() -> Comp<row![State<i64>, Choose], i64> {
        Choose::among(vec![1, 2])
            .flat_map(|x| State::get().flat_map(move |s: i64| State::put(s + x).map(move |_| s + x)))
    }
    assert_eq!(Choose::all(State::run(0, p())).run(), vec![(1, 1), (2, 2)]);
    assert_eq!(State::run(0, Choose::all(p())).run(), (3, vec![1, 3]));
}

fn from(n: i64) -> Comp<row![Emit<i64>], Never> {
    Emit::send(n).then(move || from(n + 1))
}

#[test]
fn an_endless_stream_pulled() {
    assert_eq!(Emit::pull(from(0)).take(3).collect::<Vec<_>>(), vec![0, 1, 2]);
    assert_eq!(Emit::pull(from(0)).nth(1_000_000), Some(1_000_000));
}

#[test]
fn asynchrony_the_handler_returns_the_answer_resumes_the_program_later() {
    type Waiting = Rc<RefCell<VecDeque<Box<dyn FnOnce()>>>>;
    fn later(n: i32, waiting: &Waiting) -> Comp<row![Async], i32> {
        let waiting = waiting.clone();
        Async::wait(move |resume: Resume<i32>| waiting.borrow_mut().push_back(Box::new(move || resume(n))))
    }
    let waiting = Waiting::default();
    let result = Rc::new(Cell::new(None));
    let (out, second) = (result.clone(), waiting.clone());
    let p = later(1, &waiting).flat_map(move |a| later(2, &second).map(move |b| a + b));
    Async::start(p, move |sum| out.set(Some(sum)));
    assert_eq!(result.get(), None);
    loop {
        let Some(next) = waiting.borrow_mut().pop_front() else { break };
        next();
    }
    assert_eq!(result.get(), Some(3));
}

#[test]
fn interception_and_audit_an_operation_seen_changed_and_asked_again() {
    let seen: Rc<RefCell<Vec<&str>>> = Rc::default();
    let log = seen.clone();
    let audit = Handler::new(pure, move |op, k| {
        log.borrow_mut().push(if let State::Put(..) = op { "Put" } else { "Get" });
        match op {
            State::Put(s, r) if s > 2 => State::put(2).then(move || k.resume(r, ())),
            op => k.pass(op),
        }
    });
    assert_eq!(State::run(0, audit.intercept(count(5))).run(), (2, 2));
    assert_eq!(seen.borrow().iter().filter(|op| **op == "Put").count(), 5);
}

enum Account {
    Deposit(i64, Reply<i64>),
}

fn deposit<R: Has<Account, I>, I>(amount: i64) -> Comp<R, i64> {
    perform(|r| Account::Deposit(amount, r))
}

#[test]
fn delegation_an_effect_answered_by_the_operations_of_another() {
    fn on_state<R: Has<Account, I>, I: 'static, A: 'static>(p: Comp<R, A>) -> Comp<(State<i64>, R::Rest), A> {
        let on = |op, k: K<(State<i64>, R::Rest), A>| match op {
            Account::Deposit(amount, r) => State::get()
                .flat_map(move |s: i64| State::put(s + amount).map(move |_| s + amount))
                .flat_map(move |balance| k.resume(r, balance)),
        };
        Handler::new(pure, on).handle::<_, There<I>>(p.also::<State<i64>>())
    }
    let p: Comp<row![Account], i64> = deposit(10).then(|| deposit(5));
    assert_eq!(State::run(100, on_state(p)).run(), (115, 115));
}

#[test]
fn reader_a_value_of_the_surroundings_and_changed_for_a_part_of_the_program() {
    let p: Comp<row![Reader<i64>], (i64, i64)> =
        Reader::ask().flat_map(|a| Reader::local(|v| v * 10, Reader::ask()).map(move |b| (a, b)));
    assert_eq!(Reader::run(2, p).run(), (2, 20));
}

#[test]
fn a_failure_recovered_the_first_of_the_choices_fresh_numbers_a_clock_that_a_test_sets() {
    let failing: Comp<row![Throws<String>], usize> = Throws::raise("no".to_string());
    assert_eq!(Throws::recover(|e: String| pure(e.len()), failing).run(), 2);
    fn odd() -> Comp<row![Choose], i32> {
        Choose::among(vec![2, 4, 5, 7]).flat_map(|x| if x % 2 == 0 { Choose::none() } else { pure(x) })
    }
    assert_eq!(Choose::first(odd()).run(), Some(5));
    assert_eq!(Choose::all(odd()).run(), vec![5, 7]);
    let two: Comp<row![Fresh], (i64, i64)> = Fresh::fresh().flat_map(|a| Fresh::fresh().map(move |b| (a, b)));
    assert_eq!(Fresh::run(two).run(), (0, 1));
    assert_eq!(Clock::at(|| 42, Clock::now::<row![Clock], _>()).run(), 42);
}

#[test]
fn streams_collected_looped_over_mapped_filtered_cut_short_zipped_merged() {
    fn three<R: Has<Emit<i64>, I>, I: 'static>() -> Comp<R, ()> {
        Emit::send_all(vec![1, 2, 3])
    }
    assert_eq!(Emit::collect(three::<row![Emit<i64>], _>()).run(), (vec![1, 2, 3], ()));
    let summed = Emit::each(|x: i64| State::modify(move |s: i64| s + x), three::<row![Emit<i64>, State<i64>], _>());
    assert_eq!(State::run(0, summed).run().0, 6);
    let even = Emit::filter(|x| x % 2 == 0, from(0));
    let evens = Emit::take(3, Emit::map(|x| x * 2, even));
    assert_eq!(Emit::collect(evens).run().0, vec![0, 4, 8]);
    assert_eq!(Emit::pull(Emit::zip(from(0), three())).collect::<Vec<_>>(), vec![(0, 1), (1, 2), (2, 3)]);
    assert_eq!(Emit::pull(Emit::merge(three(), from(10))).take(5).collect::<Vec<_>>(), vec![1, 10, 2, 11, 3]);
}

/// A program started, and time moved on until it has its value.
fn wait_for<A: 'static>(timers: &Timers, p: Comp<row![Async], A>) -> A {
    let result = Rc::new(Cell::new(None));
    let out = result.clone();
    Async::start(p, move |a| out.set(Some(a)));
    timers.run();
    result.take().expect("the program came to its value")
}

#[test]
fn asynchrony_both_at_once_the_first_of_two_all_a_sleep() {
    let timers = Timers::new();
    let after = |millis: u64, n: i32| timers.sleep::<row![Async], _>(millis).map(move |_| n);
    assert_eq!(wait_for(&timers, Async::both(after(30, 1), after(10, 2))), (1, 2));
    assert_eq!(timers.now(), 30);
    assert_eq!(wait_for(&timers, Async::race(after(200, 1), after(10, 2))), 2);
    assert_eq!(wait_for(&timers, Async::all(vec![after(20, 1), after(10, 2), after(1, 3)])), vec![1, 2, 3]);
    assert_eq!(wait_for(&timers, after(5, 7).map(|n| n + 1)), 8);
}

#[test]
fn asynchrony_a_million_answers_given_at_once_take_no_stack() {
    fn sum(n: u32, so_far: u32) -> Comp<row![Async], u32> {
        if n == 0 {
            return pure(so_far);
        }
        Async::wait(|resume: Resume<u32>| resume(1)).flat_map(move |x| sum(n - 1, so_far + x))
    }
    assert_eq!(wait_for(&Timers::new(), sum(1_000_000, 0)), 1_000_000);
}

#[test]
fn no_handler_an_operation_left_in_the_row_is_refused_by_rustc_and_stepped_it_is_asked_at_the_top() {
    // `State::get().run()` does not compile: see the examples of lib.rs, which `cargo test` checks
    let open: Comp<row![State<i64>], i64> = State::get().map(|s: i64| s + 1);
    let Step::Asked(asked) = open.step() else { panic!("the operation has no handler") };
    let Some(State::Get(r)) = asked.op() else { panic!("the operation asked is a get") };
    assert_eq!(State::run(0, asked.resume(*r, 41)).run(), (0, 42));
}

#[test]
fn no_collector_a_long_chain_of_binds_or_of_frames_is_dropped_in_a_loop() {
    // binds never run
    let unrun: Comp<Counter, i64> = (0..1_000_000).fold(State::get(), |p, _| p.map(|s: i64| s + 1));
    drop(unrun);
    // frames waiting for a value that never comes
    fn deep(n: u32) -> Comp<row![Throws<String>], u32> {
        if n == 0 { Throws::raise("the bottom".to_string()) } else { defer(move || deep(n - 1)).map(|x| x + 1) }
    }
    assert_eq!(Throws::run(deep(1_000_000)).run(), Err("the bottom".to_string()));
}
