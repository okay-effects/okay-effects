//! The standard effects: each an enum of operations (data) and a handler or two (functions on programs).

use std::rc::Rc;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::{Comp, Handler, Has, K, Never, Reply, perform, pure};

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
    pub fn get<R: Has<Self, I>, I>() -> Comp<R, S> {
        perform(State::Get)
    }

    pub fn put<R: Has<Self, I>, I>(s: S) -> Comp<R, ()> {
        perform(|r| State::Put(s, r))
    }

    pub fn modify<R: Has<Self, I>, I>(f: impl Fn(S) -> S + 'static) -> Comp<R, ()> {
        Self::get().flat_map(move |s| Self::put(f(s)))
    }

    /// The state kept by the handler, answered in place; each path of the program has its own.
    pub fn run<R: Has<Self, I>, I, A: 'static>(s0: S, p: Comp<R, A>) -> Comp<R::Rest, (S, A)> {
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
    pub fn ask<R: Has<Self, I>, I>() -> Comp<R, V> {
        perform(Reader::Ask)
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(value: V, p: Comp<R, A>) -> Comp<R::Rest, A> {
        let answer = move |_: &mut (), op| match op {
            Reader::Ask(r) => r.give(value.clone()),
        };
        Handler::in_place((), |_, a| pure(a), answer).handle(p)
    }

    /// The program, its asks answered by the reader outside, through `f`: an interceptor.
    pub fn local<R: Has<Self, I>, I, A: 'static>(f: impl Fn(V) -> V + 'static, p: Comp<R, A>) -> Comp<R, A> {
        let f = Rc::new(f);
        let on = move |op, k: K<R, A>| match op {
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
    pub fn tell<R: Has<Self, I>, I>(w: W) -> Comp<R, ()> {
        perform(|r| Writer::Tell(w, r))
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(p: Comp<R, A>) -> Comp<R::Rest, (Vec<W>, A)> {
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
    pub fn raise<R: Has<Self, I>, I, A: 'static>(error: E) -> Comp<R, A> {
        perform(|r| Throws::Raise(error, r)).map(|never: Never| match never {})
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(p: Comp<R, A>) -> Comp<R::Rest, Result<A, E>> {
        let on = |op, _| match op {
            Throws::Raise(error, _) => pure(Err(error)),
        };
        Handler::new(|a| pure(Ok(a)), on).handle(p)
    }

    /// The program, or what `rescue` makes of its failure.
    pub fn recover<R: Has<Self, I>, I, A: 'static>(
        rescue: impl Fn(E) -> Comp<R::Rest, A> + 'static,
        p: Comp<R, A>,
    ) -> Comp<R::Rest, A> {
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
    pub fn among<R: Has<Self, I>, I, X: Clone + 'static>(xs: Vec<X>) -> Comp<R, X> {
        perform(|r| Choose::Among(xs.len(), r)).map(move |i: usize| xs[i].clone())
    }

    /// No value: this path ends.
    pub fn none<R: Has<Self, I>, I, X: 'static>() -> Comp<R, X> {
        perform(|r| Choose::Among(0, r)).map(|_: usize| unreachable!("one of none"))
    }

    /// Every value the program comes to.
    pub fn all<R: Has<Self, I>, I, A: Clone + 'static>(p: Comp<R, A>) -> Comp<R::Rest, Vec<A>> {
        // the values of the paths from the `i`-th of `n` on, after those `found` before
        fn from<T: 'static, A: Clone + 'static>(
            k: K<T, Vec<A>>,
            r: Reply<usize>,
            (i, n): (usize, usize),
            found: Vec<A>,
        ) -> Comp<T, Vec<A>> {
            if i == n {
                return pure(found);
            }
            k.resume(r, i).flat_map(move |more| {
                let found = found.iter().cloned().chain(more).collect();
                from(k.clone(), r, (i + 1, n), found)
            })
        }
        let on = |op, k| match op {
            Choose::Among(n, r) => from(k, r, (0, n), Vec::new()),
        };
        Handler::new(|a| pure(vec![a]), on).handle(p)
    }

    /// The first value the program comes to, the other paths not taken.
    pub fn first<R: Has<Self, I>, I, A: 'static>(p: Comp<R, A>) -> Comp<R::Rest, Option<A>> {
        // the first value of the paths from the `i`-th of `n` on
        fn from<T: 'static, A: 'static>(
            k: K<T, Option<A>>,
            r: Reply<usize>,
            (i, n): (usize, usize),
        ) -> Comp<T, Option<A>> {
            if i == n {
                return pure(None);
            }
            k.resume(r, i).flat_map(move |found| match found {
                Some(a) => pure(Some(a)),
                None => from(k.clone(), r, (i + 1, n)),
            })
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
    pub fn fresh<R: Has<Self, I>, I>() -> Comp<R, i64> {
        perform(Fresh::Next)
    }

    pub fn run<R: Has<Self, I>, I, A: 'static>(p: Comp<R, A>) -> Comp<R::Rest, A> {
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
    pub fn now<R: Has<Self, I>, I>() -> Comp<R, i64> {
        perform(Clock::Now)
    }

    pub fn system<R: Has<Self, I>, I, A: 'static>(p: Comp<R, A>) -> Comp<R::Rest, A> {
        let millis = || SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_millis() as i64);
        Self::at(millis, p)
    }

    pub fn at<R: Has<Self, I>, I, A: 'static>(time: impl Fn() -> i64 + 'static, p: Comp<R, A>) -> Comp<R::Rest, A> {
        let answer = move |_: &mut (), op| match op {
            Clock::Now(r) => r.give(time()),
        };
        Handler::in_place((), |_, a| pure(a), answer).handle(p)
    }
}
