//! The standard effects: each an enum of operations (data) and a handler or two (functions on programs).

use std::rc::Rc;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::{Comp, Handler, Has, K, Never, Program, Reply, perform, pure};

mod emit;
mod later;

pub use emit::{Emit, Pull};
pub use later::{Async, Resume, Timers};

/// STATE.
pub enum State<S> {
    Get(Reply<S>),
    Put(S, Reply<()>),
}

impl<S: Clone + 'static> State<S> {
    pub fn get<R: Has<Self, I>, I>() -> impl Program<S, R> {
        perform(State::Get)
    }

    pub fn put<R: Has<Self, I>, I>(s: S) -> impl Program<(), R> {
        perform(|r| State::Put(s, r))
    }

    pub fn modify<R: Has<Self, I>, I>(f: impl FnOnce(S) -> S + Clone + 'static) -> impl Program<(), R> {
        Self::get().flat_map(move |s| Self::put(f(s)))
    }

    /// The state kept by the handler, answered in place; each path of the program has its own.
    pub fn run<R: Has<Self, I>, I, A: 'static>(s0: S, p: impl Program<A, R>) -> impl Program<(S, A), R::Rest> {
        let answer = |state: &mut S, op| match op {
            State::Get(r) => r.give(state.clone()),
            State::Put(s, r) => {
                *state = s;
                r.give(())
            }
        };
        Handler::in_place(s0, |s, a| pure((s, a)), answer).handle(p)
    }
}

/// READER: a value of the surroundings.
pub enum Reader<V> {
    Ask(Reply<V>),
}

impl<V: Clone + 'static> Reader<V> {
    pub fn ask<R: Has<Self, I>, I>() -> impl Program<V, R> {
        perform(Reader::Ask)
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(value: V, p: impl Program<A, R>) -> impl Program<A, R::Rest> {
        let answer = move |_: &mut (), op| match op {
            Reader::Ask(r) => r.give(value.clone()),
        };
        Handler::in_place((), |_, a| pure(a), answer).handle(p)
    }

    /// The program, its asks answered by the reader outside, through `f`: an interceptor.
    pub fn local<R: Has<Self, I>, I, A: 'static>(
        f: impl Fn(V) -> V + 'static,
        p: impl Program<A, R>,
    ) -> impl Program<A, R> {
        let f = Rc::new(f);
        let on = move |op, k: K<A, R>| match op {
            Reader::Ask(r) => {
                let f = f.clone();
                Self::ask().flat_map(move |v| k.resume(r, f(v)))
            }
        };
        Handler::new(pure, on).intercept(p)
    }
}

/// WRITER: values told, collected in order.
pub enum Writer<W> {
    Tell(W, Reply<()>),
}

impl<W: Clone + 'static> Writer<W> {
    pub fn tell<R: Has<Self, I>, I>(w: W) -> impl Program<(), R> {
        perform(|r| Writer::Tell(w, r))
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(p: impl Program<A, R>) -> impl Program<(Vec<W>, A), R::Rest> {
        let answer = |told: &mut Vec<W>, op| match op {
            Writer::Tell(w, r) => {
                told.push(w);
                r.give(())
            }
        };
        Handler::in_place(Vec::new(), |told, a| pure((told, a)), answer).handle(p)
    }
}

/// THROWS: the rest of the program is dropped.
pub enum Throws<E> {
    Raise(E, Reply<Never>),
}

impl<E: 'static> Throws<E> {
    pub fn raise<R: Has<Self, I>, I, A: 'static>(error: E) -> impl Program<A, R> {
        perform(|r| Throws::Raise(error, r)).map(|never: Never| match never {})
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(p: impl Program<A, R>) -> impl Program<Result<A, E>, R::Rest> {
        let on = |op, _| match op {
            Throws::Raise(error, _) => pure(Err(error)),
        };
        Handler::new(|a| pure(Ok(a)), on).handle(p)
    }

    /// The program, or what `rescue` makes of its failure.
    pub fn recover<R: Has<Self, I>, I, A: 'static, P: Program<A, R::Rest>>(
        rescue: impl Fn(E) -> P + 'static,
        p: impl Program<A, R>,
    ) -> impl Program<A, R::Rest> {
        let on = move |op, _| match op {
            Throws::Raise(error, _) => rescue(error),
        };
        Handler::new(pure, on).handle(p)
    }
}

/// CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times.
pub enum Choose {
    /// one of so many: which
    Among(usize, Reply<usize>),
}

impl Choose {
    pub fn among<R: Has<Self, I>, I, X: Clone + 'static>(xs: Vec<X>) -> impl Program<X, R> {
        // shared: what follows an operation is copied each time its continuation is resumed again
        let xs = Rc::new(xs);
        perform(|r| Choose::Among(xs.len(), r)).map(move |i: usize| xs[i].clone())
    }

    /// No value: this path ends.
    pub fn none<R: Has<Self, I>, I, X: 'static>() -> impl Program<X, R> {
        perform(|r| Choose::Among(0, r)).map(|_: usize| unreachable!("one of none"))
    }

    /// Every value the program comes to.
    pub fn all<R: Has<Self, I>, I, A: Clone + 'static>(p: impl Program<A, R>) -> impl Program<Vec<A>, R::Rest> {
        // the values of the paths from the `i`-th of `n` on, after those `found` before
        fn from<T: 'static, A: Clone + 'static>(
            k: K<Vec<A>, T>,
            r: Reply<usize>,
            (i, n): (usize, usize),
            found: Vec<A>,
        ) -> Comp<Vec<A>, T> {
            if i == n {
                return pure(found).boxed();
            }
            let path = k.resume(r, i);
            path.flat_map(move |more| from(k, r, (i + 1, n), joined(found, more))).boxed()
        }
        fn joined<A>(mut found: Vec<A>, more: Vec<A>) -> Vec<A> {
            if found.is_empty() {
                return more;
            }
            found.extend(more);
            found
        }
        let on = |op, k| match op {
            Choose::Among(n, r) => from(k, r, (0, n), Vec::new()),
        };
        Handler::new(|a| pure(vec![a]), on).handle(p)
    }

    /// The first value the program comes to, the other paths not taken.
    pub fn first<R: Has<Self, I>, I, A: 'static>(p: impl Program<A, R>) -> impl Program<Option<A>, R::Rest> {
        // the first value of the paths from the `i`-th of `n` on
        fn from<T: 'static, A: 'static>(
            k: K<Option<A>, T>,
            r: Reply<usize>,
            (i, n): (usize, usize),
        ) -> Comp<Option<A>, T> {
            if i == n {
                return pure(None).boxed();
            }
            let path = k.resume(r, i);
            path.flat_map(move |found| match found {
                Some(a) => pure(Some(a)).boxed(),
                None => from(k, r, (i + 1, n)),
            })
            .boxed()
        }
        let on = |op, k| match op {
            Choose::Among(n, r) => from(k, r, (0, n)),
        };
        Handler::new(|a| pure(Some(a)), on).handle(p)
    }
}

/// FRESH: a number never given before, in this run.
pub enum Fresh {
    Next(Reply<i64>),
}

impl Fresh {
    pub fn fresh<R: Has<Self, I>, I>() -> impl Program<i64, R> {
        perform(Fresh::Next)
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(p: impl Program<A, R>) -> impl Program<A, R::Rest> {
        let answer = |next: &mut i64, op| match op {
            Fresh::Next(r) => {
                *next += 1;
                r.give(*next - 1)
            }
        };
        Handler::in_place(0, |_, a| pure(a), answer).handle(p)
    }
}

/// CLOCK: the time, in milliseconds — the system's, or one a test sets.
pub enum Clock {
    Now(Reply<i64>),
}

impl Clock {
    pub fn now<R: Has<Self, I>, I>() -> impl Program<i64, R> {
        perform(Clock::Now)
    }

    pub fn system<R: Has<Self, I>, I, A: 'static>(p: impl Program<A, R>) -> impl Program<A, R::Rest> {
        let millis = || SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_millis() as i64);
        Self::at(millis, p)
    }

    pub fn at<R: Has<Self, I>, I, A: 'static>(
        time: impl Fn() -> i64 + 'static,
        p: impl Program<A, R>,
    ) -> impl Program<A, R::Rest> {
        let answer = move |_: &mut (), op| match op {
            Clock::Now(r) => r.give(time()),
        };
        Handler::in_place((), |_, a| pure(a), answer).handle(p)
    }
}
