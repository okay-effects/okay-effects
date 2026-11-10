//! Algebraic effects and handlers: a computation is data, a handler is a fold over it, one small machine runs it.
//! The same model as the Scala and the Java kernels beside this one; the same programs come to the same answers.
//!
//! Rust has no type for "an operation answered by an X" as one sealed family, so an operation is a type of its own
//! (`impl Op for Get { type Answer = i64; }`) and a handler is a list of typed clauses, one per operation. Values
//! travel through the machine as `Rc<dyn Any>`: a continuation is a value that may be resumed many times, so what
//! it holds is shared, and what a program answers is `Clone`.

use std::any::{Any, TypeId, type_name};
use std::fmt;
use std::marker::PhantomData;
use std::rc::Rc;

pub mod effects;
pub mod json;
pub mod wire;

/// An operation: a question as data, answered by an `Answer`.
pub trait Op: 'static {
    type Answer: Clone + 'static;
}

type Val = Rc<dyn Any>;
type Next = Rc<dyn Fn(Val) -> Node>;

/// An operation asked: itself, and the name of its type.
#[derive(Clone)]
struct Asking {
    op: Rc<dyn Any>,
    name: &'static str,
}

/// A computation. Only data: nothing runs until `step`.
#[derive(Clone)]
enum Node {
    /// a value
    Done(Val),
    /// a question: an operation, to be answered
    Ask(Asking),
    /// a computation, and what follows its value
    Bind(Rc<Node>, Next),
    /// a computation, and a continuation taken earlier put back after it
    Plug(Rc<Node>, Rest),
}

/// A continuation as data: what waits for a value. Shared, so it may be resumed never, once or many times.
type Rest = Rc<Frames>;

enum Frames {
    End,
    Then(Next, Rest),
    Both(Rest, Rest),
}

thread_local! {
    static END: Rest = Rc::new(Frames::End);
    static UNIT: Rc<Node> = Rc::new(Node::Done(Rc::new(())));
}

fn end() -> Rest {
    END.try_with(Rc::clone).unwrap_or_else(|_| Rc::new(Frames::End))
}

fn unit() -> Rc<Node> {
    UNIT.try_with(Rc::clone).unwrap_or_else(|_| Rc::new(Node::Done(Rc::new(()))))
}

/// THE MACHINE: a computation run to its next question, in a loop — constant stack, whatever the program.
fn machine(start: Node) -> (Result<Val, Asking>, Rest) {
    let mut c = start;
    let mut k = end();
    loop {
        c = match &c {
            Node::Ask(asking) => return (Err(asking.clone()), k),
            Node::Bind(first, next) => {
                k = Rc::new(Frames::Then(next.clone(), k));
                (**first).clone()
            }
            Node::Plug(first, rest) => {
                k = if matches!(*k, Frames::End) { rest.clone() } else { Rc::new(Frames::Both(rest.clone(), k)) };
                (**first).clone()
            }
            Node::Done(x) => match &*k.clone() {
                Frames::End => return (Ok(x.clone()), k),
                Frames::Then(next, rest) => {
                    k = rest.clone();
                    next(x.clone())
                }
                Frames::Both(first, rest) => match &**first {
                    Frames::End => {
                        k = rest.clone();
                        continue;
                    }
                    Frames::Then(next, more) => {
                        k = Rc::new(Frames::Both(more.clone(), rest.clone()));
                        next(x.clone())
                    }
                    Frames::Both(a, b) => {
                        k = Rc::new(Frames::Both(a.clone(), Rc::new(Frames::Both(b.clone(), rest.clone()))));
                        continue;
                    }
                },
            },
        }
    }
}

// Rust has no collector: a chain of a million binds, or of a million frames, would be dropped by recursion a
// million deep. So a chain is dropped in a loop — each link is emptied before it is let go.

impl Drop for Node {
    fn drop(&mut self) {
        let mut next = self.take_first();
        while let Some(first) = next {
            next = Rc::try_unwrap(first).ok().and_then(|mut node| node.take_first());
        }
    }
}

impl Node {
    fn take_first(&mut self) -> Option<Rc<Node>> {
        match self {
            Node::Bind(first, _) | Node::Plug(first, _) => Some(std::mem::replace(first, unit())),
            _ => None,
        }
    }
}

impl Drop for Frames {
    fn drop(&mut self) {
        let mut next = self.take_rest();
        while let Some(rest) = next {
            next = Rc::try_unwrap(rest).ok().and_then(|mut frames| frames.take_rest());
        }
    }
}

impl Frames {
    fn take_rest(&mut self) -> Option<Rest> {
        match self {
            Frames::Then(_, rest) | Frames::Both(_, rest) => Some(std::mem::replace(rest, end())),
            Frames::End => None,
        }
    }
}

fn value<A: Clone + 'static>(v: &Val) -> A {
    v.downcast_ref::<A>().unwrap_or_else(|| panic!("a value of another type than {}", type_name::<A>())).clone()
}

/// A computation of an `A`.
pub struct Comp<A> {
    node: Node,
    of: PhantomData<fn() -> A>,
}

impl<A> Clone for Comp<A> {
    fn clone(&self) -> Self {
        Comp { node: self.node.clone(), of: PhantomData }
    }
}

fn comp<A>(node: Node) -> Comp<A> {
    Comp { node, of: PhantomData }
}

pub fn pure<A: Clone + 'static>(a: A) -> Comp<A> {
    comp(Node::Done(Rc::new(a)))
}

pub fn perform<O: Op>(op: O) -> Comp<O::Answer> {
    comp(Node::Ask(Asking { op: Rc::new(op), name: type_name::<O>() }))
}

/// A computation built when it is reached: a call in tail position takes no stack.
pub fn defer<A: 'static>(p: impl Fn() -> Comp<A> + 'static) -> Comp<A> {
    comp(Node::Bind(Rc::new(Node::Done(Rc::new(()))), Rc::new(move |_| p().node)))
}

impl<A: Clone + 'static> Comp<A> {
    pub fn flat_map<B: 'static>(self, f: impl Fn(A) -> Comp<B> + 'static) -> Comp<B> {
        comp(Node::Bind(Rc::new(self.node), Rc::new(move |x| f(value(&x)).node)))
    }

    pub fn map<B: Clone + 'static>(self, f: impl Fn(A) -> B + 'static) -> Comp<B> {
        self.flat_map(move |a| pure(f(a)))
    }

    /// The computation after this one, whose value is not needed.
    pub fn then<B: 'static>(self, next: impl Fn() -> Comp<B> + 'static) -> Comp<B> {
        self.flat_map(move |_| next())
    }

    /// Where the computation comes to: its value, or an operation it asks and the rest of it.
    pub fn step(self) -> Step<A> {
        match machine(self.node) {
            (Ok(x), _) => Step::Done(value(&x)),
            (Err(asking), k) => Step::Asked(Asked { asking, k, of: PhantomData }),
        }
    }

    /// A program with nothing left to ask, run to its value.
    pub fn run(self) -> Result<A, NoHandler> {
        match self.step() {
            Step::Done(a) => Ok(a),
            Step::Asked(asked) => Err(NoHandler { op: asked.asking.name }),
        }
    }
}

pub enum Step<A> {
    Done(A),
    Asked(Asked<A>),
}

/// An operation asked by a program of an `A`, and the rest of the program.
pub struct Asked<A> {
    asking: Asking,
    k: Rest,
    of: PhantomData<fn() -> A>,
}

impl<A> Asked<A> {
    /// The name of the operation's type.
    pub fn name(&self) -> &'static str {
        self.asking.name
    }

    /// The operation, if it is an `O`.
    pub fn op<O: Op>(&self) -> Option<&O> {
        self.asking.op.downcast_ref::<O>()
    }

    /// The rest resumed with the answer of the operation, an `O`.
    pub fn resume<O: Op>(&self, answer: O::Answer) -> Comp<A> {
        assert!(self.asking.op.is::<O>(), "not the operation asked: {}", type_name::<O>());
        comp(Node::Plug(Rc::new(Node::Done(Rc::new(answer))), self.k.clone()))
    }
}

/// An operation that reached the top: its effect has no handler.
#[derive(Debug, PartialEq)]
pub struct NoHandler {
    pub op: &'static str,
}

impl fmt::Display for NoHandler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "an operation with no handler: {}", self.op)
    }
}

impl std::error::Error for NoHandler {}

type Clause = Rc<dyn Fn(&Asking, Next) -> Node>;

/// The answers of a handler, in a program of an `A`: a `B`. A clause for each operation it answers.
pub struct Handler<A, B> {
    ret: Rc<dyn Fn(A) -> Comp<B>>,
    clauses: Vec<(TypeId, Clause)>,
}

impl<A, B> Clone for Handler<A, B> {
    fn clone(&self) -> Self {
        Handler { ret: self.ret.clone(), clauses: self.clauses.clone() }
    }
}

impl<A: Clone + 'static, B: 'static> Handler<A, B> {
    pub fn new(ret: impl Fn(A) -> Comp<B> + 'static) -> Self {
        Handler { ret: Rc::new(ret), clauses: Vec::new() }
    }

    /// The clause of the operation `O`: the operation, and the rest of the program.
    pub fn on<O: Op>(mut self, clause: impl Fn(&O, K<O, B>) -> Comp<B> + 'static) -> Self {
        let erased: Clause = Rc::new(move |asking, next| {
            let op = asking.op.downcast_ref::<O>().expect("a clause is chosen by its operation's type");
            clause(op, K { asking: asking.clone(), next, of: PhantomData }).node
        });
        self.clauses.push((TypeId::of::<O>(), erased));
        self
    }

    /// THE FOLD: the operations of this handler answered by it, every other one asked again outside.
    pub fn handle(&self, p: Comp<A>) -> Comp<B> {
        let h = self.clone();
        defer(move || match p.clone().step() {
            Step::Done(a) => (h.ret)(a),
            Step::Asked(asked) => {
                let (again, k) = (h.clone(), asked.k.clone());
                let next: Next =
                    Rc::new(move |x| again.handle(comp(Node::Plug(Rc::new(Node::Done(x)), k.clone()))).node);
                let asking = asked.asking;
                match h.clauses.iter().find(|(id, _)| *id == (*asking.op).type_id()) {
                    Some((_, clause)) => comp(clause(&asking, next)),
                    None => comp(Node::Bind(Rc::new(Node::Ask(asking)), next)),
                }
            }
        })
    }
}

/// The rest of a handled program, waiting for the answer of the operation `O` it asked.
pub struct K<O: Op, B> {
    asking: Asking,
    next: Next,
    of: PhantomData<fn(O) -> B>,
}

impl<O: Op, B> Clone for K<O, B> {
    fn clone(&self) -> Self {
        K { asking: self.asking.clone(), next: self.next.clone(), of: PhantomData }
    }
}

impl<O: Op, B> K<O, B> {
    /// The rest resumed with the answer.
    pub fn resume(&self, answer: O::Answer) -> Comp<B> {
        comp((self.next)(Rc::new(answer)))
    }

    /// The operation asked again outside, the rest resumed with what is answered there: interception, audit.
    pub fn pass(&self) -> Comp<B> {
        comp(Node::Bind(Rc::new(Node::Ask(self.asking.clone())), self.next.clone()))
    }
}
