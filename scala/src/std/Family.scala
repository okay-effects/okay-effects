package okay.effects.scala.std

import okay.effects.scala.*

/*
 * THE FAMILY'S NAMES. The standard effects here are named as in the Java and the Rust kernels beside this one;
 * okay-scala names two of them otherwise, and a program written with its names runs here: `Fail` is `Throws`,
 * `Yield` is `Emit` (its operation `Yield.Emit`, its words `emit`, `collect`, `foreach`). `Fresh.next` and
 * `Fresh.from` are in `Fresh`.
 */

type Fail[E, X] = Throws[E, X]

object Fail:
  export Throws.{Raise, raise, run}

  /** okay-scala's order: the program, then what is made of its failure */
  def recover[E, R[_], Rest[_], A](p: A ! R)(h: E => A ! Rest)(using Split[Fail % E, R, Rest]): A ! Rest =
    Throws.recover(h)(p)

type Yield[O, X] = Emit[O, X]

object Yield:
  val Emit = okay.effects.scala.std.Emit.Send
  export okay.effects.scala.std.Emit.{collect, send as emit}

  def foreach[O, R[_], Rest[_], A](p: A ! R)(using Split[Yield % O, R, Rest])(f: O => Unit ! Rest): A ! Rest =
    okay.effects.scala.std.Emit.each(f)(p)
