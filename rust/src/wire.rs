//! THE OPERATION THAT CROSSES a process: a name and arguments in JSON, answered in JSON. A program written over it
//! runs in one process, in one language; its handlers may be in another. Only this data crosses — a continuation
//! stays where it was made, known outside by a number. The messages are the okay wire's (wire/README.md).
//!
//!   escapes     what JSON cannot say of a value, said by tagged objects
//!   cbor        the other format of a message
//!   deflate     its compression
//!   auth        a secret proved by both sides, never sent
//!   link        one end of the wire: lines, or frames
//!   worker      the far side: a module served to a host
//!   remote      the host's side: a far side as an effect

use crate::json::Json;
use crate::{Has, Prog, Reply, perform};

pub mod auth;
pub mod cbor;
pub mod deflate;
pub mod escapes;
mod link;
mod remote;
mod worker;

pub use link::Link;
pub use remote::{Asking, Failing, Far, Opening, Remote, Streaming};
pub use worker::{Fed, Function, Module, Row, Thing, serve};

pub enum Foreign {
    /// a name and arguments
    Call(String, Vec<Json>, Reply<Json>),
}

pub fn call<R: Has<Foreign, I>, I>(name: &str, args: Vec<Json>) -> impl Prog<Json, R> + use<R, I> {
    perform(|r| Foreign::Call(name.to_string(), args, r))
}

/// A failure that crosses, by name: raised on one side, handled on the other (`Throws<Condition>`).
#[derive(Clone, Debug, PartialEq)]
pub struct Condition {
    pub kind: String,
    pub message: String,
}

impl Condition {
    pub fn new(kind: &str, message: impl Into<String>) -> Condition {
        Condition { kind: kind.to_string(), message: message.into() }
    }
}

/// the wire's version: a host refuses any other
pub const SHIM: i64 = 9;
