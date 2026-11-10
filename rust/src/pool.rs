//! A POOL: memory for the machine's frames, taken from the thread's own lists of free blocks before the system is
//! asked. A frame lives like a stack entry — made where a value is waited for, let go when it comes — so the block
//! one frame leaves is the block the next one takes, and the system's allocator is not called. `Shared` is an `Rc`
//! whose memory comes from here; `Owned` is such a `Box`. A continuation's levels, a value too big to be kept in
//! place (val.rs) and a computation too big to be (erased.rs) are held so too. Straight-line code takes no memory
//! at all; where continuations are taken it is worth having: `choose` of examples/bench.rs, the system's allocator
//! against this, 253 → 185 ns an operation, 11.75 → 3.75 calls of the system.
//!
//! The crate's `unsafe` is here, in val.rs and in erased.rs. It is sound because:
//!   * a block of a class is always got from the system with that class's layout and given back to it with the
//!     same one, whichever way it goes: a free list only holds blocks in between. A block is in a list or in use,
//!     never both: `take` unlinks it before it is written, `give` is called once, by the last owner's `drop`.
//!   * `Shared` counts its owners as `Rc` does: the value is dropped and the block given back when the last one
//!     goes, and `get_mut` gives a `&mut` only to an owner that is alone. `Owned` has one owner.
//!   * the pointers are not `Send`: the counts and the lists are plain cells of one thread.
//!   * at a thread's end its lists are emptied and closed; a block let go after that goes back to the system.

use std::alloc::{Layout, alloc, dealloc, handle_alloc_error};
use std::cell::Cell;
use std::marker::PhantomData;
use std::mem::forget;
use std::ops::Deref;
use std::ptr::{self, NonNull};

use crate::machine::Frame;

/// a class is a size: a multiple of this, which is also the alignment of every block
const STEP: usize = 16;
/// the classes: up to 256 bytes; what is bigger, or aligned to more, goes to the system
const CLASSES: usize = 16;
/// free blocks kept in a class at most: a deep recursion gives its memory back
const KEPT: usize = 4096;

/// The free blocks of one class: each holds the next in its first word. `kept` is how many; `KEPT` when full —
/// or closed, at the thread's end, with none in it.
struct Class {
    free: Cell<*mut u8>,
    kept: Cell<usize>,
}

/// What empties the lists at the thread's end. It is apart from them so that they have no destructor: then they
/// are reached without a check, and are there for a value dropped after this.
struct AtExit;

thread_local! {
    static CLASSES_FREE: [Class; CLASSES] =
        const { [const { Class { free: Cell::new(ptr::null_mut()), kept: Cell::new(0) } }; CLASSES] };
    static AT_EXIT: AtExit = const { AtExit };
}

/// The lists emptied, and closed: what is let go from now on goes to the system.
fn close() {
    CLASSES_FREE.with(|classes| {
        for (class, list) in classes.iter().enumerate() {
            let mut block = list.free.replace(ptr::null_mut());
            list.kept.set(KEPT);
            while !block.is_null() {
                // SAFETY: a free block holds the next one in its first word; it came from `fresh(block_of(class))`
                block = unsafe {
                    let next = block.cast::<*mut u8>().read();
                    dealloc(block, block_of(class));
                    next
                };
            }
        }
    })
}

impl Drop for AtExit {
    fn drop(&mut self) {
        close()
    }
}

/// the class of a layout, if it has one
#[inline]
fn class_of(layout: Layout) -> Option<usize> {
    (layout.size() <= STEP * CLASSES && layout.align() <= STEP).then(|| layout.size().max(1).div_ceil(STEP) - 1)
}

fn block_of(class: usize) -> Layout {
    Layout::from_size_align(STEP * (class + 1), STEP).expect("a small layout")
}

/// a layout the system takes: not of size zero
fn real(layout: Layout) -> Layout {
    Layout::from_size_align(layout.size().max(1), layout.align()).expect("a layout as valid as the one given")
}

fn fresh(layout: Layout) -> NonNull<u8> {
    // SAFETY: the layout is not of size zero
    NonNull::new(unsafe { alloc(layout) }).unwrap_or_else(|| handle_alloc_error(layout))
}

/// A block of the class from the system, its list being empty.
#[cold]
fn fresh_of(class: usize) -> NonNull<u8> {
    // what is taken now may come back to the list: there must be someone to empty it, or it is closed
    if AT_EXIT.try_with(|_| ()).is_err() {
        close();
    }
    fresh(block_of(class))
}

/// Memory for the layout, not written yet.
#[inline]
fn take(layout: Layout) -> NonNull<u8> {
    let Some(class) = class_of(layout) else { return fresh(real(layout)) };
    let block = CLASSES_FREE.with(|classes| {
        let list = &classes[class];
        let block = list.free.get();
        if !block.is_null() {
            // SAFETY: a free block holds the next one in its first word
            list.free.set(unsafe { block.cast::<*mut u8>().read() });
            list.kept.set(list.kept.get() - 1);
        }
        block
    });
    NonNull::new(block).unwrap_or_else(|| fresh_of(class))
}

/// SAFETY: the memory was given by `take` for the same layout; it is not used after this
#[inline]
unsafe fn give(memory: NonNull<u8>, layout: Layout) {
    let Some(class) = class_of(layout) else { return unsafe { dealloc(memory.as_ptr(), real(layout)) } };
    let kept = CLASSES_FREE.with(|classes| {
        let list = &classes[class];
        let kept = list.kept.get();
        if kept < KEPT {
            // SAFETY: a block is at least a word, aligned to more than a word, and nobody uses it
            unsafe { memory.cast::<*mut u8>().write(list.free.get()) };
            list.free.set(memory.as_ptr());
            list.kept.set(kept + 1);
        }
        kept
    });
    if kept >= KEPT {
        // SAFETY: `take` got it from the system with this layout
        unsafe { dealloc(memory.as_ptr(), block_of(class)) }
    }
}

/// A value in memory of the pool, with the count of its owners. The layout is C's: a slice's is worked out.
#[repr(C)]
struct Slot<T: ?Sized> {
    owners: Cell<usize>,
    value: T,
}

/// the value written into memory of the pool, owned once
fn slot<T>(value: T) -> NonNull<Slot<T>> {
    let slot = take(Layout::new::<Slot<T>>()).cast::<Slot<T>>();
    // SAFETY: the memory is of the layout of a `Slot<T>`, and nobody else has it
    unsafe { slot.write(Slot { owners: Cell::new(1), value }) };
    slot
}

/// A head and a function: what a `Shared` can hold with the function's type forgotten.
pub struct With<H, F: ?Sized> {
    pub head: H,
    pub next: F,
}

/// A value with many owners on one thread, as an `Rc`.
pub struct Shared<T: ?Sized> {
    slot: NonNull<Slot<T>>,
    owns: PhantomData<Slot<T>>,
}

impl<H> Shared<With<H, dyn Frame>> {
    pub fn new(head: H, next: impl Frame + 'static) -> Self {
        Shared { slot: slot(With { head, next }), owns: PhantomData }
    }
}

/// A slice being written: its first values, and the memory they are in.
struct Half<T> {
    first: *mut T,
    written: usize,
    memory: NonNull<u8>,
    layout: Layout,
}

impl<T> Drop for Half<T> {
    fn drop(&mut self) {
        // SAFETY: the first `written` values were written and nobody else has them; the memory was taken
        // with this layout and is given back once
        unsafe {
            ptr::drop_in_place(ptr::slice_from_raw_parts_mut(self.first, self.written));
            give(self.memory, self.layout);
        }
    }
}

impl<T> Shared<[T]> {
    /// The `len` values of `values`, which gives that many.
    pub fn from_exact(len: usize, values: impl Iterator<Item = T>) -> Self {
        let values_layout = Layout::array::<T>(len).expect("a slice no bigger than memory");
        let (layout, _) = Layout::new::<Cell<usize>>().extend(values_layout).expect("a slice no bigger than memory");
        let layout = layout.pad_to_align();
        let memory = take(layout);
        // a pointer to a slot of `len` values: the layout taken is this one's, by the rule of C
        let slot = ptr::slice_from_raw_parts_mut(memory.as_ptr(), len) as *mut Slot<[T]>;
        // SAFETY: the place of a field in memory that is this slot's
        let first = unsafe { (&raw mut (*slot).value).cast::<T>() };
        // until every value is written the slot is not a slice yet: if `values` panics or gives too few, what
        // is written is dropped and the memory given back
        let mut half = Half { first, written: 0, memory, layout };
        for value in values.take(len) {
            // SAFETY: there is room for `len` values, and this is one of the first `len`
            unsafe { first.add(half.written).write(value) };
            half.written += 1;
        }
        assert!(half.written == len, "as many values as said");
        std::mem::forget(half);
        // SAFETY: the values are all written, and the count is, here
        unsafe {
            (&raw mut (*slot).owners).write(Cell::new(1));
            Shared { slot: NonNull::new_unchecked(slot), owns: PhantomData }
        }
    }
}

impl<T: ?Sized> Shared<T> {
    fn slot(&self) -> &Slot<T> {
        // SAFETY: the slot is alive while it has an owner, and this is one
        unsafe { self.slot.as_ref() }
    }

    /// no other owner
    pub fn alone(&self) -> bool {
        self.slot().owners.get() == 1
    }

    /// The value, to be written, if this owner is alone.
    pub fn get_mut(&mut self) -> Option<&mut T> {
        // SAFETY: there is no other owner to read it, and this one is borrowed for as long
        self.alone().then(|| unsafe { &mut (*self.slot.as_ptr()).value })
    }
}

impl<T: ?Sized> Deref for Shared<T> {
    type Target = T;

    fn deref(&self) -> &T {
        &self.slot().value
    }
}

impl<T: ?Sized> Clone for Shared<T> {
    fn clone(&self) -> Self {
        let owners = &self.slot().owners;
        owners.set(owners.get().checked_add(1).expect("fewer owners than a count holds"));
        Shared { slot: self.slot, owns: PhantomData }
    }
}

impl<T: ?Sized> Drop for Shared<T> {
    #[inline]
    fn drop(&mut self) {
        let owners = self.slot().owners.get();
        if owners > 1 {
            return self.slot().owners.set(owners - 1);
        }
        let layout = Layout::for_value(self.slot());
        // SAFETY: this was the last owner: the value is dropped once, then its memory, taken for this layout, is
        // given back. If the value's drop panics, the memory is only lost.
        unsafe {
            ptr::drop_in_place(&raw mut (*self.slot.as_ptr()).value);
            give(self.slot.cast(), layout)
        }
    }
}

/// A value with one owner, as a `Box`.
pub struct Owned<T> {
    slot: NonNull<Slot<T>>,
    owns: PhantomData<Slot<T>>,
}

impl<T> Owned<T> {
    pub fn new(value: T) -> Self {
        Owned { slot: slot(value), owns: PhantomData }
    }

    pub fn into_inner(self) -> T {
        // SAFETY: the value is read out once — `self` is forgotten, so it is not dropped — then the memory, taken
        // for this layout, is given back
        let value = unsafe { (&raw const (*self.slot.as_ptr()).value).read() };
        unsafe { give(self.slot.cast(), Layout::new::<Slot<T>>()) };
        forget(self);
        value
    }
}

impl<T> Deref for Owned<T> {
    type Target = T;

    fn deref(&self) -> &T {
        // SAFETY: the slot is alive while it has its owner
        unsafe { &self.slot.as_ref().value }
    }
}

impl<T> Drop for Owned<T> {
    fn drop(&mut self) {
        // SAFETY: as the last owner of a `Shared`
        unsafe {
            ptr::drop_in_place(&raw mut (*self.slot.as_ptr()).value);
            give(self.slot.cast(), Layout::new::<Slot<T>>())
        }
    }
}

#[cfg(test)]
mod tests {
    use std::rc::Rc;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::thread;

    use super::*;
    use crate::machine::{Machine, Node, run};
    use crate::val::Val;

    fn kept(class: usize) -> usize {
        CLASSES_FREE.with(|classes| classes[class].kept.get())
    }

    fn place<T>(owned: &Owned<T>) -> usize {
        &raw const **owned as usize
    }

    #[test]
    fn a_block_let_go_is_the_next_one_taken_in_its_class_only() {
        let first = Owned::new([1u64; 5]);
        let at = place(&first);
        drop(first);
        let other_class = Owned::new([2u64; 9]);
        assert_ne!(place(&other_class), at);
        let second = Owned::new([3u64; 5]);
        assert_eq!((place(&second), *second), (at, [3; 5]));
        // two alive at once are two blocks
        let third = Owned::new([4u64; 5]);
        assert_ne!(place(&third), at);
        assert_eq!((*second, *third, *other_class), ([3; 5], [4; 5], [2; 9]));
    }

    /// a frame that adds what it holds to the value it is given
    struct Adds(Rc<i32>);

    impl Frame for Adds {
        fn last(&mut self, x: Val, m: &mut Machine) -> Node {
            self.again(x, m)
        }

        fn again(&self, x: Val, _: &mut Machine) -> Node {
            Node::Done(Val::new(x.take::<i32>() + *self.0))
        }
    }

    fn given(frame: &Shared<With<Rc<()>, dyn Frame>>, x: i32) -> i32 {
        run(|m| frame.next.again(Val::new(x), m)).ok().expect("a value").take()
    }

    #[test]
    fn a_value_is_dropped_once_when_its_last_owner_goes() {
        let held = Rc::new(());
        let mut one = Shared::new(held.clone(), Adds(Rc::new(1)));
        assert!(one.alone() && one.get_mut().is_some());
        let two = one.clone();
        assert!(!one.alone() && one.get_mut().is_none());
        assert_eq!((given(&one, 1), given(&two, 2), Rc::strong_count(&two.head)), (2, 3, 2));
        drop(one);
        assert_eq!(Rc::strong_count(&held), 2);
        let mut two = two;
        *two.get_mut().map(|alone| &mut alone.head).expect("alone again") = held.clone();
        assert_eq!(Rc::strong_count(&held), 2);
        drop(two);
        assert_eq!(Rc::strong_count(&held), 1);
    }

    #[test]
    fn what_a_function_holds_is_dropped_with_it() {
        let held = Rc::new(5);
        let shared = Shared::new(Rc::new(()), Adds(held.clone()));
        assert_eq!((given(&shared, 1), Rc::strong_count(&held)), (6, 2));
        drop(shared);
        assert_eq!(Rc::strong_count(&held), 1);
    }

    #[test]
    fn an_owned_value_is_dropped_once_taken_out_or_not() {
        let held = Rc::new(());
        let owned = Owned::new([held.clone(), held.clone()]);
        assert_eq!(Rc::strong_count(&owned[1]), 3);
        let out = owned.into_inner();
        assert_eq!(Rc::strong_count(&held), 3);
        drop(out);
        drop(Owned::new(held.clone()));
        assert_eq!(Rc::strong_count(&held), 1);
    }

    #[repr(align(64))]
    #[derive(Clone, Copy, PartialEq, Debug)]
    struct Wide(u8);

    #[test]
    fn sizes_and_alignments_small_and_big_each_in_its_place() {
        let nothing = Owned::new(());
        let byte = Owned::new(7u8);
        let sixteen = Owned::new(u128::MAX);
        let wide = Owned::new(Wide(9));
        let big = Owned::new([11u64; 100]);
        let edge = Owned::new([13u8; STEP * CLASSES - 8]);
        let over = Owned::new([15u8; STEP * CLASSES - 7]);
        assert_eq!((*nothing, *byte, *sixteen, *wide), ((), 7, u128::MAX, Wide(9)));
        assert_eq!((*big, *edge, *over), ([11; 100], [13; STEP * CLASSES - 8], [15; STEP * CLASSES - 7]));
        assert_eq!((place(&sixteen) % 16, place(&wide) % 64, place(&big) % 8), (0, 0, 0));
        assert_eq!(wide.into_inner(), Wide(9));
        assert_eq!(big.into_inner()[99], 11);
        // what has no class goes to the system, not to a list
        let before: Vec<usize> = (0..CLASSES).map(kept).collect();
        drop(Owned::new(Wide(1)));
        drop(Owned::new([0u64; 100]));
        assert_eq!((0..CLASSES).map(kept).collect::<Vec<_>>(), before);
    }

    #[test]
    fn a_slice_of_any_length_its_values_dropped_once() {
        let held = Rc::new(());
        let none: Shared<[Rc<()>]> = Shared::from_exact(0, std::iter::empty());
        let three = Shared::from_exact(3, (0..3).map(|_| held.clone()));
        let many = Shared::from_exact(100, (0..200).map(|_| held.clone()));
        let wide = Shared::from_exact(2, [Wide(1), Wide(2)].into_iter());
        let again = many.clone();
        assert_eq!((none.len(), three.len(), many.len(), Rc::strong_count(&held)), (0, 3, 100, 104));
        assert!(Rc::ptr_eq(&again[99], &held) && !many.alone() && three.alone());
        assert_eq!((&wide[..], &raw const wide[0] as usize % 64), (&[Wide(1), Wide(2)][..], 0));
        drop((none, three, many));
        assert_eq!(Rc::strong_count(&held), 101);
        drop(again);
        assert_eq!(Rc::strong_count(&held), 1);
    }

    #[test]
    #[should_panic(expected = "as many values as said")]
    fn a_slice_of_fewer_values_than_said_is_refused() {
        Shared::from_exact(3, [1, 2].into_iter());
    }

    #[test]
    fn a_class_keeps_so_many_free_blocks_and_no_more() {
        thread::spawn(|| {
            let class = class_of(Layout::new::<Slot<[u64; 3]>>()).expect("a small layout");
            let many: Vec<_> = (0..KEPT + 10).map(|i| Owned::new([i as u64; 3])).collect();
            assert!(many.iter().enumerate().all(|(i, owned)| owned[2] == i as u64));
            drop(many);
            assert_eq!(kept(class), KEPT);
            let again: Vec<_> = (0..KEPT + 10).map(|i| Owned::new([i as u64; 3])).collect();
            assert_eq!(kept(class), 0);
            drop(again);
        })
        .join()
        .expect("the thread ends well");
    }

    struct Late(Cell<Option<Shared<[Counted]>>>);

    thread_local! {
        static LATE: Late = const { Late(Cell::new(None)) };
    }

    #[test]
    fn at_a_threads_end_the_lists_are_emptied_and_what_is_let_go_after_goes_to_the_system() {
        // closed by hand: taking and letting go still work, and nothing is kept
        thread::spawn(|| {
            let class = class_of(Layout::new::<Slot<u64>>()).expect("a small layout");
            drop((Owned::new(1u64), Owned::new(2u64)));
            assert_eq!(kept(class), 2);
            let alive = Owned::new(3u64);
            close();
            assert!(CLASSES_FREE.with(|classes| classes.iter().all(|list| list.free.get().is_null())));
            drop(alive);
            drop(Owned::new(4u64));
            assert!(CLASSES_FREE.with(|classes| classes.iter().all(|list| list.free.get().is_null())));
            assert_eq!(*Owned::new(5u64), 5);
        })
        .join()
        .expect("the thread ends well");
        // by the thread's own end, with a value another destructor lets go, before the lists' or after
        let dropped = Arc::new(AtomicUsize::new(0));
        let counted = Counted(dropped.clone());
        thread::spawn(move || {
            LATE.with(|late| late.0.set(Some(Shared::from_exact(1, [counted].into_iter()))));
            drop((Owned::new(1u64), Shared::new((), Adds(Rc::new(1)))));
        })
        .join()
        .expect("the thread ends well");
        assert_eq!(dropped.load(Ordering::Relaxed), 1);
    }

    struct Counted(Arc<AtomicUsize>);

    impl Drop for Counted {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
    }
}
