//! Algebraic effects and handlers: a computation is data, a handler answers its operations, one small machine runs
//! it. The same model as the Scala and the Java kernels beside this one; the same programs come to the same answers.
//!
//! An effect is one enum; each operation is a variant that carries a `Reply<X>`, the witness of what answers it.
//! A handler is one closure with a `match` over the enum. A computation `Comp<R, A>` carries the ROW `R` of the
//! effects it may ask, a list of types: a handler takes its effect out of the row, and only a program with the
//! empty row has `run`. So rustc refuses an effect left without a handler, a handler that misses an operation,
//! and an answer of the wrong type.
//!
//! ```
//! use okay_effects::effects::Choose;
//! use okay_effects::{Comp, Handler, Has, Reply, perform, pure, row};
//!
//! // an effect: its operations, each with what answers it
//! enum Account {
//!     Deposit(i64, Reply<()>),
//!     Balance(Reply<i64>),
//! }
//!
//! fn deposit<R: Has<Account, I>, I>(amount: i64) -> Comp<R, ()> {
//!     perform(|r| Account::Deposit(amount, r))
//! }
//!
//! fn balance<R: Has<Account, I>, I>() -> Comp<R, i64> {
//!     perform(Account::Balance)
//! }
//!
//! // a program of two effects
//! fn program() -> Comp<row![Account, Choose], i64> {
//!     Choose::among(vec![1, 2]).flat_map(|x| deposit(x * 10)).then(|| balance())
//! }
//!
//! // a handler: every operation answered in place, from a balance it keeps
//! fn bank<A: 'static>(start: i64, p: Comp<row![Account, Choose], A>) -> Comp<row![Choose], A> {
//!     let answer = |balance: &mut i64, op| match op {
//!         Account::Deposit(amount, r) => {
//!             *balance += amount;
//!             r.give(())
//!         }
//!         Account::Balance(r) => r.give(*balance),
//!     };
//!     Handler::in_place(start, |_, a| pure(a), answer).handle(p)
//! }
//!
//! // each handler takes its effect out of the row; with none left, the program runs
//! assert_eq!(Choose::all(bank(100, program())).run(), vec![110, 120]);
//! ```
//!
//! An effect left in the row: there is no `run`.
//!
//! ```compile_fail,E0599
//! use okay_effects::effects::{Choose, State};
//! use okay_effects::{Comp, row};
//!
//! let p: Comp<row![State<i64>, Choose], i64> = State::get();
//! State::run(0, p).run(); // `Choose` is not handled
//! ```
//!
//! An operation of an effect that is not in the program's row: it cannot be asked.
//!
//! ```compile_fail,E0277
//! use okay_effects::effects::{Choose, State};
//! use okay_effects::{Comp, row};
//!
//! let p: Comp<row![Choose], i64> = State::get();
//! ```
//!
//! A handler that misses an operation: the `match` is not exhaustive.
//!
//! ```compile_fail,E0004
//! # use okay_effects::{Comp, Handler, Reply, pure, row};
//! # enum Account {
//! #     Deposit(i64, Reply<()>),
//! #     Balance(Reply<i64>),
//! # }
//! fn bank(p: Comp<row![Account], i64>) -> Comp<row![], i64> {
//!     let answer = |balance: &mut i64, op| match op {
//!         Account::Balance(r) => r.give(*balance),
//!     };
//!     Handler::in_place(0, |_, a| pure(a), answer).handle(p)
//! }
//! ```
//!
//! An answer of the wrong type: a `Reply<i64>` gives only an `i64`.
//!
//! ```compile_fail,E0308
//! # use okay_effects::{Comp, Handler, Reply, pure, row};
//! # enum Account {
//! #     Deposit(i64, Reply<()>),
//! #     Balance(Reply<i64>),
//! # }
//! fn bank(p: Comp<row![Account], i64>) -> Comp<row![], i64> {
//!     let answer = |balance: &mut i64, op| match op {
//!         Account::Deposit(amount, r) => {
//!             *balance += amount;
//!             r.give(())
//!         }
//!         Account::Balance(r) => r.give("nothing"),
//!     };
//!     Handler::in_place(0, |_, a| pure(a), answer).handle(p)
//! }
//! ```

use std::any::TypeId;
use std::marker::PhantomData;
use std::rc::Rc;

pub mod effects;
pub mod json;
mod machine;
mod val;
pub mod wire;

use machine::{Clause, Mark, Node, Pieces, State, bind};
use val::Val;

// -- THE ROW: the effects a computation may ask, as a list of types — `()`, or an effect and the rest: `(E, R)`.
// It is a proof for rustc and nothing more: when a program runs, an operation finds its handler by its type.

/// The place of an effect in a row: the first.
pub struct Here;
/// The place of an effect in a row: after the first, at `I` of the rest.
pub struct There<I>(PhantomData<I>);

/// The effect `E` is in this row; `Rest` is the row without it. The place `I` is found by rustc, never written.
#[diagnostic::on_unimplemented(message = "the effect `{E}` is not in the row `{Self}`")]
pub trait Has<E, I>: 'static {
    type Rest: 'static;
}

impl<E: 'static, T: 'static> Has<E, Here> for (E, T) {
    type Rest = T;
}

impl<E, F: 'static, T: Has<E, I>, I> Has<E, There<I>> for (F, T) {
    type Rest = (F, T::Rest);
}

/// Every effect of the row `S` is in this row. The places `Is` are found by rustc.
#[diagnostic::on_unimplemented(message = "the row `{Self}` does not hold every effect of the row `{S}`")]
pub trait Includes<S, Is> {}

impl<R> Includes<(), ()> for R {}

impl<R: Has<E, I> + Includes<T, Is>, E, T, I, Is> Includes<(E, T), (I, Is)> for R {}

/// A row, written as a list: `row![State<i64>, Choose]` is `(State<i64>, (Choose, ()))`.
#[macro_export]
macro_rules! row {
    () => { () };
    ($effect:ty $(, $rest:ty)* $(,)?) => { ($effect, $crate::row![$($rest),*]) };
}

// -- A COMPUTATION

/// A computation of an `A` that may ask the operations of the row `R`. Only data: nothing runs until `step`.
/// It is run once: what is asked for many times is built by a function.
pub struct Comp<R, A> {
    node: Node,
    of: PhantomData<fn() -> (R, A)>,
}

fn comp<R, A>(node: Node) -> Comp<R, A> {
    Comp { node, of: PhantomData }
}

/// The witness that an operation is answered by an `X`. Only `perform` makes one.
pub struct Reply<X>(PhantomData<fn(X)>);

impl<X> Clone for Reply<X> {
    fn clone(&self) -> Self {
        *self
    }
}
impl<X> Copy for Reply<X> {}

/// An operation's answer, given in place.
pub struct Answer(Val);

impl<X: 'static> Reply<X> {
    pub fn give(self, x: X) -> Answer {
        Answer(Val::new(x))
    }
}

/// What answers an operation that is never answered: no value.
pub enum Never {}

pub fn pure<R, A: 'static>(a: A) -> Comp<R, A> {
    comp(Node::Done(Val::new(a)))
}

/// An operation of the effect `E`, asked: `perform(State::Get)`, `perform(|r| State::Put(s, r))`.
pub fn perform<E: 'static, X: 'static, R: Has<E, I>, I>(operation: impl FnOnce(Reply<X>) -> E) -> Comp<R, X> {
    comp(Node::Ask(Val::new(operation(Reply(PhantomData)))))
}

/// A computation built when it is reached: a call in tail position takes no stack.
pub fn defer<R, A>(p: impl Fn() -> Comp<R, A> + 'static) -> Comp<R, A> {
    comp(bind(Node::Done(Val::new(())), move |_| p().node))
}

impl<R, A: 'static> Comp<R, A> {
    pub fn flat_map<B>(self, f: impl Fn(A) -> Comp<R, B> + 'static) -> Comp<R, B> {
        comp(bind(self.node, move |x| f(x.take()).node))
    }

    pub fn map<B: 'static>(self, f: impl Fn(A) -> B + 'static) -> Comp<R, B> {
        comp(bind(self.node, move |x| Node::Done(Val::new(f(x.take())))))
    }

    /// The computation after this one, whose value is not needed.
    pub fn then<B>(self, next: impl Fn() -> Comp<R, B> + 'static) -> Comp<R, B> {
        comp(bind(self.node, move |_| next().node))
    }

    /// The same computation in a bigger row, where rustc does not see by itself that it fits.
    pub fn embed<S: Includes<R, Is>, Is>(self) -> Comp<S, A> {
        comp(self.node)
    }

    /// The same computation in the row with `F` before: for a handler that answers with the operations of `F`.
    pub fn also<F>(self) -> Comp<(F, R), A> {
        comp(self.node)
    }

    /// Where the computation comes to: its value, or an operation that no handler in it answers and the rest.
    pub fn step(self) -> Step<R, A> {
        match machine::run(self.node) {
            Ok(x) => Step::Done(x.take()),
            Err((op, k)) => Step::Asked(Asked { op, k: K { k, of: PhantomData } }),
        }
    }
}

impl<A: 'static> Comp<(), A> {
    /// A program with nothing left to ask, run to its value.
    pub fn run(self) -> A {
        match self.step() {
            Step::Done(a) => a,
            Step::Asked(_) => unreachable!("the empty row has no operation to ask"),
        }
    }
}

pub enum Step<R, A> {
    Done(A),
    Asked(Asked<R, A>),
}

/// An operation asked by a program of an `A`, and the rest of the program.
pub struct Asked<R, A> {
    op: Val,
    k: K<R, A>,
}

impl<R, A> Asked<R, A> {
    /// The operation, if it is of the effect `E`.
    pub fn op<E: 'static, I>(&self) -> Option<&E>
    where
        R: Has<E, I>,
    {
        self.op.get()
    }

    /// The rest resumed with the answer.
    pub fn resume<X: 'static>(&self, reply: Reply<X>, x: X) -> Comp<R, A> {
        self.k.resume(reply, x)
    }
}

// -- HANDLERS

/// The rest of a handled program, waiting for the answer of the operation it asked: a value, to resume once,
/// many times, later, or never. It comes to a `B`, asking the row `R`.
pub struct K<R, B> {
    k: Pieces,
    of: PhantomData<fn() -> (R, B)>,
}

impl<R, B> Clone for K<R, B> {
    fn clone(&self) -> Self {
        K { k: self.k.clone(), of: PhantomData }
    }
}

impl<R, B> K<R, B> {
    /// The rest resumed with the answer; `reply` is the operation's own.
    pub fn resume<X: 'static>(&self, _reply: Reply<X>, x: X) -> Comp<R, B> {
        comp(Node::Resume(self.k.clone(), Val::new(x)))
    }

    /// The operation asked again outside, the rest resumed with what is answered there: interception, audit.
    pub fn pass<E: 'static, I>(&self, op: E) -> Comp<R, B>
    where
        R: Has<E, I>,
    {
        let k = self.k.clone();
        comp(bind(Node::Ask(Val::new(op)), move |x| Node::Resume(k.clone(), x)))
    }
}

/// A handler of the effect `E`, in a program of an `A`: a `B`, asking what is left, the row `R`.
pub struct Handler<E, A, B, R> {
    mark: Mark,
    state: State,
    of: PhantomData<fn(E, A) -> (B, R)>,
}

impl<E: 'static, A: 'static, B, R> Handler<E, A, B, R> {
    /// THE GENERAL HANDLER. A clause is given the rest of the program, `k`, as a value.
    pub fn new(ret: impl Fn(A) -> Comp<R, B> + 'static, on: impl Fn(E, K<R, B>) -> Comp<R, B> + 'static) -> Self {
        let ret = Box::new(move |_, a: Val| ret(a.take()).node);
        let on = Rc::new(move |op: Val, k| on(op.take(), K { k, of: PhantomData }).node);
        let mark = Mark { effect: TypeId::of::<E>(), mine: None, ret, clause: Clause::General(on) };
        Handler { mark, state: Rc::new(()), of: PhantomData }
    }

    /// THE HANDLER IN PLACE: every operation answered at once, from a state the handler keeps — nothing is
    /// captured, the program goes on. The state belongs to the path: a continuation resumed twice starts twice
    /// from the state it was taken with.
    pub fn in_place<S: Clone + 'static>(
        s0: S,
        ret: impl Fn(S, A) -> Comp<R, B> + 'static,
        answer: impl Fn(&mut S, E) -> Answer + 'static,
    ) -> Self {
        let ret = Box::new(move |state, a: Val| ret(taken(state), a.take()).node);
        let answer = Box::new(move |state: &mut State, op: Val| answer(written(state), op.take()).0);
        let mark = Mark { effect: TypeId::of::<E>(), mine: None, ret, clause: Clause::InPlace(answer) };
        Handler { mark, state: Rc::new(s0), of: PhantomData }
    }

    /// Of the operations of `E`, those this handler answers; any other passes outside.
    pub fn mine(mut self, mine: impl Fn(&E) -> bool + 'static) -> Self {
        self.mark.mine = Some(Box::new(move |op: &Val| mine(op.get().expect("an operation of the effect"))));
        self
    }

    /// The operations of `E` answered, every other one passing outside; `E` leaves the row.
    pub fn handle<In: Has<E, I, Rest = R>, I>(self, p: Comp<In, A>) -> Comp<R, B> {
        comp(Node::Handle(Box::new((Rc::new(self.mark), self.state, p.node))))
    }

    /// `handle`, the effect kept in the row: a clause may ask the operation again — interception, audit.
    pub fn intercept<I>(self, p: Comp<R, A>) -> Comp<R, B>
    where
        R: Has<E, I>,
    {
        comp(Node::Handle(Box::new((Rc::new(self.mark), self.state, p.node))))
    }
}

/// A handler's state to be written: this path's alone — one shared with a continuation is copied first.
fn written<S: Clone + 'static>(state: &mut State) -> &mut S {
    if Rc::get_mut(state).is_none() {
        *state = Rc::new(state.downcast_ref::<S>().expect("the state of this handler").clone());
    }
    Rc::get_mut(state).and_then(|alone| alone.downcast_mut()).expect("the state of this handler")
}

/// A handler's state at the end of its program.
fn taken<S: Clone + 'static>(state: State) -> S {
    let state = state.downcast::<S>().expect("the state of this handler");
    Rc::try_unwrap(state).unwrap_or_else(|shared| (*shared).clone())
}
