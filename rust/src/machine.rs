//! THE MACHINE: one loop. What waits for the value is a stack of LEVELS, one for each handler the program is under:
//! the frames inside the handler, the handler, its state. The top level is kept apart, in the loop's own variable.
//!
//!   Bind       it becomes a frame on the top level
//!   Done       the top frame takes the value; no frame left — the handler's `ret`, and the level is popped
//!   Handle     a new level
//!   Ask        the nearest handler of the operation is found. In place: it answers, the loop goes on. General:
//!              the levels above and at the handler are taken off as `k`, and the clause runs below them
//!   Resume     the levels of a `k` are put back on top
//!
//! A computation is run once: the machine takes its nodes by value, and a value is moved from where it is made
//! to where it is asked for, never copied. What may be run many times is a continuation, so what a `k` holds is
//! shared and never written: frames are immutable once linked, and a state shared with a `k` is copied before it
//! is written. Types are checked at the kernel's surface (lib.rs); in here values are of any type (val.rs).

use std::any::TypeId;
use std::iter::once;
use std::mem::{replace, take};
use std::rc::Rc;

use crate::pool::{Shared, With};
use crate::val::Val;

/// the state of a handler in place
pub(crate) type State = Rc<dyn std::any::Any>;
/// the levels of a continuation, the lowest first
pub(crate) type Pieces = Shared<[Level]>;

/// A computation.
pub(crate) enum Node {
    /// a value
    Done(Val),
    /// a question: an operation, to be answered
    Ask(Val),
    /// a computation, and what follows its value
    Bind(Shared<Frame>),
    /// a computation under a handler, with the handler's state
    Handle(Box<(Rc<Mark>, State, Node)>),
    /// a continuation taken earlier, resumed with an answer
    Resume(Pieces, Val),
}

/// What follows a value, inside one level: `next`, a function of the value. A bind is made as a frame not yet
/// linked: when the machine reaches it, `first` is taken to be run and the frames already waiting are put in
/// `rest`. Nobody else holds it until then; after, it is never written again. Its memory is the pool's (pool.rs):
/// a frame is let go when its value comes, and the next bind takes the room it leaves.
pub(crate) type Frame = With<Waits, dyn Fn(Val) -> Node>;

/// what a frame holds beside its function
pub(crate) struct Waits {
    first: Option<Node>,
    rest: Option<Shared<Frame>>,
}

pub(crate) fn bind(first: Node, next: impl Fn(Val) -> Node + 'static) -> Node {
    Node::Bind(Shared::new(Waits { first: Some(first), rest: None }, next))
}

/// A handler, as the machine sees it.
pub(crate) struct Mark {
    pub effect: TypeId,
    /// of the operations of the effect, those this handler answers; none: all of them
    pub mine: Option<Box<dyn Fn(&Val) -> bool>>,
    pub ret: Box<dyn Fn(State, Val) -> Node>,
    pub clause: Clause,
}

pub(crate) enum Clause {
    /// the answer, at once, from the handler's state
    InPlace(Box<dyn Fn(&mut State, Val) -> Val>),
    /// a computation, given the rest of the program
    General(Rc<dyn Fn(Val, Pieces) -> Node>),
}

impl Mark {
    fn accepts(&self, op: &Val) -> bool {
        self.effect == op.kind() && self.mine.as_ref().is_none_or(|mine| mine(op))
    }
}

#[derive(Clone, Default)]
pub(crate) struct Level {
    frames: Option<Shared<Frame>>,
    handler: Option<(Rc<Mark>, State)>,
}

impl Level {
    fn is_empty(&self) -> bool {
        self.frames.is_none() && self.handler.is_none()
    }

    fn accepts(&self, op: &Val) -> bool {
        self.handler.as_ref().is_some_and(|(mark, _)| mark.accepts(op))
    }
}

/// A computation run to its value, or to an operation that no handler in it answers and the rest of it.
pub(crate) fn run(start: Node) -> Result<Val, (Val, Pieces)> {
    let mut c = start;
    let mut top = Level::default();
    let mut under: Vec<Level> = Vec::new();
    loop {
        c = match c {
            Node::Bind(mut bind) => {
                let frame = &mut bind.get_mut().expect("a computation is run once").head;
                let first = frame.first.take().expect("a computation is run once");
                frame.rest = top.frames.take();
                top.frames = Some(bind);
                first
            }
            Node::Done(x) => match top.frames.take() {
                Some(mut frame) => {
                    // a frame held by no continuation is unlinked, not counted
                    top.frames = match frame.get_mut() {
                        Some(alone) => alone.head.rest.take(),
                        None => frame.head.rest.clone(),
                    };
                    (frame.next)(x)
                }
                None => match (top.handler.take(), under.pop()) {
                    (Some((mark, state)), below) => {
                        top = below.unwrap_or_default();
                        (mark.ret)(state, x)
                    }
                    (None, Some(below)) => {
                        top = below;
                        Node::Done(x)
                    }
                    (None, None) => return Ok(x),
                },
            },
            Node::Ask(op) => {
                // the nearest handler of the operation: the top level's, or the highest under it
                let found = match top.accepts(&op) {
                    true => Some(under.len()),
                    false => under.iter().rposition(|level| level.accepts(&op)),
                };
                let Some(at) = found else {
                    under.push(top);
                    return Err((op, Shared::from_exact(under.len(), under.into_iter())));
                };
                let level = if at == under.len() { &mut top } else { &mut under[at] };
                let (mark, state) = level.handler.as_mut().expect("the level found has a handler");
                match &mark.clause {
                    Clause::InPlace(answer) => Node::Done(answer(state, op)),
                    Clause::General(on) => {
                        let on = on.clone();
                        let levels = under.len() - at + 1;
                        let k = Shared::from_exact(levels, under.drain(at..).chain(once(take(&mut top))));
                        top = under.pop().unwrap_or_default();
                        on(op, k)
                    }
                }
            }
            Node::Handle(handle) => {
                let (mark, state, body) = *handle;
                under.push(replace(&mut top, Level { frames: None, handler: Some((mark, state)) }));
                body
            }
            Node::Resume(k, x) => {
                // a level with nothing in it is not kept: a clause that resumes last, in a loop, takes no room
                if !top.is_empty() {
                    under.push(take(&mut top));
                }
                let (last, lower) = k.split_last().expect("a continuation has a level");
                under.extend(lower.iter().filter(|level| !level.is_empty()).cloned());
                top = last.clone();
                Node::Done(x)
            }
        }
    }
}

// Rust has no collector: a chain of a million binds, or of a million frames, would be dropped by recursion a
// million deep. So a chain is dropped in a loop — each link is emptied before it is let go.

impl Drop for Waits {
    #[inline]
    fn drop(&mut self) {
        // a frame that gave its value holds nothing: most do
        if self.first.is_some() || self.rest.is_some() {
            self.drop_chains()
        }
    }
}

impl Waits {
    fn drop_chains(&mut self) {
        // a chain is followed link by link; `more` takes memory only where a frame holds two chains
        let mut more = Vec::new();
        let mut next = self.unlink(&mut more);
        while let Some(mut frame) = next.or_else(|| more.pop()) {
            next = frame.get_mut().expect("held by nobody else").head.unlink(&mut more);
        }
    }

    /// the frames that only this one holds, taken out of it: one is given, a second is put in `more`
    fn unlink(&mut self, more: &mut Vec<Shared<Frame>>) -> Option<Shared<Frame>> {
        let rest = self.rest.take_if(|rest| rest.alone());
        match self.first.take_if(|first| matches!(first, Node::Bind(_))) {
            Some(Node::Bind(first)) => {
                more.extend(rest);
                Some(first)
            }
            _ => rest,
        }
    }
}
