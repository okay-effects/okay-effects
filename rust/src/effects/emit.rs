//! EMIT: a stream of values, possibly endless. The producer is a program that sends; what a stream is made into —
//! a list, a loop, another stream, an iterator pulled a value at a time — is the handler's choice.

use std::cell::{OnceCell, RefCell};
use std::mem::swap;
use std::rc::Rc;

use crate::{Comp, Handler, Has, K, Prog, Reply, There, defer, eff, perform, pure};

pub enum Emit<T> {
    Send(T, Reply<()>),
}

impl<T: Clone + 'static> Emit<T> {
    pub fn send<R: Has<Self, I>, I>(value: T) -> impl Prog<(), R> {
        perform(|r| Emit::Send(value, r))
    }

    /// Every value of a sequence, which may be lazy and endless.
    pub fn send_all<R: Has<Self, I>, I: 'static>(values: impl IntoIterator<Item = T> + 'static) -> Comp<(), R> {
        fn from<T: Clone + 'static, R: Has<Emit<T>, I>, I: 'static>(at: Lazy<T>) -> Comp<(), R> {
            defer(move || match at.get() {
                None => pure(()).boxed(),
                Some((value, rest)) => Emit::send(value).then(move || from(rest)).boxed(),
            })
        }
        from(Lazy::new(values.into_iter()))
    }

    /// The values sent, with the program's own.
    pub fn collect<R: Has<Self, I>, I, A: 'static>(p: impl Prog<A, R>) -> impl Prog<(Vec<T>, A), R::Rest> {
        let answer = |sent: &mut Vec<T>, op| match op {
            Emit::Send(value, r) => {
                sent.push(value);
                r.give(())
            }
        };
        Handler::in_place(Vec::new(), |sent, a| pure((sent, a)), answer).handle(p)
    }

    /// `f` run for each value, as it is sent.
    pub fn each<R: Has<Self, I>, I, A: 'static, P: Prog<(), R::Rest>>(
        f: impl Fn(T) -> P + 'static,
        p: impl Prog<A, R>,
    ) -> impl Prog<A, R::Rest> {
        let on = move |op, k: K<A, R::Rest>| match op {
            Emit::Send(value, r) => f(value).then(move || k.resume(r, ())),
        };
        Handler::new(pure, on).handle(p)
    }

    /// A stream of the values, each through `f`.
    pub fn map<R: Has<Self, I>, I: 'static, U: Clone + 'static, A: 'static>(
        f: impl Fn(T) -> U + 'static,
        p: impl Prog<A, R>,
    ) -> impl Prog<A, (Emit<U>, R::Rest)> {
        let on = move |op, k: K<A, (Emit<U>, R::Rest)>| match op {
            Emit::Send(value, r) => Emit::send(f(value)).then(move || k.resume(r, ())),
        };
        Handler::new(pure, on).handle::<_, There<I>, _>(p.also::<Emit<U>>())
    }

    /// A stream of the values that `keep`: the others are not sent on.
    pub fn filter<R: Has<Self, I>, I: 'static, A: 'static>(
        keep: impl Fn(&T) -> bool + 'static,
        p: impl Prog<A, R>,
    ) -> impl Prog<A, R> {
        let on = move |op, k: K<A, R>| match op {
            Emit::Send(value, r) if keep(&value) => Self::send(value).then(move || k.resume(r, ())).boxed(),
            Emit::Send(_, r) => k.resume(r, ()).boxed(),
        };
        Handler::new(pure, on).intercept(p)
    }

    /// A stream of the first `n` values; the producer is not resumed after them.
    pub fn take<R: Has<Self, I>, I: 'static, A: 'static>(n: usize, p: impl Prog<A, R>) -> impl Prog<(), R> {
        // what is sent on depends on how many are still wanted: the handler answers with a function of that
        type Left<R> = Rc<dyn Fn(usize) -> Comp<(), R>>;
        let on = |op: Self, k: K<Left<R>, R>| match op {
            Emit::Send(value, r) => pure(Rc::new(move |left| {
                if left == 0 {
                    return pure(()).boxed();
                }
                let k = k.clone();
                let rest = move |_| match left {
                    1 => pure(()).boxed(),
                    _ => k.resume(r, ()).flat_map(move |rest: Left<R>| rest(left - 1)).boxed(),
                };
                Self::send(value.clone()).flat_map(rest).boxed()
            }) as Left<R>),
        };
        let none = |_| pure(Rc::new(|_| pure(()).boxed()) as Left<R>);
        Handler::new(none, on).intercept(p).flat_map(move |all| all(n))
    }

    /// The values, pulled as they are needed: the producer runs no further than was asked.
    pub fn pull<A: 'static>(p: impl Prog<A, eff![Emit<T>]>) -> Pull<T> {
        let on = |op, k| match op {
            Emit::Send(value, r) => pure(Sent(Some((value, r, k)))),
        };
        Pull { rest: Some(Handler::new(|_| pure(Sent(None)), on).handle(p).boxed()) }
    }

    /// Two streams, value by value in pairs, as long as both last.
    pub fn zip<U: Clone + 'static, A: 'static, B: 'static, R: Has<Emit<(T, U)>, I>, I: 'static>(
        a: impl Prog<A, eff![Emit<T>]>,
        b: impl Prog<B, eff![Emit<U>]>,
    ) -> Comp<(), R> {
        Emit::send_all(Emit::pull(a).zip(Emit::pull(b)))
    }

    /// Two streams in turn, a value of each; then what is left of the longer.
    pub fn merge<A: 'static, B: 'static, R: Has<Self, I>, I: 'static>(
        a: impl Prog<A, eff![Emit<T>]>,
        b: impl Prog<B, eff![Emit<T>]>,
    ) -> Comp<(), R> {
        let (mut first, mut second) = (Emit::pull(a), Emit::pull(b));
        Emit::send_all(std::iter::from_fn(move || {
            swap(&mut first, &mut second);
            second.next().or_else(|| first.next())
        }))
    }
}

/// A stream stopped at a value it sent, with the rest of it; or ended.
struct Sent<T>(Option<(T, Reply<()>, K<Sent<T>, ()>)>);

/// The values of a stream, as an iterator.
pub struct Pull<T> {
    rest: Option<Comp<Sent<T>, ()>>,
}

impl<T: 'static> Iterator for Pull<T> {
    type Item = T;

    fn next(&mut self) -> Option<T> {
        let Sent(sent) = self.rest.take()?.run();
        let (value, r, k) = sent?;
        self.rest = Some(k.resume(r, ()).boxed());
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
