package okay.effects.scala

import scala.quoted.*

/** A WORD OR A PROGRAM IN EVERY BRANCH of an `if` or a `match` that is bound:
 *
 *   _ <- if bad then Throws.raise(e) else if odd then Writer.tell(n) else unit
 *
 * Such an expression has no one type to bind, and at run time a word does not say what it is. So it is taken
 * apart where it is written, by the second macro here: each branch is made a program of the row on its own
 * (`Fits`), as if the row had been expected of it. Nothing of it is left at run time. */
object Branches:
  def flatMap[R[_]: Type, V: Type, B: Type](p: Expr[Any], f: Expr[V => B ! R])(using Quotes): Expr[B ! R] =
    '{ ${ placed[R, V](p) }.flatMap($f) }

  def map[R[_]: Type, V: Type, B: Type](p: Expr[Any], f: Expr[V => B])(using Quotes): Expr[B ! R] =
    '{ ${ placed[R, V](p) }.map($f) }

  /** `p`, each of its branches a program of `R`, to a `V` */
  private def placed[R[_]: Type, V: Type](p: Expr[Any])(using Quotes): Expr[V ! R] =
    import quotes.reflect.*
    def branch(term: Term): Term = term match
      case Inlined(call, bindings, body) => Inlined(call, bindings, branch(body))
      case Block(statements, last) => Block(statements, branch(last))
      case Typed(inner, _) => branch(inner)
      // a `$` name is the compiler's own: the inliner's mark on an argument whose type it adapted
      case TypeApply(Select(inner, name), List(_)) if name.startsWith("$") => branch(inner)
      case If(condition, yes, no) => If(condition, branch(yes), branch(no))
      case Match(on, cases) => Match(on, cases.map(c => CaseDef(c.pattern, c.guard, branch(c.rhs))))
      case leaf => leaf.tpe.widen.asType match
        case '[t] =>
          val row = Messages.written(TypeRepr.of[R])
          val fits = Expr.summon[Fits[t, R, V]].getOrElse(
            report.errorAndAbort(s"neither a word nor a program of the row $row: ${leaf.tpe.widen.show}", leaf.pos))
          '{ $fits(${ leaf.asExprOf[t] }) }.asTerm
    branch(p.asTerm).asExprOf[V ! R]
