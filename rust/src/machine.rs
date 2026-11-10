//! THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
//! the frames inside the handler, the handler, its state. The top level is the machine's `top`.
//!
//! A program is STRAIGHT-LINE CODE, composed by rustc: a computation is a struct (lib.rs) that is started with
//! what follows it, a `Kont`, also a struct. A bind, a map, an operation answered in place: one goes on into
//! the other with no memory taken and nothing for the loop to do. What follows becomes a FRAME, on the heap, only
//! where it must wait as a value:
//!
//!   Run        a computation whose type was forgotten (`.boxed()`): the loop starts it, so a call in tail position
//!              takes no stack. What follows it, if anything, is a frame
//!   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
//!   Ask        an operation not answered in place. General: the levels above and at the handler are taken off as
//!              `k`, and the clause runs below them. No handler: the run stops there
//!   Resume     the levels of a `k` are put back on top
//!   a handler  a new level; what follows the handled program is a frame of the level below
//!   ensuring   a frame that holds the finalizer and what follows. A value that comes to it waits while the
//!              finalizer runs
//!   unwinding  the levels of a `k` are put back on top, its handler GONE, and WALKED: the frames are passed with
//!              no value, a finalizer's frame runs its finalizer, a level is left without its `ret` — down to
//!              the frame that marks the unwinding's END, which gives `()` to what waits for it
//!
//! A computation is run once: it is taken by value, and a value is moved from where it is made to where it is asked
//! for. What may be run many times is a continuation, so what a `k` holds is shared and never written: a frame that
//! someone else holds runs a copy of what it keeps, and a state shared with a `k` is copied before it is written.
//! Types are checked at the kernel's surface (lib.rs); between a handler and its program values are of any type
//! (val.rs).

use std::any::{Any, TypeId};
use std::iter::once;
use std::marker::PhantomData;
use std::mem::{replace, take};
use std::rc::Rc;

use crate::erased::Erased;
use crate::pool::{Shared, With};
use crate::val::Val;

/// the state of a handler in place
pub type State = Rc<dyn Any>;
/// the levels of a continuation, the lowest first
pub type Pieces = Shared<[Level]>;
/// which unwinding: the one whose end holds this very one
pub type To = Rc<()>;

/// What the loop does next.
pub enum Node {
    /// a value, for the top frame
    Done(Val),
    /// an operation that was not answered in place; what follows it is already a frame
    Ask(Val),
    /// a computation of a forgotten type, to be started
    Run(Erased),
    /// a continuation taken earlier, resumed with an answer
    Resume(Pieces, Val),
}

/// What follows a value of type `A`, composed by rustc. It is used once; it is copied where a continuation is
/// resumed again, so it is `Clone`.
pub trait Kont<A>: Clone + 'static {
    /// nothing follows: the value is the level's
    const LAST: bool = false;

    fn apply(self, a: A, m: &mut Machine) -> Node;
}

/// The end of every chain: the value is given to the loop.
#[derive(Clone)]
pub struct Ret;

impl<A: 'static> Kont<A> for Ret {
    const LAST: bool = true;

    #[inline(always)]
    fn apply(self, a: A, _: &mut Machine) -> Node {
        Node::Done(Val::new(a))
    }
}

/// A computation, as the machine sees it: something started with what follows it. The kernel's structs are the
/// only ones (lib.rs); the surface names them by `Prog<A, R>`.
pub trait Start: Sized + 'static {
    /// the effects it may ask
    type Row: 'static;
    type Out: 'static;

    fn start<K: Kont<Self::Out>>(self, k: K, m: &mut Machine) -> Node;

    /// The type forgotten. Only what is erased already does it otherwise: it stays as it is.
    fn erase(self) -> Erased {
        Erased::new(self)
    }
}

/// A frame: what follows a value inside one level, kept on the heap, and the frames that wait under it. Once
/// linked it is never written, except by its only holder. Its memory is the pool's (pool.rs): a frame is let go
/// when its value comes, and the next one takes the room it leaves.
type Link = With<Under, dyn Frame>;

/// the frames that wait under a frame
struct Under(Option<Shared<Link>>);

pub trait Frame {
    /// the value given by the frame's only holder: what it keeps is moved out
    fn last(&mut self, x: Val, m: &mut Machine) -> Node;
    /// the value given while a continuation holds the frame too: a copy of what it keeps runs
    fn again(&self, x: Val, m: &mut Machine) -> Node;
    /// The frame passed with no value by the unwinding `to`, and what the loop does then, if the unwinding stops
    /// here: a finalizer's frame, the unwinding's end. Any other frame is only passed.
    fn left(&self, _to: &To, _m: &mut Machine) -> Option<Node> {
        None
    }
}

/// a `Kont` kept in a frame, until its value comes
struct Waits<K, A>(Option<K>, PhantomData<fn(A)>);

impl<A: 'static, K: Kont<A>> Frame for Waits<K, A> {
    fn last(&mut self, x: Val, m: &mut Machine) -> Node {
        self.0.take().expect("a frame is given its value once").apply(x.take(), m)
    }

    fn again(&self, x: Val, m: &mut Machine) -> Node {
        self.0.clone().expect("a frame is given its value once").apply(x.take(), m)
    }
}

/// a frame that resumes a continuation with the value: an operation passed outside (`K::pass`)
struct Resumes(Pieces);

impl Frame for Resumes {
    fn last(&mut self, x: Val, _: &mut Machine) -> Node {
        Node::Resume(self.0.clone(), x)
    }

    fn again(&self, x: Val, _: &mut Machine) -> Node {
        Node::Resume(self.0.clone(), x)
    }
}

/// `ensuring`: a finalizer, and what follows the value of the program it is on. With a value, the finalizer runs
/// and the value goes on after it; unwound, the finalizer runs and the unwinding goes on after it.
struct Finally<F, K, A>(Option<(F, K)>, PhantomData<fn(A)>);

impl<A: Clone + 'static, D: Start, F: FnOnce() -> D + Clone + 'static, K: Kont<A>> Frame for Finally<F, K, A> {
    fn last(&mut self, x: Val, m: &mut Machine) -> Node {
        let (finalizer, k) = self.0.take().expect("a frame is given its value once");
        finalizer().start(Gives { a: x.take(), k }, m)
    }

    fn again(&self, x: Val, m: &mut Machine) -> Node {
        let (finalizer, k) = self.0.clone().expect("a frame is given its value once");
        finalizer().start(Gives { a: x.take(), k }, m)
    }

    fn left(&self, to: &To, m: &mut Machine) -> Option<Node> {
        let (finalizer, _) = self.0.as_ref().expect("a frame is given its value once");
        m.link(Unwinding(to.clone()));
        Some((finalizer.clone())().start(Ret, m))
    }
}

/// a value that waits while its finalizer runs, and what follows it
#[derive(Clone)]
struct Gives<A, K> {
    a: A,
    k: K,
}

impl<X, A: Clone + 'static, K: Kont<A>> Kont<X> for Gives<A, K> {
    #[inline(always)]
    fn apply(self, _: X, m: &mut Machine) -> Node {
        self.k.apply(self.a, m)
    }
}

/// An unwinding goes on from here: what comes here — a finalizer's value, the result of a handler in the part
/// that is unwound — is dropped, and the frames under this one are walked.
struct Unwinding(To);

impl Frame for Unwinding {
    fn last(&mut self, _: Val, m: &mut Machine) -> Node {
        m.walk(&self.0)
    }

    fn again(&self, _: Val, m: &mut Machine) -> Node {
        m.walk(&self.0)
    }
}

/// The end of an unwinding: `()` to what waits for it. Another unwinding passes it.
struct End(To);

impl Frame for End {
    fn last(&mut self, _: Val, _: &mut Machine) -> Node {
        Node::Done(Val::new(()))
    }

    fn again(&self, _: Val, _: &mut Machine) -> Node {
        Node::Done(Val::new(()))
    }

    fn left(&self, to: &To, _: &mut Machine) -> Option<Node> {
        Rc::ptr_eq(&self.0, to).then(|| Node::Done(Val::new(())))
    }
}

/// A handler, as the machine sees it.
pub struct Mark {
    pub effect: TypeId,
    /// of the operations of the effect, those this handler answers; none: all of them
    pub mine: Option<Box<dyn Fn(&Val) -> bool>>,
    /// its effect is still in the row outside it (`intercept`): gone, it lets its operations pass
    pub keeps: bool,
    pub ret: Box<dyn Fn(State, Val, &mut Machine) -> Node>,
    pub clause: Clause,
}

pub enum Clause {
    /// the answer, at once, from the handler's state
    InPlace(Box<dyn Fn(&mut State, Val) -> Val>),
    /// a computation, given the rest of the program
    General(Rc<dyn Fn(Val, Pieces, &mut Machine) -> Node>),
    /// WHERE A GENERAL HANDLER WAS, while its `k` is unwound: the handler it was has left, and answers nothing.
    /// It took its effect out of the row (`handle`), so nobody is left to answer an operation of its own that a
    /// finalizer asks: the finalizer ends there, and the unwinding — this one — goes on.
    Gone(Rc<Mark>, To),
}

impl Mark {
    #[inline]
    fn accepts(&self, op: &Val) -> bool {
        self.effect == op.kind() && self.mine.as_ref().is_none_or(|mine| mine(op))
    }

    /// The handler `was`, gone while the unwinding `to` passes where it was.
    fn gone(was: &Rc<Mark>, to: &To) -> Rc<Mark> {
        let own = was.clone();
        Rc::new(Mark {
            effect: was.effect,
            mine: Some(Box::new(move |op| own.accepts(op))),
            keeps: false,
            ret: Box::new(|_, x, _| Node::Done(x)),
            clause: Clause::Gone(was.clone(), to.clone()),
        })
    }
}

#[derive(Clone, Default)]
pub struct Level {
    frames: Option<Shared<Link>>,
    handler: Option<(Rc<Mark>, State)>,
}

impl Level {
    fn is_empty(&self) -> bool {
        self.frames.is_none() && self.handler.is_none()
    }

    #[inline]
    fn accepts(&self, op: &Val) -> bool {
        self.handler.as_ref().is_some_and(|(mark, _)| mark.accepts(op))
    }

    /// The level as the unwinding `to` puts it back. The `lowest` is the level of the handler that unwinds: it has
    /// left — gone, or with nothing in its place if it kept its effect in the row. A place higher up where a
    /// handler had left before is now a place of this unwinding.
    fn left(&self, lowest: bool, to: &To) -> Level {
        let handler = self.handler.as_ref().and_then(|(mark, state)| match &mark.clause {
            Clause::Gone(was, _) => Some((Mark::gone(was, to), state.clone())),
            _ if !lowest => Some((mark.clone(), state.clone())),
            _ if mark.keeps => None,
            _ => Some((Mark::gone(mark, to), state.clone())),
        });
        Level { frames: self.frames.clone(), handler }
    }
}

pub struct Machine {
    top: Level,
    under: Vec<Level>,
}

impl Machine {
    /// What follows becomes a frame of the top level. In tail position nothing follows, and nothing is kept: a
    /// loop takes no room.
    #[inline]
    pub fn push<A: 'static, K: Kont<A>>(&mut self, k: K) {
        if !K::LAST {
            self.link(Waits(Some(k), PhantomData))
        }
    }

    /// A frame that resumes `k` with the value it is given.
    pub fn push_resume(&mut self, k: Pieces) {
        self.link(Resumes(k))
    }

    fn link(&mut self, k: impl Frame + 'static) {
        let under = Under(self.top.frames.take());
        self.top.frames = Some(Shared::new(under, k));
    }

    /// `ensuring`: a frame of the top level that holds the finalizer, and what follows the program's value.
    pub fn ensure<A, D, F, K>(&mut self, finalizer: F, k: K)
    where
        A: Clone + 'static,
        D: Start,
        F: FnOnce() -> D + Clone + 'static,
        K: Kont<A>,
    {
        self.link(Finally(Some((finalizer, k)), PhantomData))
    }

    /// A CONTINUATION UNWOUND: its levels are put back over the end of the unwinding — a frame of the top level,
    /// which what waits for the unwinding is under — and walked. What comes out of a level on to the one below
    /// it goes on unwinding.
    pub fn unwind(&mut self, k: Pieces) -> Node {
        let to = To::new(());
        self.link(End(to.clone()));
        for (i, level) in k.iter().enumerate() {
            if i > 0 {
                self.link(Unwinding(to.clone()));
            }
            let below = replace(&mut self.top, level.left(i == 0, &to));
            self.under.push(below);
        }
        // the frames are this path's alone now, if nobody else holds `k`
        drop(k);
        self.walk(&to)
    }

    /// UNWINDING, to its end `to`: the frames are passed with no value, the top first. A finalizer's frame: the
    /// finalizer runs, and the unwinding goes on after it (`Unwinding`). A level with no frame left: it is left
    /// without its `ret`, its state dropped. The end of another unwinding, begun inside this one, is passed.
    fn walk(&mut self, to: &To) -> Node {
        loop {
            match self.top.frames.take() {
                Some(mut frame) => {
                    self.top.frames = match frame.get_mut() {
                        Some(alone) => alone.head.0.take(),
                        None => frame.head.0.clone(),
                    };
                    if let Some(next) = frame.next.left(to, self) {
                        return next;
                    }
                }
                None => match self.under.pop() {
                    Some(below) => self.top = below,
                    // a continuation taken from a finalizer while it ran in an unwinding, by a handler inside the
                    // part unwound, was kept and resumed on another stack: the finalizer has ended, and there is
                    // no unwinding to go on with, and no value
                    None => panic!("the rest of an unwinding was resumed outside the unwinding"),
                },
            }
        }
    }

    /// A new level, of a handler: its program starts with nothing waiting inside it.
    pub fn enter(&mut self, mark: Rc<Mark>, state: State) {
        let below = replace(&mut self.top, Level { frames: None, handler: Some((mark, state)) });
        self.under.push(below);
    }

    /// the nearest handler of the operation: the top level's, or the highest under it
    #[inline(always)]
    fn nearest(&self, op: &Val) -> Option<usize> {
        match self.top.accepts(op) {
            true => Some(self.under.len()),
            false => self.under.iter().rposition(|level| level.accepts(op)),
        }
    }

    /// The operation's answer, if its nearest handler gives it in place: the program just goes on. If not, the
    /// operation back: what follows it is to be pushed, and it is to be given to the loop as an `Ask`.
    #[inline(always)]
    pub fn answered(&mut self, op: Val) -> Result<Val, Val> {
        let Some(at) = self.nearest(&op) else { return Err(op) };
        let level = if at == self.under.len() { &mut self.top } else { &mut self.under[at] };
        let (mark, state) = level.handler.as_mut().expect("the level found has a handler");
        match &mark.clause {
            Clause::InPlace(answer) => Ok(answer(state, op)),
            _ => Err(op),
        }
    }
}

/// A computation, given as its start, run to its value, or to an operation that no handler in it answers and the
/// rest of it.
pub fn run(start: impl FnOnce(&mut Machine) -> Node) -> Result<Val, (Val, Pieces)> {
    let mut m = Machine { top: Level::default(), under: Vec::new() };
    let first = start(&mut m);
    go(m, first)
}

/// THE LOOP. It is one function for every program: all it calls is the machine's own, or behind a pointer.
fn go(mut m: Machine, mut c: Node) -> Result<Val, (Val, Pieces)> {
    loop {
        c = match c {
            Node::Run(erased) => erased.start(&mut m),
            Node::Done(x) => match m.top.frames.take() {
                Some(mut frame) => match frame.get_mut() {
                    // a frame held by no continuation is unlinked, not counted, and what it keeps is not copied
                    Some(alone) => {
                        m.top.frames = alone.head.0.take();
                        alone.next.last(x, &mut m)
                    }
                    None => {
                        m.top.frames = frame.head.0.clone();
                        frame.next.again(x, &mut m)
                    }
                },
                None => match (m.top.handler.take(), m.under.pop()) {
                    (Some((mark, state)), below) => {
                        m.top = below.unwrap_or_default();
                        (mark.ret)(state, x, &mut m)
                    }
                    (None, Some(below)) => {
                        m.top = below;
                        Node::Done(x)
                    }
                    (None, None) => return Ok(x),
                },
            },
            Node::Ask(op) => {
                let Some(at) = m.nearest(&op) else {
                    m.under.push(m.top);
                    return Err((op, Shared::from_exact(m.under.len(), m.under.into_iter())));
                };
                let level = if at == m.under.len() { &mut m.top } else { &mut m.under[at] };
                let (mark, state) = level.handler.as_mut().expect("the level found has a handler");
                match &mark.clause {
                    Clause::InPlace(answer) => Node::Done(answer(state, op)),
                    Clause::General(on) => {
                        let on = on.clone();
                        let levels = m.under.len() - at + 1;
                        let k = Shared::from_exact(levels, m.under.drain(at..).chain(once(take(&mut m.top))));
                        m.top = m.under.pop().unwrap_or_default();
                        on(op, k, &mut m)
                    }
                    // the finalizer that asks ends here: what waits inside it is unwound with the rest
                    Clause::Gone(_, to) => {
                        let to = to.clone();
                        m.walk(&to)
                    }
                }
            }
            Node::Resume(k, x) => {
                // a level with nothing in it is not kept: a clause that resumes last, in a loop, takes no room
                if !m.top.is_empty() {
                    m.under.push(take(&mut m.top));
                }
                let (last, lower) = k.split_last().expect("a continuation has a level");
                m.under.extend(lower.iter().filter(|level| !level.is_empty()).cloned());
                m.top = last.clone();
                Node::Done(x)
            }
        }
    }
}

// Rust has no collector: a million frames waiting for a value that never comes would be dropped by recursion a
// million deep. So a chain of frames is dropped in a loop — each link is emptied before it is let go. (A chain of
// computations never run is dropped so too: erased.rs.)

impl Drop for Under {
    #[inline]
    fn drop(&mut self) {
        let mut next = self.0.take();
        // a frame that someone else holds stays, with all under it
        while let Some(alone) = next.as_mut().and_then(Shared::get_mut) {
            next = alone.head.0.take();
        }
    }
}
