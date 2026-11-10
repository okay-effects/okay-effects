//! THE FAMILY'S TCK (okay-kernel, tck/Tck.scala): a thousand random programs — handlers of logs, states, failures
//! and choices nested in any order, each effect under two identities — and the digest of their outcomes. The digest
//! is the reference machine's; the Scala and the Java kernels of this repository come to the same number.

use std::cell::Cell;
use std::rc::Rc;

use okay_algebraic::effects::{Among, all, among};
use okay_algebraic::*;

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

// the effects: an identity `e` in the operation — a handler answers its own, and asks any other again outside
struct TellOp(i32, i32);
struct GetOp(i32);
struct PutOp(i32, i32);
struct RaiseOp(i32, i32);

impl Op for TellOp { type Answer = (); }
impl Op for GetOp { type Answer = i32; }
impl Op for PutOp { type Answer = (); }
impl Op for RaiseOp { type Answer = i32; }

fn writer(e: i32, p: Comp<i32>) -> Comp<i32> {
    Handler::new(|a: i32| pure((0i32, a)))
        .on(move |tell: &TellOp, k| {
            if tell.0 != e {
                return k.pass();
            }
            let n = tell.1;
            k.resume(()).map(move |(sum, a)| (sum.wrapping_add(n), a))
        })
        .handle(p)
        .map(|(sum, a)| a.wrapping_mul(100).wrapping_add(sum))
}

/// the TCK's state is a cell, made where the handler is installed: one for every resumption taken past it
fn state(e: i32, s0: i32, p: Comp<i32>) -> Comp<i32> {
    let s = Rc::new(Cell::new(s0));
    let (at_end, read, write) = (s.clone(), s.clone(), s);
    Handler::new(move |a: i32| pure(a.wrapping_mul(10).wrapping_add(at_end.get())))
        .on(move |get: &GetOp, k| if get.0 == e { k.resume(read.get()) } else { k.pass() })
        .on(move |put: &PutOp, k| {
            if put.0 != e {
                return k.pass();
            }
            write.set(put.1);
            k.resume(())
        })
        .handle(p)
}

fn throws(e: i32, p: Comp<i32>) -> Comp<i32> {
    Handler::new(|a: i32| pure(a))
        .on(move |raise: &RaiseOp, k| if raise.0 == e { pure(-1 - raise.1) } else { k.pass() })
        .handle(p)
}

fn build(t: &Rc<T>) -> Comp<i32> {
    match &**t {
        T::Pure(n) => pure(*n),
        T::Tell(e, n) => { let n = *n; perform(TellOp(*e, n)).map(move |_| n) }
        T::Get(e) => perform(GetOp(*e)),
        T::Put(e, n) => { let n = *n; perform(PutOp(*e, n)).map(move |_| n) }
        T::Among(ns) => among(ns.clone()),
        T::Raise(e, n) => perform(RaiseOp(*e, *n)),
        T::Seq(a, b) => { let b = b.clone(); build(a).then(move || build(&b)) }
        T::Add(a, b) => { let b = b.clone(); build(a).flat_map(move |i| build(&b).map(move |j| i.wrapping_add(j))) }
        T::WriterH(e, body) => writer(*e, build(body)),
        T::StateH(e, s0, body) => state(*e, *s0, build(body)),
        T::ThrowsH(e, body) => throws(*e, build(body)),
        T::ChooseH(body) => all::<i32, i32>(build(body)).map(|vs| vs.iter().fold(0i32, |s, v| s.wrapping_add(*v))),
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
        let outcome = build(&t).run().unwrap_or(-1000000); // no handler
        d = d.wrapping_mul(31).wrapping_add(outcome as i64);
    }
    assert_eq!(d, DIGEST);
}

#[allow(dead_code)]
fn used(_: Among<i32>) {}
