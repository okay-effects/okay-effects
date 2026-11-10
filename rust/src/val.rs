//! A VALUE OF ANY TYPE, kept in place when it is no bigger than two words, in a box when it is. Every operation,
//! every answer and every value a frame waits for passes through the machine as one of these. A `Box<dyn Any>`
//! would do, and did: but most values are small, and a box for each was more than half of the machine's
//! allocations. Measured with examples/bench.rs when every bind was a frame, boxes against this: `state` 35 → 25 ns
//! an operation, `reader3` 31 → 22, `choose` 275 → 238, `binds` 24 → 17. (Three words, to keep a `Vec` in place,
//! were tried on the machine of today: `state` 5.7 → 6.5, `choose` the same.)
//!
//! The kernel's `unsafe` is here, in erased.rs and in pool.rs, whose boxes these are. This is sound because the
//! room is written once, in `new`, with a `T` that fits it in size and alignment, and `kind` says which; it is
//! read only as that `T` — `take` and `get` compare the type first, `drop` is the function made for it; and it is
//! read out at most once — `take` forgets the value it emptied, so `drop` does not see it. A value may hold an
//! `Rc`, so the marker keeps it on its thread.

use std::any::TypeId;
use std::marker::PhantomData;
use std::mem::{MaybeUninit, forget};
use std::rc::Rc;

use crate::pool::Owned;

type Room = MaybeUninit<[usize; 2]>;

pub struct Val {
    kind: &'static Kind,
    room: Room,
    here: PhantomData<Rc<()>>,
}

/// What a room holds: a value of the type `id`, dropped by `drop`.
struct Kind {
    id: TypeId,
    drop: unsafe fn(&mut Room),
}

struct Of<A>(PhantomData<A>);

impl<A: 'static> Of<A> {
    /// an `A` is kept as itself if it fits the room, as an `Owned<A>` — a box of the pool — if not
    const FITS: bool = size_of::<A>() <= size_of::<Room>() && align_of::<A>() <= align_of::<Room>();
    const KIND: &'static Kind =
        &Kind { id: TypeId::of::<A>(), drop: if Self::FITS { drop_kept::<A> } else { drop_kept::<Owned<A>> } };
}

/// SAFETY: a `T` fits the room: it is an `A` that `FITS`, or an `Owned<A>`, which is one word
unsafe fn put<T>(kept: T) -> Room {
    let mut room = Room::uninit();
    unsafe { room.as_mut_ptr().cast::<T>().write(kept) };
    room
}

/// SAFETY: the room holds a `T`, and what it holds is not used after this
unsafe fn out<T>(room: &mut Room) -> T {
    unsafe { room.as_mut_ptr().cast::<T>().read() }
}

unsafe fn drop_kept<T>(room: &mut Room) {
    drop(unsafe { out::<T>(room) })
}

impl Val {
    pub fn new<A: 'static>(a: A) -> Val {
        // SAFETY: what is put fits, by the test
        let room = if Of::<A>::FITS { unsafe { put(a) } } else { unsafe { put(Owned::new(a)) } };
        Val { kind: Of::<A>::KIND, room, here: PhantomData }
    }

    /// the type of the value
    pub fn kind(&self) -> TypeId {
        self.kind.id
    }

    pub fn get<A: 'static>(&self) -> Option<&A> {
        if self.kind.id != TypeId::of::<A>() {
            return None;
        }
        // SAFETY: the room holds an `A` or an `Owned<A>`, as `new` chose by the same test
        Some(if Of::<A>::FITS {
            unsafe { &*self.room.as_ptr().cast::<A>() }
        } else {
            unsafe { &**self.room.as_ptr().cast::<Owned<A>>() }
        })
    }

    pub fn take<A: 'static>(mut self) -> A {
        assert!(self.kind.id == TypeId::of::<A>(), "a value of the type its place in the program says");
        // SAFETY: as in `get`; and `self` is forgotten, so what is read out here is not dropped again
        let a = if Of::<A>::FITS {
            unsafe { out::<A>(&mut self.room) }
        } else {
            unsafe { out::<Owned<A>>(&mut self.room) }.into_inner()
        };
        forget(self);
        a
    }
}

impl Drop for Val {
    fn drop(&mut self) {
        // SAFETY: `kind` was made in `new` for what the room holds
        unsafe { (self.kind.drop)(&mut self.room) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_value_small_or_big_is_dropped_once_taken_or_not() {
        let held = Rc::new(());
        let small = || Val::new(held.clone());
        let big = || Val::new([held.clone(), held.clone(), held.clone()]);
        assert_eq!(Rc::strong_count(&small().take::<Rc<()>>()), 2);
        assert_eq!(big().take::<[Rc<()>; 3]>().len(), 3);
        assert!(small().get::<Rc<()>>().is_some() && small().get::<u8>().is_none());
        assert!(big().get::<[Rc<()>; 3]>().is_some_and(|all| Rc::ptr_eq(&all[2], &held)));
        assert_eq!(big().kind(), TypeId::of::<[Rc<()>; 3]>());
        Val::new(()).take::<()>();
        assert_eq!(Rc::strong_count(&held), 1);
    }
}
