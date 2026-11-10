//! THE FAMILY'S TCK (okay-kernel, tck/Tck.scala): a thousand random programs — handlers of logs, states, failures
//! and choices nested in any order, each effect under two identities — and the digest of their outcomes. The digest
//! is the reference machine's; the Scala and the Java kernels of this repository come to the same number.

use std::cell;
use std::rc::Rc;

use okay_effects::effects::Choose;
use okay_effects::*;

/// java.util.Random, which the reference draws its programs from
struct JRandom(u64);

impl JRandom {
    fn new(seed: i64) -> JRandom {
        JRandom((seed as u64 ^ 0x5DEECE66D) & ((1 << 48) - 1))
    }
    fn next(&mut self, bits: u32) -> i32 {
        self.0 = (self.0.wrapping_mul(0x5DEECE66D).wrapping_add(0xB)) & ((1 << 48) - 1);
        (self.0 >> (48 - bits)) as i32
    }
    fn next_int(&mut self, bound: i32) -> i32 {
        if bound & -bound == bound {
            return ((bound as i64 * self.next(31) as i64) >> 31) as i32;
        }
        loop {
            let bits = self.next(31);
            let val = bits % bound;
            if bits.wrapping_sub(val).wrapping_add(bound - 1) >= 0 {
                return val;
            }
        }
    }
}

enum T {
    Pure(i32),
    Tell(i32, i32),
    Get(i32),
    Put(i32, i32),
    Among(Vec<i32>),
    Raise(i32, i32),
    Seq(Rc<T>, Rc<T>),
    Add(Rc<T>, Rc<T>),
    WriterH(i32, Rc<T>),
    StateH(i32, i32, Rc<T>),
    ThrowsH(i32, Rc<T>),
    ChooseH(Rc<T>),
}

fn random(r: &mut JRandom, depth: i32) -> Rc<T> {
    Rc::new(if depth <= 0 || r.next_int(4) == 0 {
        match r.next_int(6) {
            0 => T::Pure(r.next_int(10)),
            1 => { let e = r.next_int(2); T::Tell(e, r.next_int(10)) }
            2 => T::Get(r.next_int(2)),
            3 => { let e = r.next_int(2); T::Put(e, r.next_int(10)) }
            4 => { let n = 1 + r.next_int(2); T::Among((0..n).map(|_| r.next_int(10)).collect()) }
            _ => { let e = r.next_int(2); T::Raise(e, r.next_int(10)) }
        }
    } else {
        match r.next_int(6) {
            0 => { let a = random(r, depth - 1); T::Seq(a, random(r, depth - 1)) }
            1 => { let a = random(r, depth - 1); T::Add(a, random(r, depth - 1)) }
            2 => { let e = r.next_int(2); T::WriterH(e, random(r, depth - 1)) }
            3 => { let e = r.next_int(2); let s0 = r.next_int(10); T::StateH(e, s0, random(r, depth - 1)) }
            4 => { let e = r.next_int(2); T::ThrowsH(e, random(r, depth - 1)) }
            _ => T::ChooseH(random(r, depth - 1)),
        }
    })
}

// the effects: an identity `e` in the operation — a handler answers its own, and any other passes outside
enum Log {
    Tell(i32, i32, Reply<()>),
}
enum Cell {
    Get(i32, Reply<i32>),
    Put(i32, i32, Reply<()>),
}
enum Fail {
    Raise(i32, i32, Reply<i32>),
}

type Program = Comp<row![Log, Cell, Fail, Choose], i32>;

fn writer(e: i32, p: Program) -> Program {
    let on = |op, k: K<_, (i32, i32)>| match op {
        Log::Tell(_, n, r) => k.resume(r, ()).map(move |(sum, a)| (sum.wrapping_add(n), a)),
    };
    Handler::new(|a| pure((0, a)), on)
        .mine(move |op| matches!(op, Log::Tell(id, _, _) if *id == e))
        .intercept(p)
        .map(|(sum, a)| a.wrapping_mul(100).wrapping_add(sum))
}

/// the TCK's state is a cell made where the handler is installed: one for every resumption taken past it —
/// not the state of `effects::State`, which each path has its own
fn state(e: i32, s0: i32, p: Program) -> Program {
    let s = Rc::new(cell::Cell::new(s0));
    let at_end = s.clone();
    let answer = move |_: &mut (), op| match op {
        Cell::Get(_, r) => r.give(s.get()),
        Cell::Put(_, n, r) => {
            s.set(n);
            r.give(())
        }
    };
    Handler::in_place((), move |_, a: i32| pure(a.wrapping_mul(10).wrapping_add(at_end.get())), answer)
        .mine(move |op| matches!(op, Cell::Get(id, _) | Cell::Put(id, _, _) if *id == e))
        .intercept(p)
}

fn throws(e: i32, p: Program) -> Program {
    let on = |op, _| match op {
        Fail::Raise(_, n, _) => pure(-1 - n),
    };
    Handler::new(pure, on).mine(move |op| matches!(op, Fail::Raise(id, _, _) if *id == e)).intercept(p)
}

fn build(t: &Rc<T>) -> Program {
    match &**t {
        T::Pure(n) => pure(*n),
        T::Tell(e, n) => { let (e, n) = (*e, *n); perform(move |r| Log::Tell(e, n, r)).map(move |_: ()| n) }
        T::Get(e) => { let e = *e; perform(move |r| Cell::Get(e, r)) }
        T::Put(e, n) => { let (e, n) = (*e, *n); perform(move |r| Cell::Put(e, n, r)).map(move |_: ()| n) }
        T::Among(ns) => Choose::among(ns.clone()),
        T::Raise(e, n) => { let (e, n) = (*e, *n); perform(move |r| Fail::Raise(e, n, r)) }
        T::Seq(a, b) => { let b = b.clone(); build(a).then(move || build(&b)) }
        T::Add(a, b) => { let b = b.clone(); build(a).flat_map(move |i| build(&b).map(move |j| i.wrapping_add(j))) }
        T::WriterH(e, body) => writer(*e, build(body)),
        T::StateH(e, s0, body) => state(*e, *s0, build(body)),
        T::ThrowsH(e, body) => throws(*e, build(body)),
        T::ChooseH(body) => Choose::all(build(body)).map(|vs| vs.iter().fold(0i32, |s, v| s.wrapping_add(*v))).embed(),
    }
}

const DIGEST: i64 = -8623788747993822161;

#[test]
fn a_thousand_random_programs_the_reference_machines_digest() {
    let mut r = JRandom::new(20261010);
    let mut d: i64 = 0;
    for _ in 0..1000 {
        let mut t = random(&mut r, 6);
        t = Rc::new(T::WriterH(1, t));
        t = Rc::new(T::WriterH(0, t));
        t = Rc::new(T::StateH(1, 0, t));
        t = Rc::new(T::StateH(0, 0, t));
        t = Rc::new(T::ThrowsH(1, t));
        t = Rc::new(T::ThrowsH(0, t));
        t = Rc::new(T::ChooseH(t));
        let outcome = match build(&t).step() {
            Step::Done(value) => value,
            Step::Asked(_) => -1000000, // no handler
        };
        d = d.wrapping_mul(31).wrapping_add(outcome as i64);
    }
    assert_eq!(d, DIGEST);
}
