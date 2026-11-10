//! What the model promises, each promise a test.

use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;

use okay_effects::effects::*;
use okay_effects::*;

type Counter = eff![State<i64>];

fn count(n: i64) -> Comp<i64, Counter> {
    if n == 0 {
        return State::get().boxed();
    }
    State::get().flat_map(|s: i64| State::put(s + 1)).then(move || count(n - 1)).boxed()
}

/// a count to `n`, its binds nested to the left
fn nested(n: i64) -> Comp<i64, Counter> {
    (0..n).fold(State::get().boxed(), |p, _| p.flat_map(|s: i64| State::put(s + 1).map(move |_| s + 1)).boxed())
}

#[test]
fn a_row_handlers_take_their_effects_one_by_one_a_program_runs_when_none_is_left() {
    let p = State::get::<eff![State<i64>, Writer<String>, Throws<String>], _>().flat_map(|s: i64| {
        Writer::tell(format!("was {s}"))
            .then(move || State::put(s + 1))
            .then(move || if s > 100 { Throws::raise("too big".to_string()).boxed() } else { pure(()).boxed() })
            .map(move |_| s * 2)
    });
    let got = Throws::run(Writer::run(State::run(1, p))).run();
    assert_eq!(got, Ok((vec!["was 1".to_string()], (2, 2))));
}

#[test]
fn the_order_of_handlers_is_the_meaning_state_kept_or_lost_at_a_failure() {
    fn p() -> impl Prog<(), eff![State<i64>, Throws<String>]> {
        State::put(5).then(|| Throws::raise("no".to_string()))
    }
    assert_eq!(State::run(0, Throws::run(p())).run(), (5, Err("no".to_string())));
    assert_eq!(Throws::run(State::run(0, p())).run(), Err("no".to_string()));
}

#[test]
#[cfg_attr(miri, ignore)]
fn constant_stack_a_million_operations() {
    assert_eq!(State::run(0, count(1_000_000)).run(), (1_000_000, 1_000_000));
}

#[test]
fn constant_stack_a_few_thousand_operations() {
    assert_eq!(State::run(0, count(3_000)).run(), (3_000, 3_000));
}

#[test]
#[cfg_attr(miri, ignore)]
fn constant_stack_a_hundred_thousand_binds_nested_to_the_left() {
    assert_eq!(State::run(0, nested(100_000)).run(), (100_000, 100_000));
}

#[test]
fn constant_stack_a_few_thousand_binds_nested_to_the_left() {
    assert_eq!(State::run(0, nested(3_000)).run(), (3_000, 3_000));
}

fn even(n: u32) -> Comp<bool, ()> {
    if n == 0 { pure(true).boxed() } else { defer(move || odd(n - 1)) }
}
fn odd(n: u32) -> Comp<bool, ()> {
    if n == 0 { pure(false).boxed() } else { defer(move || even(n - 1)) }
}

#[test]
#[cfg_attr(miri, ignore)]
fn mutual_tail_recursion_without_end_a_million_calls() {
    assert!(even(1_000_000).run());
}

#[test]
fn mutual_tail_recursion_a_few_thousand_calls() {
    assert!(even(3_000).run());
    assert!(!even(3_001).run());
}

#[test]
fn a_continuation_taken_many_times_every_path_of_a_choice() {
    let p = Choose::among::<eff![Choose], _, _>(vec![1, 2, 3])
        .flat_map(|x| Choose::among(vec![10, 20]).map(move |y| (x, y)));
    assert_eq!(Choose::all(p).run(), vec![(1, 10), (1, 20), (2, 10), (2, 20), (3, 10), (3, 20)]);
}

#[test]
fn many_times_with_state_each_path_its_own_state_or_one_state_through_all() {
    fn p() -> impl Prog<i64, eff![State<i64>, Choose]> {
        Choose::among(vec![1, 2])
            .flat_map(|x| State::get().flat_map(move |s: i64| State::put(s + x).map(move |_| s + x)))
    }
    assert_eq!(Choose::all(State::run(0, p())).run(), vec![(1, 1), (2, 2)]);
    assert_eq!(State::run(0, Choose::all(p())).run(), (3, vec![1, 3]));
}

fn from(n: i64) -> Comp<Never, eff![Emit<i64>]> {
    Emit::send(n).then(move || from(n + 1)).boxed()
}

#[test]
#[cfg_attr(miri, ignore)]
fn an_endless_stream_pulled() {
    assert_eq!(Emit::pull(from(0)).take(3).collect::<Vec<_>>(), vec![0, 1, 2]);
    assert_eq!(Emit::pull(from(0)).nth(1_000_000), Some(1_000_000));
}

#[test]
fn an_endless_stream_pulled_a_few_thousand_values() {
    assert_eq!(Emit::pull(from(0)).take(3).collect::<Vec<_>>(), vec![0, 1, 2]);
    assert_eq!(Emit::pull(from(0)).nth(3_000), Some(3_000));
}

#[test]
fn asynchrony_the_handler_returns_the_answer_resumes_the_program_later() {
    type Waiting = Rc<RefCell<VecDeque<Box<dyn FnOnce()>>>>;
    fn later(n: i32, waiting: &Waiting) -> impl Prog<i32, eff![Async]> + use<> {
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
            State::Put(s, r) if s > 2 => State::put(2).then(move || k.resume(r, ())).boxed(),
            op => k.pass(op).boxed(),
        }
    });
    assert_eq!(State::run(0, audit.intercept(count(5))).run(), (2, 2));
    assert_eq!(seen.borrow().iter().filter(|op| **op == "Put").count(), 5);
}

#[derive(Clone)]
enum Account {
    Deposit(i64, Reply<i64>),
}

fn deposit<R: Has<Account, I>, I>(amount: i64) -> impl Prog<i64, R> {
    perform(|r| Account::Deposit(amount, r))
}

#[test]
fn delegation_an_effect_answered_by_the_operations_of_another() {
    fn on_state<R: Has<Account, I>, I: 'static, A: 'static>(
        p: impl Prog<A, R>,
    ) -> impl Prog<A, (State<i64>, R::Rest)> {
        let on = |op, k: K<A, (State<i64>, R::Rest)>| match op {
            Account::Deposit(amount, r) => State::get()
                .flat_map(move |s: i64| State::put(s + amount).map(move |_| s + amount))
                .flat_map(move |balance| k.resume(r, balance)),
        };
        Handler::new(pure, on).handle::<_, There<I>, _>(p.also::<State<i64>>())
    }
    let p = deposit::<eff![Account], _>(10).then(|| deposit(5));
    assert_eq!(State::run(100, on_state(p)).run(), (115, 115));
}

#[test]
fn reader_a_value_of_the_surroundings_and_changed_for_a_part_of_the_program() {
    let p = Reader::ask::<eff![Reader<i64>], _>()
        .flat_map(|a| Reader::local(|v| v * 10, Reader::ask()).map(move |b| (a, b)));
    assert_eq!(Reader::run(2, p).run(), (2, 20));
}

#[test]
fn a_failure_recovered_the_first_of_the_choices_fresh_numbers_a_clock_that_a_test_sets() {
    let failing = Throws::raise::<eff![Throws<String>], _, usize>("no".to_string());
    assert_eq!(Throws::recover(|e: String| pure(e.len()), failing).run(), 2);
    fn odd() -> impl Prog<i32, eff![Choose]> {
        Choose::among(vec![2, 4, 5, 7])
            .flat_map(|x| if x % 2 == 0 { Choose::none().boxed() } else { pure(x).boxed() })
    }
    assert_eq!(Choose::first(odd()).run(), Some(5));
    assert_eq!(Choose::all(odd()).run(), vec![5, 7]);
    let two = Fresh::fresh::<eff![Fresh], _>().flat_map(|a| Fresh::fresh().map(move |b| (a, b)));
    assert_eq!(Fresh::run(two).run(), (0, 1));
    assert_eq!(Clock::at(|| 42, Clock::now::<eff![Clock], _>()).run(), 42);
}

#[test]
fn streams_collected_looped_over_mapped_filtered_cut_short_zipped_merged() {
    fn three<R: Has<Emit<i64>, I>, I: 'static>() -> impl Prog<(), R> {
        Emit::send_all(vec![1, 2, 3])
    }
    assert_eq!(Emit::collect(three::<eff![Emit<i64>], _>()).run(), (vec![1, 2, 3], ()));
    let summed = Emit::each(|x: i64| State::modify(move |s: i64| s + x), three::<eff![Emit<i64>, State<i64>], _>());
    assert_eq!(State::run(0, summed).run().0, 6);
    let even = Emit::filter(|x| x % 2 == 0, from(0));
    let evens = Emit::take(3, Emit::map(|x| x * 2, even));
    assert_eq!(Emit::collect(evens).run().0, vec![0, 4, 8]);
    assert_eq!(Emit::pull(Emit::zip(from(0), three())).collect::<Vec<_>>(), vec![(0, 1), (1, 2), (2, 3)]);
    assert_eq!(Emit::pull(Emit::merge(three(), from(10))).take(5).collect::<Vec<_>>(), vec![1, 10, 2, 11, 3]);
}

/// A program started, and time moved on until it has its value.
fn wait_for<A: 'static>(timers: &Timers, p: impl Prog<A, eff![Async]>) -> A {
    let result = Rc::new(Cell::new(None));
    let out = result.clone();
    Async::start(p, move |a| out.set(Some(a)));
    timers.run();
    result.take().expect("the program came to its value")
}

/// `n` waits, each answered at once, and the answers summed
fn answered(n: u32, so_far: u32) -> Comp<u32, eff![Async]> {
    if n == 0 {
        return pure(so_far).boxed();
    }
    Async::wait(|resume: Resume<u32>| resume(1)).flat_map(move |x| answered(n - 1, so_far + x)).boxed()
}

#[test]
fn asynchrony_both_at_once_the_first_of_two_all_a_sleep() {
    let timers = Timers::new();
    let after = |millis: u64, n: i32| timers.sleep::<eff![Async], _>(millis).map(move |_| n);
    assert_eq!(wait_for(&timers, Async::both(after(30, 1), after(10, 2))), (1, 2));
    assert_eq!(timers.now(), 30);
    assert_eq!(wait_for(&timers, Async::race(after(200, 1), after(10, 2))), 2);
    assert_eq!(wait_for(&timers, Async::all(vec![after(20, 1), after(10, 2), after(1, 3)])), vec![1, 2, 3]);
    assert_eq!(wait_for(&timers, after(5, 7).map(|n| n + 1)), 8);
}

#[test]
#[cfg_attr(miri, ignore)]
fn asynchrony_a_million_answers_given_at_once_take_no_stack() {
    assert_eq!(wait_for(&Timers::new(), answered(1_000_000, 0)), 1_000_000);
}

#[test]
fn asynchrony_a_few_thousand_answers_given_at_once_take_no_stack() {
    assert_eq!(wait_for(&Timers::new(), answered(3_000, 0)), 3_000);
}

#[test]
fn no_handler_an_operation_left_in_the_row_is_refused_by_rustc_and_stepped_it_is_asked_at_the_top() {
    // `State::get().run()` does not compile: see the examples of lib.rs, which `cargo test` checks
    let open = State::get::<eff![State<i64>], _>().map(|s: i64| s + 1);
    let Step::Asked(asked) = open.step() else { panic!("the operation has no handler") };
    let Some(State::Get(r)) = asked.op() else { panic!("the operation asked is a get") };
    assert_eq!(State::run(0, asked.resume(*r, 41)).run(), (0, 42));
}

/// binds never run, and frames waiting for a value that never comes, `n` of each
fn chains_dropped(n: u32) {
    let unrun: Comp<i64, Counter> = (0..n).fold(State::get().boxed(), |p, _| p.map(|s: i64| s + 1).boxed());
    drop(unrun);
    fn deep(n: u32) -> Comp<u32, eff![Throws<String>]> {
        if n == 0 {
            return Throws::raise("the bottom".to_string()).boxed();
        }
        defer(move || deep(n - 1)).map(|x| x + 1).boxed()
    }
    assert_eq!(Throws::run(deep(n)).run(), Err("the bottom".to_string()));
}

#[test]
#[cfg_attr(miri, ignore)]
fn no_collector_a_long_chain_of_binds_or_of_frames_is_dropped_in_a_loop() {
    chains_dropped(1_000_000);
}

#[test]
fn no_collector_a_chain_of_a_few_thousand_binds_or_frames_is_dropped_in_a_loop() {
    chains_dropped(3_000);
}

// -- ENSURING

thread_local! {
    /// what was said on the way, in order
    static LOG: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

fn said<R: 'static>(what: impl Into<String>) -> Comp<(), R> {
    let what = what.into();
    defer(move || {
        LOG.with(|log| log.borrow_mut().push(what));
        pure(())
    })
}

/// a result, and what was said on the way
fn logged<A>(result: impl FnOnce() -> A) -> (A, Vec<String>) {
    LOG.take();
    let a = result();
    (a, LOG.take())
}

fn told<const N: usize>(what: [&str; N]) -> Vec<String> {
    what.iter().map(|said| said.to_string()).collect()
}

fn no<A>() -> Result<A, String> {
    Err("no".to_string())
}

type Failing = eff![State<i64>, Throws<String>];

fn tick<R: Has<State<i64>, I>, I>() -> impl Prog<(), R> {
    State::modify(|s: i64| s + 1)
}

#[test]
fn ensuring_a_value_the_finalizer_runs_after_the_program_and_the_programs_value_is_the_result() {
    let p = said::<()>("body").map(|_| 1).ensuring(|| said("closed").map(|_| 2));
    assert_eq!(logged(|| p.run()), (1, told(["body", "closed"])));
    // a finalizer is made when it runs: building the program runs nothing
    LOG.take();
    let built = pure::<_, ()>(1).ensuring(|| {
        LOG.with(|log| log.borrow_mut().push("closed".to_string()));
        pure(())
    });
    assert!(LOG.with(|log| log.borrow().is_empty()));
    assert_eq!(logged(|| built.run()), (1, told(["closed"])));
}

#[test]
fn ensuring_a_failure_the_finalizer_runs_before_the_failure_is_answered() {
    fn p() -> impl Prog<i64, eff![Throws<String>]> {
        said("body").then(|| Throws::raise("no".to_string())).ensuring(|| said("closed"))
    }
    let rescued = Throws::recover(|e: String| said(format!("rescued {e}")).map(|_| 0), p());
    assert_eq!(logged(|| rescued.run()), (0, told(["body", "closed", "rescued no"])));
    assert_eq!(logged(|| Throws::run(p()).run()), (no(), told(["body", "closed"])));
}

#[test]
fn ensuring_a_handlers_abort_any_handler_made_by_handler_abort_unwinds_what_it_leaves() {
    let p = deposit::<eff![Account], _>(1).ensuring(|| said("closed"));
    let refused = Handler::abort(pure, |_: Account| pure(-1)).handle(p);
    assert_eq!(logged(|| refused.run()), (-1, told(["closed"])));
}

#[test]
fn ensuring_the_first_of_the_choices_a_path_with_no_value_is_unwound_a_path_not_begun_holds_nothing() {
    fn path(x: i64) -> Comp<i64, eff![Choose]> {
        said(format!("open {x}"))
            .then(move || if x == 1 { Choose::none().boxed() } else { pure(x).boxed() })
            .ensuring(move || said(format!("closed {x}")))
            .boxed()
    }
    let p = Choose::among(vec![1, 2, 3]).flat_map(path);
    assert_eq!(logged(|| Choose::first(p).run()), (Some(2), told(["open 1", "closed 1", "open 2", "closed 2"])));
}

#[test]
fn ensuring_a_stream_cut_short_the_producer_is_unwound_where_it_stopped() {
    let cut = Emit::take(2, from(0).ensuring(|| said("closed")));
    assert_eq!(logged(|| Emit::collect(cut).run()), ((vec![0, 1], ()), told(["closed"])));
    // what a finalizer sends when the stream is cut goes to the stream's handler: the cut has left
    let last = Emit::take(2, from(0).ensuring(|| Emit::send(-1)));
    assert_eq!(Emit::collect(last).run().0, vec![0, 1, -1]);
    let pulled = Emit::pull(Emit::take(2, from(0).ensuring(|| Emit::send(-1))));
    assert_eq!(pulled.collect::<Vec<_>>(), vec![0, 1, -1]);
}

#[test]
fn ensuring_every_path_of_a_choice_a_finalizer_after_the_choice_runs_once_in_each_path() {
    fn path(x: i64) -> Comp<i64, eff![Choose]> {
        let ended = if x == 2 { Choose::none().boxed() } else { pure(x * 10).boxed() };
        ended.ensuring(move || said(format!("closed {x}"))).boxed()
    }
    let p = Choose::among(vec![1, 2, 3]).flat_map(path);
    assert_eq!(logged(|| Choose::all(p).run()), (vec![10, 30], told(["closed 1", "closed 2", "closed 3"])));
}

#[test]
fn ensuring_every_path_of_a_choice_a_finalizer_before_the_choice_runs_once_for_each_path_that_passes_it() {
    let chosen =
        Choose::among::<eff![Choose], _, _>(vec![1, 2, 3]).flat_map(|x| said(format!("path {x}")).map(move |_| x));
    let p = chosen
        .ensuring(|| said("closed"))
        .flat_map(|x| if x == 2 { Choose::none().boxed() } else { pure(x).boxed() });
    let all = told(["path 1", "closed", "path 2", "closed", "path 3", "closed"]);
    assert_eq!(logged(|| Choose::all(p).run()), (vec![1, 3], all));
}

#[test]
fn ensuring_nested_the_innermost_finalizer_first_at_a_value_and_at_a_failure() {
    let ended = pure::<i64, eff![Throws<String>]>(1).ensuring(|| said("inner")).ensuring(|| said("outer"));
    assert_eq!(logged(|| Throws::run(ended).run()), (Ok(1), told(["inner", "outer"])));
    let failed = Throws::raise::<eff![Throws<String>], _, i64>("no".to_string())
        .ensuring(|| said("inner"))
        .then(|| said("not run").map(|_| 2))
        .ensuring(|| said("outer"));
    assert_eq!(logged(|| Throws::run(failed).run()), (no(), told(["inner", "outer"])));
}

#[test]
fn ensuring_state_a_finalizer_reads_and_writes_the_state_as_its_path_left_it_inside_the_handler_or_outside() {
    fn saw<R: Has<State<i64>, I>, I: 'static>() -> impl Prog<(), R> {
        State::get().flat_map(|s: i64| said(format!("saw {s}")).then(move || State::put(s + 1)))
    }
    fn p() -> impl Prog<i64, Failing> {
        State::put(5).then(|| Throws::raise("no".to_string())).ensuring(|| saw())
    }
    // the state inside the handler that leaves: it is in what is unwound, and is dropped after the finalizer
    assert_eq!(logged(|| Throws::run(State::run(0, p())).run()), (no(), told(["saw 5"])));
    // the state outside it: what the finalizer wrote stays
    assert_eq!(logged(|| State::run(0, Throws::run(p())).run()), ((6, no()), told(["saw 5"])));
    // each path of a choice has the state it left
    type Paths = eff![Choose, State<i64>];
    fn ended(x: i64) -> impl Prog<i64, Paths> {
        State::put(x).then(|| Choose::none())
    }
    let seen = || State::get().flat_map(|s: i64| said(format!("saw {s}")));
    let q = Choose::among(vec![1, 2]).flat_map(move |x| ended(x).ensuring(seen));
    assert_eq!(logged(|| Choose::all(State::run(0, q)).run()), (vec![], told(["saw 1", "saw 2"])));
}

#[test]
fn ensuring_a_finalizer_that_fails_at_a_value_the_failure_is_the_result_and_what_waits_outside_is_unwound() {
    let p = pure::<i64, eff![Throws<String>]>(1)
        .ensuring(|| Throws::raise::<_, _, ()>("closing".to_string()))
        .ensuring(|| said("outer"));
    assert_eq!(logged(|| Throws::run(p).run()), (Err("closing".to_string()), told(["outer"])));
}

#[test]
fn ensuring_a_finalizer_that_fails_in_an_unwinding_the_unwinding_goes_on_the_failure_goes_to_its_handler() {
    type Two = eff![Throws<i64>, Throws<String>];
    fn p() -> impl Prog<i64, Two> {
        let closing = || Throws::raise::<Two, _, ()>(7).then(|| said("not run"));
        Throws::raise("no".to_string()).ensuring(closing).ensuring(|| said("outer"))
    }
    // its handler is outside the one that leaves: it unwinds the rest, and its answer is the result
    let outside = Throws::<i64>::run(Throws::<String>::run(p()));
    assert_eq!(logged(|| outside.run()), (Err(7), told(["outer"])));
    // its handler is inside what is unwound: the failure is answered there, and the unwinding goes on from there
    let rescued = Throws::<i64>::recover(|n| said(format!("rescued {n}")).map(|_| 0), p());
    let inside = Throws::<String>::run(rescued.then(|| said("not run")));
    assert_eq!(logged(|| inside.run()), (no(), told(["outer", "rescued 7"])));
    // its handler is the one that leaves: nobody answers, the finalizer ends there, the first failure stands
    let again = || Throws::raise::<eff![Throws<String>], _, ()>("closing".to_string()).then(|| said("not run"));
    let own = Throws::raise::<_, _, i64>("no".to_string()).ensuring(again).ensuring(|| said("outer"));
    assert_eq!(logged(|| Throws::run(own).run()), (no(), told(["outer"])));
}

#[test]
fn ensuring_k_is_a_value_unwound_twice_the_finalizers_run_twice_resumed_after_its_path_ends_once_more() {
    let p = deposit::<eff![Account], _>(1)
        .flat_map(|n| said(format!("got {n}")).map(move |_| n))
        .ensuring(|| said("closed"));
    let handler = Handler::new(pure, |op, k: K<i64, ()>| match op {
        Account::Deposit(n, r) => {
            let (again, path) = (k.clone(), k.clone());
            k.unwind().then(move || again.unwind()).then(move || path.resume(r, n + 1))
        }
    });
    assert_eq!(logged(|| handler.handle(p).run()), (2, told(["closed", "closed", "got 2", "closed"])));
}

#[test]
fn ensuring_the_rest_of_an_unwinding_kept_by_a_handler_inside_it_resumed_where_the_unwinding_is_not_refused() {
    type Kept = eff![Throws<String>, Account];
    let p = Throws::raise::<Kept, _, i64>("no".to_string()).ensuring(|| deposit(1).then(|| said("closed")));
    let kept: Rc<RefCell<Option<Comp<i64, eff![Throws<String>]>>>> = Rc::default();
    let keep = kept.clone();
    let keeper = Handler::new(pure, move |op, k: K<i64, eff![Throws<String>]>| match op {
        Account::Deposit(n, r) => {
            keep.replace(Some(k.resume(r, n).boxed()));
            pure(0)
        }
    });
    // the finalizer waits at its operation; the unwinding goes on without it
    let left = keeper.handle(p);
    assert_eq!(logged(|| Throws::run(left).run()), (no(), told([])));
    // resumed, the finalizer ends — and there is no unwinding to go on with, and no value: the run panics
    let rest = kept.take().expect("the rest of the finalizer was kept");
    let refused = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| Throws::run(rest).run()));
    let why = refused.expect_err("the run is refused");
    let said = why.downcast_ref::<&str>().copied();
    assert_eq!(said, Some("the rest of an unwinding was resumed outside the unwinding"));
    assert_eq!(LOG.take(), told(["closed"]));
}

#[test]
fn ensuring_k_neither_resumed_nor_unwound_nothing_of_it_runs() {
    let p = deposit::<eff![Account], _>(1).ensuring(|| said("closed"));
    // the clause lets `k` go: dropped, as Rust drops a value — no finalizer runs
    let kept = Handler::new(pure, |_: Account, _: K<i64, ()>| pure(0));
    assert_eq!(logged(|| kept.handle(p).run()), (0, told([])));
    // a stream pulled and not read to its end is let go so too
    let mut pulled = Emit::pull(from(0).ensuring(|| said("closed")));
    assert_eq!(logged(|| (pulled.next(), drop(pulled)).0), (Some(0), told([])));
}

#[test]
fn ensuring_asynchrony_a_program_that_waits_is_kept_and_its_finalizer_runs_when_it_ends() {
    let waiting: Rc<RefCell<Vec<Box<dyn FnOnce()>>>> = Rc::default();
    let queue = waiting.clone();
    let p = Async::wait(move |resume: Resume<i64>| queue.borrow_mut().push(Box::new(move || resume(1))))
        .map(|x| x + 1)
        .ensuring(|| said("closed"));
    let result = Rc::new(Cell::new(None));
    let out = result.clone();
    assert_eq!(logged(|| Async::start(p, move |a| out.set(Some(a)))), ((), told([])));
    assert_eq!(logged(|| waiting.take().into_iter().for_each(|wake| wake())), ((), told(["closed"])));
    assert_eq!(result.get(), Some(2));
}

#[test]
fn bracket_acquired_used_released_when_the_use_ends_and_when_it_fails() {
    fn using(fails: bool) -> impl Prog<usize, eff![Throws<String>]> {
        let used = move |f: &'static str| {
            let end = move || if fails { Throws::raise("no".to_string()).boxed() } else { pure(f.len()).boxed() };
            said(format!("use {f}")).then(end)
        };
        bracket(said("open").map(|_| "file"), used, |f| said(format!("close {f}")))
    }
    let all = told(["open", "use file", "close file"]);
    assert_eq!(logged(|| Throws::run(using(false)).run()), (Ok(4), all.clone()));
    assert_eq!(logged(|| Throws::run(using(true)).run()), (no(), all));
}

/// a loop of `n` turns, a finalizer in each
fn finalized(n: i64) -> Comp<i64, Counter> {
    if n == 0 {
        return State::get().boxed();
    }
    tick().ensuring(|| tick()).then(move || finalized(n - 1)).boxed()
}

/// a failure under `n` finalizers that wait
fn waiting(n: i64) -> Comp<i64, Failing> {
    (0..n).fold(Throws::raise("no".to_string()).boxed(), |p, _| p.ensuring(|| tick()).boxed())
}

#[test]
#[cfg_attr(miri, ignore)]
fn ensuring_constant_stack_a_million_finalizers_in_a_loop() {
    assert_eq!(State::run(0, finalized(1_000_000)).run(), (2_000_000, 2_000_000));
}

#[test]
#[cfg_attr(miri, ignore)]
fn ensuring_constant_stack_a_continuation_with_a_hundred_thousand_waiting_finalizers_is_unwound() {
    assert_eq!(State::run(0, Throws::run(waiting(100_000))).run(), (100_000, no()));
}

/// finalizers never run, `n` of them: in a program not run, and waiting in a continuation that is let go
fn finalizers_dropped(n: i64) {
    drop(waiting(n));
    let lets_go = Handler::new(|_| pure(()), |_: Throws<String>, _: K<(), eff![State<i64>]>| pure(()));
    assert_eq!(State::run(0, lets_go.handle(waiting(n))).run(), (0, ()));
}

#[test]
#[cfg_attr(miri, ignore)]
fn ensuring_no_collector_a_long_chain_of_finalizers_never_run_is_dropped_in_a_loop() {
    finalizers_dropped(1_000_000);
}

#[test]
fn ensuring_a_few_thousand_finalizers_in_a_loop_waiting_and_unwound_or_never_run_and_dropped() {
    assert_eq!(State::run(0, finalized(2_000)).run(), (4_000, 4_000));
    assert_eq!(State::run(0, Throws::run(waiting(2_000))).run(), (2_000, no()));
    finalizers_dropped(2_000);
}

#[test]
fn ensuring_an_interceptor_between_the_finalizer_and_the_handler_that_leaves_sees_what_the_finalizer_asks() {
    let seen: Rc<RefCell<Vec<String>>> = Rc::default();
    let log = seen.clone();
    let audit = Handler::new(pure, move |op: State<i64>, k: K<i64, Failing>| {
        log.borrow_mut().push(if let State::Put(s, _) = &op { format!("Put({s})") } else { "Get".to_string() });
        k.pass(op)
    });
    let p = State::put(1).then(|| Throws::raise("no".to_string())).ensuring(|| State::put(9));
    assert_eq!(State::run(0, Throws::run(audit.intercept(p))).run(), (9, no()));
    assert_eq!(*seen.borrow(), told(["Put(1)", "Put(9)"]));
}

#[test]
fn ensuring_a_program_of_a_smaller_row_put_into_a_bigger_one_its_finalizer_runs_there() {
    type Small = eff![Throws<String>, State<i64>];
    type Big = eff![State<i64>, Writer<String>, Throws<String>];
    fn small() -> impl Prog<(), Small> {
        State::put(1).then(|| Throws::raise("no".to_string())).ensuring(|| State::put(2))
    }
    fn p() -> impl Prog<(), Big> {
        Writer::tell("before".to_string()).then(|| small().embed()).then(|| Writer::tell("after".to_string()))
    }
    assert_eq!(State::run(0, Throws::run(Writer::run(p()))).run(), (2, no()));
    assert_eq!(State::run(0, Writer::run(Throws::run(p()))).run(), (2, (vec!["before".to_string()], no())));
}

#[test]
fn ensuring_an_operation_no_handler_answers_whoever_steps_the_program_resumes_its_rest_or_unwinds_it() {
    let p = deposit::<eff![Account], _>(1).ensuring(|| said("closed"));
    let Step::Asked(asked) = p.step() else { panic!("an operation was expected") };
    let (unwound, said) = logged(|| asked.unwind().step());
    assert!(matches!(unwound, Step::Done(())));
    assert_eq!(said, told(["closed"]));
    let Some(Account::Deposit(_, r)) = asked.op() else { panic!("a deposit was asked") };
    let (resumed, said) = logged(|| asked.resume(*r, 2).step());
    assert!(matches!(resumed, Step::Done(2)));
    assert_eq!(said, told(["closed"]));
}

#[test]
fn ensuring_a_continuation_taken_in_a_finalizer_and_resumed_twice_the_value_or_the_unwinding_goes_on_twice() {
    type Paths = eff![Throws<String>, Choose];
    let closing = || Choose::among(vec![1, 2]).flat_map(|x| said(format!("closing {x}")));
    // at a value: it waits while the finalizer runs, and is given in each path
    let ended = pure::<i64, Paths>(5).ensuring(closing).ensuring(|| said("outer"));
    let all = told(["closing 1", "outer", "closing 2", "outer"]);
    assert_eq!(logged(|| Choose::all(Throws::run(ended)).run()), (vec![Ok(5), Ok(5)], all.clone()));
    // in an unwinding: it goes on in each path, to the failure that began it
    let failed = Throws::raise::<Paths, _, i64>("no".to_string()).ensuring(closing).ensuring(|| said("outer"));
    assert_eq!(logged(|| Choose::all(Throws::run(failed)).run()), (vec![no(), no()], all));
}

#[test]
fn ensuring_an_interceptor_that_unwinds_what_a_finalizer_asks_of_its_own_effect_reaches_the_handler_outside() {
    let seen: Rc<RefCell<Vec<String>>> = Rc::default();
    let log = seen.clone();
    // an interceptor of the state that leaves at a put: the rest is unwound, then the state outside is read
    let leaving = Handler::new(pure, move |op: State<i64>, k: K<i64, Counter>| match op {
        State::Put(s, _) => {
            log.borrow_mut().push(format!("Put({s})"));
            k.unwind().then(|| State::get()).map(|s: i64| s + 100).boxed()
        }
        op => {
            log.borrow_mut().push("Get".to_string());
            k.pass(op).boxed()
        }
    });
    let body = State::put::<Counter, _>(1).then(|| said("not run")).map(|_| 0);
    let p = body.ensuring(|| State::get().flat_map(|s: i64| State::put(s + 9)));
    // the finalizer read the 5 outside and wrote 14 there: the put of 1 never passed the interceptor
    assert_eq!(logged(|| State::run(5, leaving.intercept(p)).run()), ((14, 114), told([])));
    // the interceptor has left: it saw the put it left at, and nothing of the finalizer
    assert_eq!(*seen.borrow(), told(["Put(1)"]));
}

#[test]
fn ensuring_a_finalizer_stopped_at_an_operation_of_the_handler_that_left_its_own_finalizer_still_runs() {
    type Fails = eff![Throws<String>];
    // the finalizer fails as the program did; that handler has left, under `handle`: nobody answers
    let closing = || {
        let raised = || Throws::raise::<Fails, _, ()>("again".to_string());
        said("closing").then(raised).then(|| said("not run")).ensuring(|| said("inner"))
    };
    let failing = Throws::raise::<Fails, _, i64>("no".to_string());
    let p = failing.ensuring(move || closing().then(|| said("not run"))).ensuring(|| said("outer"));
    // it ends at its operation, through its own finalizer; the unwinding goes on, and the first failure stands
    assert_eq!(logged(|| Throws::run(p).run()), (no(), told(["closing", "inner", "outer"])));
    // THE SAME EFFECT AT ANOTHER PLACE OF THE ROW, its handler outside the one that left. In Scala and Java that
    // failure is answered there, and is the result. Not here: Rust finds a handler by the effect's type, the
    // nearest first, and the two places are one type — the handler that left stops both. The finalizer ends,
    // and the first failure stands, a value of the handler outside.
    type Two = eff![Throws<String>, Throws<String>];
    let other = || Throws::raise::<Two, There<Here>, ()>("other".to_string()).then(|| said("not run"));
    let q = Throws::raise::<Two, Here, i64>("no".to_string()).ensuring(other).ensuring(|| said("outer"));
    let both = Throws::run::<eff![Throws<String>], Here, _>(Throws::run::<Two, Here, _>(q));
    assert_eq!(logged(|| both.run()), (Ok(no()), told(["outer"])));
}

#[test]
fn ensuring_k_unwound_twice_then_resumed_a_handler_in_place_inside_it_each_from_the_state_k_took() {
    type Kept = eff![State<i64>, Account];
    fn saw() -> impl Prog<(), Kept> {
        State::get().flat_map(|s: i64| said(format!("saw {s}")).then(move || State::put(s + 1)))
    }
    let asked = || deposit(1).flat_map(|n| State::get().map(move |s: i64| s + n));
    let p = State::run(0, State::put::<Kept, _>(5).then(asked).ensuring(saw));
    let handler = Handler::new(pure, |op, k: K<(i64, i64), ()>| match op {
        Account::Deposit(n, r) => {
            let (again, path) = (k.clone(), k.clone());
            k.unwind().then(move || again.unwind()).then(move || path.resume(r, n + 1))
        }
    });
    // each finalizer saw the 5 that `k` holds, and wrote its 6 in a state of its own path; the last path's is kept
    assert_eq!(logged(|| handler.handle(p).run()), ((6, 7), told(["saw 5", "saw 5", "saw 5"])));
}

#[test]
fn ensuring_the_rest_of_a_stepped_program_unwound_twice_then_resumed_its_finalizers_run_each_time() {
    let asked = deposit::<eff![Account], _>(1).flat_map(|n| said(format!("got {n}")).map(move |_| n));
    let Step::Asked(asked) = asked.ensuring(|| said("closed")).step() else { panic!("an operation was expected") };
    for _ in 0..2 {
        let (unwound, said) = logged(|| asked.unwind().step());
        assert!(matches!(unwound, Step::Done(())));
        assert_eq!(said, told(["closed"]));
    }
    let Some(Account::Deposit(n, r)) = asked.op() else { panic!("a deposit was asked") };
    let (resumed, said) = logged(|| asked.resume(*r, n + 1).step());
    assert!(matches!(resumed, Step::Done(2)));
    assert_eq!(said, told(["got 2", "closed"]));
}
