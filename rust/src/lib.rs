//! Algebraic effects and handlers: a computation is data, a handler answers its operations, one small machine runs
//! it. The same model as the Scala and the Java kernels beside this one; the same programs come to the same answers.
//!
//! An effect is one enum; each operation is a variant that carries a `Reply<X>`, the witness of what answers it.
//! A handler is one closure with a `match` over the enum. A computation `impl Comp<R, A>` carries the ROW `R` of
//! the effects it may ask, a list of types: a handler takes its effect out of the row, and only a program with the
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
//! fn deposit<R: Has<Account, I>, I>(amount: i64) -> impl Comp<R, ()> {
//!     perform(|r| Account::Deposit(amount, r))
//! }
//!
//! fn balance<R: Has<Account, I>, I>() -> impl Comp<R, i64> {
//!     perform(Account::Balance)
//! }
//!
//! // a program of two effects
//! fn program() -> impl Comp<row![Account, Choose], i64> {
//!     Choose::among(vec![1, 2]).flat_map(|x| deposit(x * 10)).then(|| balance())
//! }
//!
//! // a handler: every operation answered in place, from a balance it keeps
//! fn bank<A: 'static>(start: i64, p: impl Comp<row![Account, Choose], A>) -> impl Comp<row![Choose], A> {
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
//! # What a program is made of, and what you write
//!
//! A program is composed STATICALLY: `flat_map`, `map`, `then`, `perform`, `handle` each return a struct that holds
//! its parts — nothing is allocated, and rustc compiles a chain of them into straight-line code. So the type of a
//! program is its shape, and it is not written: a function returns `impl Comp<R, A>`.
//!
//! The type is forgotten only where you say so, with `.boxed()`, which gives a `Dyn<R, A>` — a computation of any
//! shape, kept in place when it is small and in a box when it is not, and started by the machine's loop (so a
//! call in tail position takes no stack). You need it in three places, where rustc asks for one type:
//!
//!   * RECURSION. A function that calls itself, however far away, returns `Dyn<R, A>`: its shape would hold
//!     itself. Every loop of a program is such a function.
//!   * BRANCHES of different shapes: each arm of the `if` or the `match` ends with `.boxed()`.
//!   * A PROGRAM KEPT AS A VALUE — in a field, a `Vec`, behind a `dyn Fn`: its type is `Dyn<R, A>`.
//!
//! ```
//! use okay_effects::effects::{State, Throws};
//! use okay_effects::{Comp, Dyn, pure, row};
//!
//! // counts down to nothing, or fails: a loop, and two shapes of its end
//! fn down(n: i64) -> Dyn<row![State<i64>, Throws<String>], i64> {
//!     if n < 0 {
//!         return Throws::raise("below nothing".to_string()).boxed();
//!     }
//!     if n == 0 {
//!         return State::get().boxed();
//!     }
//!     State::modify(|steps: i64| steps + 1).then(move || down(n - 1)).boxed()
//! }
//!
//! assert_eq!(Throws::run(State::run(0, down(1000))).run(), Ok((1000, 1000)));
//! assert_eq!(State::run(0, Throws::run(down(-1))).run(), (0, Err("below nothing".to_string())));
//! assert_eq!(pure::<(), _>(1).map(|x| x + 1).run(), 2);
//! ```
//!
//! A function that is given a reference and returns `impl Comp<R, A>` says so with `+ use<R, I>` (its type
//! parameters): without it rustc takes the program to hold the reference.
//!
//! A closure given to `flat_map`, `map`, `then` or `defer` is `FnOnce + Clone + 'static`. It is called once — it
//! may give away what it holds —, and it is `Clone` because it is part of a continuation: when a handler resumes
//! a continuation a second time, what follows the operation is copied, closures and what they hold. So what a
//! closure holds must be `Clone`, and is best cheap to copy: keep a big value in an `Rc`. A program resumed once
//! copies nothing. The closures of a handler (`Handler::new`, `Handler::in_place`) are plain `Fn`.
//!
//! A program built at run time — by a fold, from a tree, by name — is built of `Dyn`s, each step `.boxed()`:
//! slower than straight-line code, with a frame on the heap wherever a value is waited for, and as safe for the
//! stack.
//!
//! # What rustc refuses
//!
//! An effect left in the row: the program does not run.
//!
//! ```compile_fail,E0277
//! use okay_effects::effects::{Choose, State};
//! use okay_effects::{Comp, Dyn, row};
//!
//! let p: Dyn<row![State<i64>, Choose], i64> = State::get().boxed();
//! State::run(0, p).run(); // `Choose` is not handled
//! ```
//!
//! An operation of an effect that is not in the program's row: it cannot be asked.
//!
//! ```compile_fail,E0277
//! use okay_effects::effects::{Choose, State};
//! use okay_effects::{Comp, Dyn, row};
//!
//! let p: Dyn<row![Choose], i64> = State::get().boxed();
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
//! fn bank(p: impl Comp<row![Account], i64>) -> impl Comp<row![], i64> {
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
//! fn bank(p: impl Comp<row![Account], i64>) -> impl Comp<row![], i64> {
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
mod erased;
pub mod json;
mod machine;
mod pool;
mod val;
pub mod wire;

use erased::Erased;
use machine::{Clause, Kont, Machine, Mark, Node, Pieces, Ret, Start, State};
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
pub trait Includes<S, Is>: 'static {}

impl<R: 'static> Includes<(), ()> for R {}

impl<R: Has<E, I> + Includes<T, Is>, E, T, I, Is> Includes<(E, T), (I, Is)> for R {}

/// The row with no effect left in it, `()`: only a program of this row has `run`.
#[diagnostic::on_unimplemented(message = "a program runs when no effect is left in its row: `{Self}` is not handled")]
pub trait Closed {}

impl Closed for () {}

/// A row, written as a list: `row![State<i64>, Choose]` is `(State<i64>, (Choose, ()))`.
#[macro_export]
macro_rules! row {
    () => { () };
    ($effect:ty $(, $rest:ty)* $(,)?) => { ($effect, $crate::row![$($rest),*]) };
}

// -- A COMPUTATION

/// A computation of an `A` that may ask the operations of the row `R`. Only data: nothing runs until `step`.
/// It is run once: what is asked for many times is built by a function. Every computation is one of the kernel's
/// structs — `Pure`, `Ask`, `Bind`..., and `Dyn`, the one whose type is forgotten; a function says
/// `impl Comp<R, A>` and need not name them.
pub trait Comp<R: 'static, A: 'static>: Start<Row = R, Out = A> {
    fn flat_map<B: 'static, D: Comp<R, B>, F: FnOnce(A) -> D + Clone + 'static>(self, f: F) -> Bind<Self, F> {
        Bind { c: self, f }
    }

    fn map<B: 'static, F: FnOnce(A) -> B + Clone + 'static>(self, f: F) -> Map<Self, F> {
        Map { c: self, f }
    }

    /// The computation after this one, whose value is not needed.
    fn then<B: 'static, D: Comp<R, B>, F: FnOnce() -> D + Clone + 'static>(self, next: F) -> Then<Self, F> {
        Then { c: self, f: next }
    }

    /// THE BOUNDARY: the computation with its type forgotten — kept in place if it is small, in a box if not.
    fn boxed(self) -> Dyn<R, A> {
        Dyn { erased: self.erase(), of: PhantomData }
    }

    /// The same computation in a bigger row, where rustc does not see by itself that it fits.
    fn embed<S: Includes<R, Is>, Is>(self) -> In<Self, S> {
        In { c: self, of: PhantomData }
    }

    /// The same computation in the row with `F` before: for a handler that answers with the operations of `F`.
    fn also<F: 'static>(self) -> In<Self, (F, R)> {
        In { c: self, of: PhantomData }
    }

    /// Where the computation comes to: its value, or an operation that no handler in it answers and the rest.
    fn step(self) -> Step<R, A> {
        match machine::run(|m| self.start(Ret, m)) {
            Ok(x) => Step::Done(x.take()),
            Err((op, k)) => Step::Asked(Asked { op, k: K { k, of: PhantomData } }),
        }
    }

    /// A program with nothing left to ask, run to its value.
    fn run(self) -> A
    where
        R: Closed,
    {
        match self.step() {
            Step::Done(a) => a,
            Step::Asked(_) => unreachable!("the empty row has no operation to ask"),
        }
    }
}

impl<C: Start> Comp<C::Row, C::Out> for C {}

/// A computation whose type is forgotten: what a function that calls itself returns, what branches of different
/// shapes come to, what is kept as a value. Made by `.boxed()`.
pub struct Dyn<R, A> {
    erased: Erased,
    of: PhantomData<fn() -> (R, A)>,
}

impl<R: 'static, A: 'static> Start for Dyn<R, A> {
    type Row = R;
    type Out = A;

    #[inline(always)]
    fn start<K: Kont<A>>(self, k: K, m: &mut Machine) -> Node {
        m.push(k);
        Node::Run(self.erased)
    }

    fn erase(self) -> Erased {
        self.erased
    }
}

/// A value.
pub struct Pure<R, A> {
    a: A,
    of: PhantomData<fn() -> R>,
}

pub fn pure<R: 'static, A: 'static>(a: A) -> Pure<R, A> {
    Pure { a, of: PhantomData }
}

impl<R: 'static, A: 'static> Start for Pure<R, A> {
    type Row = R;
    type Out = A;

    #[inline(always)]
    fn start<K: Kont<A>>(self, k: K, m: &mut Machine) -> Node {
        k.apply(self.a, m)
    }
}

/// A computation, and what follows its value: a function to the next computation.
pub struct Bind<C, F> {
    c: C,
    f: F,
}

#[derive(Clone)]
struct BindK<F, K> {
    f: F,
    k: K,
}

impl<C: Start, D: Start<Row = C::Row>, F: FnOnce(C::Out) -> D + Clone + 'static> Start for Bind<C, F> {
    type Row = C::Row;
    type Out = D::Out;

    #[inline(always)]
    fn start<K: Kont<D::Out>>(self, k: K, m: &mut Machine) -> Node {
        self.c.start(BindK { f: self.f, k }, m)
    }
}

impl<A, D: Start, F: FnOnce(A) -> D + Clone + 'static, K: Kont<D::Out>> Kont<A> for BindK<F, K> {
    #[inline(always)]
    fn apply(self, a: A, m: &mut Machine) -> Node {
        (self.f)(a).start(self.k, m)
    }
}

/// A computation, and a function of its value.
pub struct Map<C, F> {
    c: C,
    f: F,
}

#[derive(Clone)]
struct MapK<F, K> {
    f: F,
    k: K,
}

impl<C: Start, B: 'static, F: FnOnce(C::Out) -> B + Clone + 'static> Start for Map<C, F> {
    type Row = C::Row;
    type Out = B;

    #[inline(always)]
    fn start<K: Kont<B>>(self, k: K, m: &mut Machine) -> Node {
        self.c.start(MapK { f: self.f, k }, m)
    }
}

impl<A, B, F: FnOnce(A) -> B + Clone + 'static, K: Kont<B>> Kont<A> for MapK<F, K> {
    #[inline(always)]
    fn apply(self, a: A, m: &mut Machine) -> Node {
        self.k.apply((self.f)(a), m)
    }
}

/// A computation, and the one after it.
pub struct Then<C, F> {
    c: C,
    f: F,
}

#[derive(Clone)]
struct ThenK<F, K> {
    f: F,
    k: K,
}

impl<C: Start, D: Start<Row = C::Row>, F: FnOnce() -> D + Clone + 'static> Start for Then<C, F> {
    type Row = C::Row;
    type Out = D::Out;

    #[inline(always)]
    fn start<K: Kont<D::Out>>(self, k: K, m: &mut Machine) -> Node {
        self.c.start(ThenK { f: self.f, k }, m)
    }
}

impl<A, D: Start, F: FnOnce() -> D + Clone + 'static, K: Kont<D::Out>> Kont<A> for ThenK<F, K> {
    #[inline(always)]
    fn apply(self, _: A, m: &mut Machine) -> Node {
        (self.f)().start(self.k, m)
    }
}

/// A computation built when it is reached: a call in tail position takes no stack.
pub fn defer<R: 'static, A: 'static, D: Comp<R, A>>(p: impl FnOnce() -> D + Clone + 'static) -> Dyn<R, A> {
    pure(()).then(p).boxed()
}

/// A computation seen in another row, which holds its own.
pub struct In<C, S> {
    c: C,
    of: PhantomData<fn() -> S>,
}

impl<C: Start, S: 'static> Start for In<C, S> {
    type Row = S;
    type Out = C::Out;

    #[inline(always)]
    fn start<K: Kont<C::Out>>(self, k: K, m: &mut Machine) -> Node {
        self.c.start(k, m)
    }
}

// -- OPERATIONS

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

/// An operation of the effect `E`, answered by an `X`.
pub struct Ask<R, E, X> {
    op: E,
    of: PhantomData<fn() -> (R, X)>,
}

/// An operation of the effect `E`, asked: `perform(State::Get)`, `perform(|r| State::Put(s, r))`.
pub fn perform<E: 'static, X: 'static, R: Has<E, I>, I>(operation: impl FnOnce(Reply<X>) -> E) -> Ask<R, E, X> {
    Ask { op: operation(Reply(PhantomData)), of: PhantomData }
}

impl<R: 'static, E: 'static, X: 'static> Start for Ask<R, E, X> {
    type Row = R;
    type Out = X;

    #[inline(always)]
    fn start<K: Kont<X>>(self, k: K, m: &mut Machine) -> Node {
        match m.answered(Val::new(self.op)) {
            // in place: the program just goes on
            Ok(x) => k.apply(x.take(), m),
            // what follows is taken with the levels, as a frame
            Err(op) => {
                m.push(k);
                Node::Ask(op)
            }
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

impl<R: 'static, A: 'static> Asked<R, A> {
    /// The operation, if it is of the effect `E`.
    pub fn op<E: 'static, I>(&self) -> Option<&E>
    where
        R: Has<E, I>,
    {
        self.op.get()
    }

    /// The rest resumed with the answer.
    pub fn resume<X: 'static>(&self, reply: Reply<X>, x: X) -> Resumed<R, A> {
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

impl<R: 'static, B: 'static> K<R, B> {
    /// The rest resumed with the answer; `reply` is the operation's own.
    pub fn resume<X: 'static>(&self, _reply: Reply<X>, x: X) -> Resumed<R, B> {
        Resumed { k: self.k.clone(), x: Val::new(x), of: PhantomData }
    }

    /// The operation asked again outside, the rest resumed with what is answered there: interception, audit.
    pub fn pass<E: 'static, I>(&self, op: E) -> Passed<R, B>
    where
        R: Has<E, I>,
    {
        Passed { k: self.k.clone(), op: Val::new(op), of: PhantomData }
    }
}

/// A continuation resumed with an answer.
pub struct Resumed<R, B> {
    k: Pieces,
    x: Val,
    of: PhantomData<fn() -> (R, B)>,
}

impl<R: 'static, B: 'static> Start for Resumed<R, B> {
    type Row = R;
    type Out = B;

    fn start<Kn: Kont<B>>(self, k: Kn, m: &mut Machine) -> Node {
        m.push(k);
        Node::Resume(self.k, self.x)
    }
}

/// An operation passed outside, its answer resuming a continuation.
pub struct Passed<R, B> {
    k: Pieces,
    op: Val,
    of: PhantomData<fn() -> (R, B)>,
}

impl<R: 'static, B: 'static> Start for Passed<R, B> {
    type Row = R;
    type Out = B;

    fn start<Kn: Kont<B>>(self, k: Kn, m: &mut Machine) -> Node {
        m.push(k);
        m.push_resume(self.k);
        Node::Ask(self.op)
    }
}

/// A handler of the effect `E`, in a program of an `A`: a `B`, asking what is left, the row `R`.
pub struct Handler<E, A, B, R> {
    mark: Mark,
    state: State,
    of: PhantomData<fn(E, A) -> (B, R)>,
}

impl<E: 'static, A: 'static, B: 'static, R: 'static> Handler<E, A, B, R> {
    /// THE GENERAL HANDLER. A clause is given the rest of the program, `k`, as a value.
    pub fn new<P: Comp<R, B>, Q: Comp<R, B>>(
        ret: impl Fn(A) -> P + 'static,
        on: impl Fn(E, K<R, B>) -> Q + 'static,
    ) -> Self {
        let ret = Box::new(move |_, a: Val, m: &mut Machine| ret(a.take()).start(Ret, m));
        let on = Rc::new(move |op: Val, k, m: &mut Machine| on(op.take(), K { k, of: PhantomData }).start(Ret, m));
        let mark = Mark { effect: TypeId::of::<E>(), mine: None, ret, clause: Clause::General(on) };
        Handler { mark, state: Rc::new(()), of: PhantomData }
    }

    /// THE HANDLER IN PLACE: every operation answered at once, from a state the handler keeps — nothing is
    /// captured, the program goes on. The state belongs to the path: a continuation resumed twice starts twice
    /// from the state it was taken with.
    pub fn in_place<S: Clone + 'static, P: Comp<R, B>>(
        s0: S,
        ret: impl Fn(S, A) -> P + 'static,
        answer: impl Fn(&mut S, E) -> Answer + 'static,
    ) -> Self {
        let ret = Box::new(move |state, a: Val, m: &mut Machine| ret(taken(state), a.take()).start(Ret, m));
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
    pub fn handle<In: Has<E, I, Rest = R>, I, C: Comp<In, A>>(self, p: C) -> Handled<C, R, B> {
        Handled { mark: Rc::new(self.mark), state: self.state, body: p, of: PhantomData }
    }

    /// `handle`, the effect kept in the row: a clause may ask the operation again — interception, audit.
    pub fn intercept<I, C: Comp<R, A>>(self, p: C) -> Handled<C, R, B>
    where
        R: Has<E, I>,
    {
        Handled { mark: Rc::new(self.mark), state: self.state, body: p, of: PhantomData }
    }
}

/// A computation under a handler: a `B`, asking the row `R`.
pub struct Handled<C, R, B> {
    mark: Rc<Mark>,
    state: State,
    body: C,
    of: PhantomData<fn() -> (R, B)>,
}

impl<C: Start, R: 'static, B: 'static> Start for Handled<C, R, B> {
    type Row = R;
    type Out = B;

    fn start<K: Kont<B>>(self, k: K, m: &mut Machine) -> Node {
        m.push(k);
        m.enter(self.mark, self.state);
        self.body.start(Ret, m)
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
