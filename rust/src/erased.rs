//! A COMPUTATION OF A FORGOTTEN TYPE: what `.boxed()` makes. It is kept in place when it is no bigger than four
//! words, in a box of the pool when it is — and most steps of a loop are small: an operation, a number the closure
//! holds. A `Box<dyn ...>` would do, with an allocation at every turn of every loop.
//!
//! This is one of the kernel's three `unsafe` modules (with val.rs and pool.rs). It is sound because the room is
//! written once, in `new`, with a `T` that fits it in size and alignment — a `C`, or an `Owned<C>` — and the two
//! functions beside it are the ones made for that `T`, so the room is read only as that `T`; and it is read out at
//! most once: `start` empties the room and forgets it, and a drop runs only for a room that was not started. A
//! computation may hold an `Rc`, so the marker keeps it on its thread.

use std::cell::Cell;
use std::marker::PhantomData;
use std::mem::{ManuallyDrop, MaybeUninit};
use std::rc::Rc;

use crate::machine::{Machine, Node, Ret, Start};
use crate::pool::Owned;

type Room = MaybeUninit<[usize; 4]>;

pub struct Erased {
    /// what the room holds, by what is done with it. The functions are here, not behind a table: the loop goes
    /// through `start` at every turn
    start: unsafe fn(&mut Room, &mut Machine) -> Node,
    drop: unsafe fn(&mut Room),
    room: Room,
    here: PhantomData<Rc<()>>,
}

/// how a computation is got from what is kept
trait Kept<C> {
    fn open(self) -> C;
}

impl<C> Kept<C> for C {
    fn open(self) -> C {
        self
    }
}

impl<C> Kept<C> for Owned<C> {
    fn open(self) -> C {
        self.into_inner()
    }
}

/// SAFETY: a `T` fits the room
unsafe fn put<T>(kept: T) -> Room {
    let mut room = Room::uninit();
    unsafe { room.as_mut_ptr().cast::<T>().write(kept) };
    room
}

/// SAFETY: the room holds a `T`, and what it holds is not used after this
unsafe fn out<T>(room: &mut Room) -> T {
    unsafe { room.as_mut_ptr().cast::<T>().read() }
}

unsafe fn start_kept<C: Start, T: Kept<C>>(room: &mut Room, m: &mut Machine) -> Node {
    unsafe { out::<T>(room) }.open().start(Ret, m)
}

unsafe fn drop_kept<T>(room: &mut Room) {
    drop(unsafe { out::<T>(room) })
}

impl Erased {
    /// a `C` is kept as itself if it fits the room, as an `Owned<C>` — a box of the pool — if not
    const fn fits<C>() -> bool {
        size_of::<C>() <= size_of::<Room>() && align_of::<C>() <= align_of::<Room>()
    }

    #[inline]
    pub fn new<C: Start>(c: C) -> Erased {
        // SAFETY: what is put fits — a `C` by the test, an `Owned<C>` being one word — and the functions are its
        if Self::fits::<C>() {
            let room = unsafe { put(c) };
            Erased { start: start_kept::<C, C>, drop: drop_kept::<C>, room, here: PhantomData }
        } else {
            let room = unsafe { put(Owned::new(c)) };
            Erased { start: start_kept::<C, Owned<C>>, drop: drop_kept::<Owned<C>>, room, here: PhantomData }
        }
    }

    /// The computation started, with nothing after it but the level it is on.
    #[inline]
    pub fn start(self, m: &mut Machine) -> Node {
        // SAFETY: the function was chosen in `new` for what the room holds; `self` is forgotten, so what is read
        // out here is not dropped again
        let mut started = ManuallyDrop::new(self);
        unsafe { (started.start)(&mut started.room, m) }
    }
}

// Rust has no collector: a computation built by a loop and never run — a million binds, each erased, each holding
// the one before — would be dropped by recursion a million deep. So one that is dropped inside the drop of another
// waits in a list of its thread, and the outermost drop empties the list in a loop.

/// a room taken out of an `Erased` that is being dropped
#[derive(Clone, Copy)]
struct Left(unsafe fn(&mut Room), Room);

impl Left {
    /// SAFETY: once for a room, from the drop of the `Erased` it was taken out of — which does nothing else with it
    unsafe fn drop_now(mut self) {
        unsafe { (self.0)(&mut self.1) }
    }
}

thread_local! {
    /// what waits to be dropped, while a drop is going on; none: no drop is
    static WAITING: Cell<Option<Vec<Left>>> = const { Cell::new(None) };
}

/// The end of the outermost drop, also when it panics: what still waited then is forgotten, not dropped.
struct Outermost;

impl Drop for Outermost {
    fn drop(&mut self) {
        let _ = WAITING.try_with(|waiting| waiting.set(None));
    }
}

impl Drop for Erased {
    fn drop(&mut self) {
        let left = Left(self.drop, self.room);
        let outermost = WAITING.try_with(|waiting| match waiting.take() {
            Some(mut list) => {
                list.push(left);
                waiting.set(Some(list));
                false
            }
            None => {
                waiting.set(Some(Vec::new()));
                true
            }
        });
        // SAFETY (the three `drop_now`): each room is dropped once — this one here or, if it was put in the list,
        // by the loop of the drop it is inside; a room is taken out of the list before it is dropped
        match outermost {
            Ok(false) => {}
            // at a thread's end there is no list: recursion, as deep as it is
            Err(_) => unsafe { left.drop_now() },
            Ok(true) => {
                let _end = Outermost;
                let mut next = Some(left);
                while let Some(left) = next {
                    unsafe { left.drop_now() };
                    next = WAITING.with(|waiting| {
                        let mut list = waiting.take().unwrap_or_default();
                        let next = list.pop();
                        waiting.set(Some(list));
                        next
                    });
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::machine::run;
    use crate::{Comp, Dyn, pure};

    fn started<A: 'static>(erased: Erased) -> A {
        run(|m| erased.start(m)).ok().expect("a value").take()
    }

    #[test]
    fn a_computation_small_or_big_is_dropped_once_started_or_not() {
        let held = Rc::new(());
        let small = || Erased::new(pure::<(), _>(held.clone()));
        let big = || Erased::new(pure::<(), _>([held.clone(), held.clone(), held.clone(), held.clone(), held.clone()]));
        assert!(Erased::fits::<crate::Pure<(), Rc<()>>>() && !Erased::fits::<crate::Pure<(), [Rc<()>; 5]>>());
        assert_eq!(Rc::strong_count(&started::<Rc<()>>(small())), 2);
        assert_eq!(started::<[Rc<()>; 5]>(big()).len(), 5);
        assert_eq!(Rc::strong_count(&held), 1);
        let (not_started, nor_this) = (small(), big());
        assert_eq!(Rc::strong_count(&held), 7);
        drop((not_started, nor_this));
        assert_eq!(Rc::strong_count(&held), 1);
    }

    #[test]
    fn what_is_erased_already_stays_as_it_is() {
        let held = Rc::new(());
        let once: Dyn<(), Rc<()>> = pure(held.clone()).boxed();
        let twice = once.boxed().boxed();
        assert_eq!(Rc::strong_count(&twice.run()), 2);
    }

    #[test]
    fn one_inside_another_a_hundred_thousand_deep_is_dropped_in_a_loop_each_once() {
        let held = Rc::new(());
        let inner = held.clone();
        let chain = (0..100_000).fold(pure::<(), _>(0).boxed(), |inside, _| {
            let holds = inner.clone();
            // each is more than the room: it is in a box, and holds the one before
            inside.map(move |n: i32| n + Rc::strong_count(&holds) as i32).boxed()
        });
        assert_eq!(Rc::strong_count(&held), 100_002);
        drop(chain);
        assert_eq!(Rc::strong_count(&held), 2);
        // and the list is left as it was: the next drop is an outermost one again
        assert!(WAITING.with(|waiting| waiting.take().is_none()));
        drop(pure::<(), _>(inner).boxed().map(|held| held).boxed());
        assert_eq!(Rc::strong_count(&held), 1);
    }
}
