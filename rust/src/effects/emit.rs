//! EMIT: a stream of values, possibly endless. The producer is a program that sends; what a stream is made into —
//! a list, a loop, another stream, an iterator pulled a value at a time — is the handler's choice.

use std::cell::{OnceCell, RefCell};
use std::mem::swap;
use std::rc::Rc;

use crate::{Comp, Handler, Has, K, Reply, There, defer, perform, pure, row};

pub enum Emit<T> {
    Send(T, Reply<()>),
}

impl<T: Clone + 'static> Emit<T> {
    pub fn send<R: Has<Self, I>, I>(value: T) -> Comp<R, ()> {
        perform(|r| Emit::Send(value, r))
    }

    /// Every value of a sequence, which may be lazy and endless.
    pub fn send_all<R: Has<Self, I>, I: 'static>(values: impl IntoIterator<Item = T> + 'static) -> Comp<R, ()> {
        fn from<T: Clone + 'static, R: Has<Emit<T>, I>, I: 'static>(at: Lazy<T>) -> Comp<R, ()> {
            defer(move || match at.get() {
                None => pure(()),
                Some((value, rest)) => Emit::send(value).then(move || from(rest.clone())),
            })
        }
        from(Lazy::new(values.into_iter()))
    }

    /// The values sent, with the program's own.
    pub fn collect<R: Has<Self, I>, I, A: 'static>(p: Comp<R, A>) -> Comp<R::Rest, (Vec<T>, A)> {
        let answer = |sent: &mut Vec<T>, op| match op {
            Emit::Send(value, r) => {
                sent.push(value);
                r.give(())
            }
        };
        Handler::in_place(Vec::new(), |sent, a| pure((sent, a)), answer).handle(p)
    }

    /// `f` run for each value, as it is sent.
    pub fn each<R: Has<Self, I>, I, A: 'static>(
        f: impl Fn(T) -> Comp<R::Rest, ()> + 'static,
        p: Comp<R, A>,
    ) -> Comp<R::Rest, A> {
        let on = move |op, k: K<R::Rest, A>| match op {
            Emit::Send(value, r) => f(value).then(move || k.resume(r, ())),
        };
        Handler::new(pure, on).handle(p)
    }

    /// A stream of the values, each through `f`.
    pub fn map<R: Has<Self, I>, I: 'static, U: Clone + 'static, A: 'static>(
        f: impl Fn(T) -> U + 'static,
        p: Comp<R, A>,
    ) -> Comp<(Emit<U>, R::Rest), A> {
        let on = move |op, k: K<(Emit<U>, R::Rest), A>| match op {
            Emit::Send(value, r) => Emit::send(f(value)).then(move || k.resume(r, ())),
        };
        Handler::new(pure, on).handle::<_, There<I>>(p.also::<Emit<U>>())
    }

    /// A stream of the values that `keep`: the others are not sent on.
    pub fn filter<R: Has<Self, I>, I: 'static, A: 'static>(
        keep: impl Fn(&T) -> bool + 'static,
        p: Comp<R, A>,
    ) -> Comp<R, A> {
        let on = move |op, k: K<R, A>| match op {
            Emit::Send(value, r) if keep(&value) => Self::send(value).then(move || k.resume(r, ())),
            Emit::Send(_, r) => k.resume(r, ()),
        };
        Handler::new(pure, on).intercept(p)
    }

    /// A stream of the first `n` values; the producer is not resumed after them.
    pub fn take<R: Has<Self, I>, I: 'static, A: 'static>(n: usize, p: Comp<R, A>) -> Comp<R, ()> {
        // what is sent on depends on how many are still wanted: the handler answers with a function of that
        type Left<R> = Rc<dyn Fn(usize) -> Comp<R, ()>>;
        let on = |op: Self, k: K<R, Left<R>>| match op {
            Emit::Send(value, r) => pure(Rc::new(move |left| {
                if left == 0 {
                    return pure(());
                }
                let k = k.clone();
                Self::send(value.clone()).flat_map(move |_| match left {
                    1 => pure(()),
                    _ => k.resume(r, ()).flat_map(move |rest: Left<R>| rest(left - 1)),
                })
            }) as Left<R>),
        };
        Handler::new(|_| pure(Rc::new(|_| pure(())) as Left<R>), on).intercept(p).flat_map(move |all| all(n))
    }

    /// The values, pulled as they are needed: the producer runs no further than was asked.
    pub fn pull<A: 'static>(p: Comp<row![Emit<T>], A>) -> Pull<T> {
        let on = |op, k| match op {
            Emit::Send(value, r) => pure(Sent(Some((value, r, k)))),
        };
        Pull { rest: Some(Handler::new(|_| pure(Sent(None)), on).handle(p)) }
    }

    /// Two streams, value by value in pairs, as long as both last.
    pub fn zip<U: Clone + 'static, A: 'static, B: 'static, R: Has<Emit<(T, U)>, I>, I: 'static>(
        a: Comp<row![Emit<T>], A>,
        b: Comp<row![Emit<U>], B>,
    ) -> Comp<R, ()> {
        Emit::send_all(Emit::pull(a).zip(Emit::pull(b)))
    }

    /// Two streams in turn, a value of each; then what is left of the longer.
    pub fn merge<A: 'static, B: 'static, R: Has<Self, I>, I: 'static>(
        a: Comp<row![Emit<T>], A>,
        b: Comp<row![Emit<T>], B>,
    ) -> Comp<R, ()> {
        let (mut first, mut second) = (Emit::pull(a), Emit::pull(b));
        Emit::send_all(std::iter::from_fn(move || {
            swap(&mut first, &mut second);
            second.next().or_else(|| first.next())
        }))
    }
}

/// A stream stopped at a value it sent, with the rest of it; or ended.
struct Sent<T>(Option<(T, Reply<()>, K<(), Sent<T>>)>);

/// The values of a stream, as an iterator.
pub struct Pull<T> {
    rest: Option<Comp<(), Sent<T>>>,
}

impl<T: 'static> Iterator for Pull<T> {
    type Item = T;

    fn next(&mut self) -> Option<T> {
        let Sent(sent) = self.rest.take()?.run();
        let (value, r, k) = sent?;
        self.rest = Some(k.resume(r, ()));
        Some(value)
    }
}

/// A sequence read as it is made, each value made once: a stream resumed twice sends the same values twice.
#[derive(Clone)]
struct Lazy<T> {
    here: Rc<OnceCell<Option<(T, Lazy<T>)>>>,
    source: Rc<RefCell<dyn Iterator<Item = T>>>,
}

impl<T: Clone> Lazy<T> {
    fn new(source: impl Iterator<Item = T> + 'static) -> Lazy<T> {
        Lazy { here: Rc::default(), source: Rc::new(RefCell::new(source)) }
    }

    fn get(&self) -> Option<(T, Lazy<T>)> {
        let next = || Lazy { here: Rc::default(), source: self.source.clone() };
        self.here.get_or_init(|| self.source.borrow_mut().next().map(|value| (value, next()))).clone()
    }
}
