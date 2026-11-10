//! ASYNC: an answer that comes later. An operation gives a callback to whoever will have the answer; the handler
//! keeps the rest of the program and returns. The callback, whenever it is called, resumes the rest. Nothing waits:
//! asynchrony is callbacks and continuations, nothing else. The kernel is of one thread, and so is this.

use std::any::Any;
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;

use crate::{Comp, Handler, Has, K, Reply, perform, pure, row};

/// The callback of an awaited answer.
pub type Resume<X> = Rc<dyn Fn(X)>;

pub enum Async {
    /// the answer travels without its type; `wait` gives it back
    Await(Box<dyn FnOnce(Resume<Box<dyn Any>>)>, Reply<Box<dyn Any>>),
}

impl Async {
    /// The answer `register` will be called back with.
    pub fn wait<X: 'static, R: Has<Self, I>, I>(register: impl FnOnce(Resume<X>) + 'static) -> Comp<R, X> {
        let erased = move |resume: Resume<Box<dyn Any>>| register(Rc::new(move |x| resume(Box::new(x))));
        perform(|r| Async::Await(Box::new(erased), r))
            .map(|x: Box<dyn Any>| *x.downcast().expect("the answer of the type that was awaited"))
    }

    /// The program started; `done` is called with its value, now or later. Returns as soon as the program waits.
    pub fn start<A: 'static>(p: Comp<row![Async], A>, done: impl Fn(A) + 'static) {
        let turns = Rc::new(Turns::default());
        let next = turns.clone();
        let ret = move |a| {
            done(a);
            pure(())
        };
        let on = move |op, k: K<(), ()>| match op {
            Async::Await(register, r) => {
                let turns = next.clone();
                register(Rc::new(move |x| {
                    let k = k.clone();
                    turns.take(Box::new(move || k.resume(r, x).run()))
                }));
                pure(())
            }
        };
        turns.take(Box::new(move || Handler::new(ret, on).handle(p).run()));
    }

    /// Both programs at once; their values when both have come.
    pub fn both<A: 'static, B: 'static, R: Has<Self, I>, I>(
        a: Comp<row![Async], A>,
        b: Comp<row![Async], B>,
    ) -> Comp<R, (A, B)> {
        Async::wait(move |resume: Resume<(A, B)>| {
            let values: Rc<(RefCell<Option<A>>, RefCell<Option<B>>)> = Rc::default();
            let give = move |values: &(RefCell<Option<A>>, RefCell<Option<B>>)| {
                if values.0.borrow().is_some() && values.1.borrow().is_some() {
                    resume((values.0.take().unwrap(), values.1.take().unwrap()));
                }
            };
            let (left, right, again) = (values.clone(), values, give.clone());
            Async::start(a, move |x| {
                left.0.replace(Some(x));
                give(&left)
            });
            Async::start(b, move |y| {
                right.1.replace(Some(y));
                again(&right)
            });
        })
    }

    /// All the programs at once; their values, in the programs' order.
    pub fn all<A: 'static, R: Has<Self, I>, I>(ps: Vec<Comp<row![Async], A>>) -> Comp<R, Vec<A>> {
        Async::wait(move |resume: Resume<Vec<A>>| {
            let values: Rc<RefCell<Vec<Option<A>>>> = Rc::new(RefCell::new(ps.iter().map(|_| None).collect()));
            let give = move |values: &RefCell<Vec<Option<A>>>| {
                if values.borrow().iter().all(Option::is_some) {
                    resume(values.take().into_iter().flatten().collect());
                }
            };
            give(&values);
            for (i, p) in ps.into_iter().enumerate() {
                let (values, give) = (values.clone(), give.clone());
                Async::start(p, move |x| {
                    values.borrow_mut()[i] = Some(x);
                    give(&values)
                });
            }
        })
    }

    /// Both programs at once; the value that comes first — the other's is dropped.
    pub fn race<A: 'static, R: Has<Self, I>, I>(a: Comp<row![Async], A>, b: Comp<row![Async], A>) -> Comp<R, A> {
        Async::wait(move |resume: Resume<A>| {
            let won = Rc::new(Cell::new(false));
            let first = move |x| {
                if !won.replace(true) {
                    resume(x)
                }
            };
            Async::start(a, first.clone());
            Async::start(b, first);
        })
    }
}

/// The turns of one started program, one at a time and in a loop: a callback called at once, or a million times
/// in a row, does not nest.
#[derive(Default)]
struct Turns {
    ready: RefCell<VecDeque<Box<dyn FnOnce()>>>,
    running: Cell<bool>,
}

impl Turns {
    fn take(&self, turn: Box<dyn FnOnce()>) {
        self.ready.borrow_mut().push_back(turn);
        if !self.running.replace(true) {
            loop {
                let Some(next) = self.ready.borrow_mut().pop_front() else { break };
                next();
            }
            self.running.set(false);
        }
    }
}

/// TIME, as a test or a loop of events drives it: sleepers wait here, and `run` wakes them in the order of their
/// times. No thread, no real waiting.
#[derive(Clone, Default)]
pub struct Timers(Rc<RefCell<Waiting>>);

#[derive(Default)]
struct Waiting {
    now: u64,
    sleepers: Vec<(u64, Box<dyn FnOnce()>)>,
}

impl Timers {
    pub fn new() -> Timers {
        Timers::default()
    }

    /// The time, in milliseconds since this was made.
    pub fn now(&self) -> u64 {
        self.0.borrow().now
    }

    pub fn sleep<R: Has<Async, I>, I>(&self, millis: u64) -> Comp<R, ()> {
        let timers = self.clone();
        Async::wait(move |resume: Resume<()>| {
            let mut waiting = timers.0.borrow_mut();
            let at = waiting.now + millis;
            waiting.sleepers.push((at, Box::new(move || resume(()))));
        })
    }

    /// Time moved on until nobody waits: every sleeper woken at its time, the earliest first.
    pub fn run(&self) {
        loop {
            let wake = {
                let mut waiting = self.0.borrow_mut();
                let Some(earliest) = (0..waiting.sleepers.len()).min_by_key(|&i| waiting.sleepers[i].0) else { break };
                let (at, wake) = waiting.sleepers.remove(earliest);
                waiting.now = at;
                wake
            };
            wake();
        }
    }
}
