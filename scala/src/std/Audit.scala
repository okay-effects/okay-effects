package okay.effects.scala.std

import okay.effects.scala.*

/** THE AUDIT TRAIL: a line recorded. An ordinary effect, handled outside the interceptors that record to it — so
 * the trail is a value beside the program's, and any other handler may send the lines elsewhere. */
enum Audit[X]:
  case Record(line: String) extends Audit[Unit]

object Audit:
  def record(line: String): Word[Audit, Unit] = word(Record(line))

  /** the lines recorded, in order, with the value. Kept in place: outside a handler that resumes many times it
   * is one trail through all the paths; inside it, each path has its own */
  def trail[R[_], Rest[_], A](p: A ! R)(using Split[Audit, R, Rest]): (List[String], A) ! Rest =
    handle(p, Handler.stateful[Audit, Rest, A, (List[String], A), List[String]](Nil)((lines, a) =>
      pure((lines.reverse, a)))([X] => (op: Audit[X], lines: Cell[List[String]]) => op match
        case Record(line) => lines.set(line :: lines.get)))
