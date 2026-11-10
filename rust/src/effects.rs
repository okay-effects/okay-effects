//! The standard effects: each a few operations (data) and a handler or two (functions on programs).

use std::marker::PhantomData;
use std::rc::Rc;

use crate::{Comp, Handler, NoHandler, Op, Step, perform, pure};

// -- STATE

pub struct Get<S>(PhantomData<S>);
pub struct Put<S>(pub S);

impl<S: Clone + 'static> Op for Get<S> {
    type Answer = S;
}
impl<S: Clone + 'static> Op for Put<S> {
    type Answer = ();
}

pub fn get<S: Clone + 'static>() -> Comp<S> {
    perform(Get(PhantomData))
}

pub fn put<S: Clone + 'static>(s: S) -> Comp<()> {
    perform(Put(s))
}

/// The state passed along: a program's answer is a function of the state it is resumed in.
pub fn state<S: Clone + 'static, A: Clone + 'static>(s0: S, p: Comp<A>) -> Comp<(S, A)> {
    type In<S, A> = Rc<dyn Fn(S) -> Comp<(S, A)>>;
    let h: Handler<A, In<S, A>> = Handler::new(|a: A| pure(Rc::new(move |s: S| pure((s, a.clone()))) as In<S, A>))
        .on(|_: &Get<S>, k| {
            pure(Rc::new(move |s: S| k.resume(s.clone()).flat_map(move |f| f(s.clone()))) as In<S, A>)
        })
        .on(|put: &Put<S>, k| {
            let s = put.0.clone();
            pure(Rc::new(move |_: S| {
                let s = s.clone();
                k.resume(()).flat_map(move |f| f(s.clone()))
            }) as In<S, A>)
        });
    h.handle(p).flat_map(move |f| f(s0.clone()))
}

// -- WRITER: values told, collected in order

pub struct Tell<W>(pub W);

impl<W: Clone + 'static> Op for Tell<W> {
    type Answer = ();
}

pub fn tell<W: Clone + 'static>(w: W) -> Comp<()> {
    perform(Tell(w))
}

pub fn writer<W: Clone + 'static, A: Clone + 'static>(p: Comp<A>) -> Comp<(Vec<W>, A)> {
    Handler::new(|a: A| pure((Vec::<W>::new(), a)))
        .on(|tell: &Tell<W>, k| {
            let w = tell.0.clone();
            k.resume(()).map(move |(mut log, a): (Vec<W>, A)| {
                log.insert(0, w.clone());
                (log, a)
            })
        })
        .handle(p)
}

// -- THROWS: the rest of the program is dropped

pub struct Raise<E>(pub E);

/// What answers a raise: nothing.
#[derive(Clone)]
pub enum Never {}

impl<E: Clone + 'static> Op for Raise<E> {
    type Answer = Never;
}

pub fn raise<E: Clone + 'static, A: Clone + 'static>(error: E) -> Comp<A> {
    perform(Raise(error)).map(|never| match never {})
}

pub fn throws<E: Clone + 'static, A: Clone + 'static>(p: Comp<A>) -> Comp<Result<A, E>> {
    Handler::new(|a: A| pure(Ok(a))).on(|raise: &Raise<E>, _| pure(Err(raise.0.clone()))).handle(p)
}

// -- CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times

pub struct Among<X>(pub Vec<X>);

impl<X: Clone + 'static> Op for Among<X> {
    type Answer = X;
}

pub fn among<X: Clone + 'static>(values: Vec<X>) -> Comp<X> {
    perform(Among(values))
}

/// Every value the program comes to.
pub fn all<X: Clone + 'static, A: Clone + 'static>(p: Comp<A>) -> Comp<Vec<A>> {
    Handler::new(|a: A| pure(vec![a]))
        .on(|among: &Among<X>, k| {
            among.0.iter().fold(pure(Vec::new()), |found, x| {
                let (k, x) = (k.clone(), x.clone());
                found.flat_map(move |found: Vec<A>| {
                    k.resume(x.clone()).map(move |more| [found.clone(), more].concat())
                })
            })
        })
        .handle(p)
}

// -- EMIT: a stream, possibly endless — who handles it takes one value and keeps the rest for later

pub struct Send<T>(pub T);

impl<T: Clone + 'static> Op for Send<T> {
    type Answer = ();
}

pub fn send<T: Clone + 'static>(value: T) -> Comp<()> {
    perform(Send(value))
}

/// The values sent, pulled as they are needed: a pull is a step of the machine.
pub fn stream<T: Clone + 'static, A: Clone + 'static>(p: Comp<A>) -> impl Iterator<Item = Result<T, NoHandler>> {
    let mut rest = Some(p);
    std::iter::from_fn(move || match rest.take()?.step() {
        Step::Done(_) => None,
        Step::Asked(asked) => match asked.op::<Send<T>>() {
            Some(send) => {
                let value = send.0.clone();
                rest = Some(asked.resume::<Send<T>>(()));
                Some(Ok(value))
            }
            None => Some(Err(NoHandler { op: asked.name() })),
        },
    })
}

// -- ASYNC: the answer comes later — the handler keeps the rest and returns; who has the answer resumes it

pub type Resume<X> = Rc<dyn Fn(X)>;

pub struct Await<X>(pub Rc<dyn Fn(Resume<X>)>);

impl<X: Clone + 'static> Op for Await<X> {
    type Answer = X;
}

pub fn wait<X: Clone + 'static>(register: impl Fn(Resume<X>) + 'static) -> Comp<X> {
    perform(Await(Rc::new(register)))
}

/// The program started; `done` is called with its value, now or later. Its awaits are of `X`s.
pub fn start<X: Clone + 'static, A: Clone + 'static>(p: Comp<A>, done: impl Fn(A) + 'static) {
    let h = Handler::new(move |a: A| {
        done(a);
        pure(())
    })
    .on(|wait: &Await<X>, k| {
        (wait.0)(Rc::new(move |x| k.resume(x).run().expect("an operation with no handler, after an await")));
        pure(())
    });
    h.handle(p).run().expect("an operation with no handler");
}
