package okay.effects.scala

import scala.quoted.*

/** THE COMPILE ERRORS of a missing effect: the one macro here, for what given search cannot say — the effect and
 * the row as they are written, `State % Int` and `Reader % String + State % Int`, where Scala would print the type
 * lambdas they are, or nothing at all under a context bound. */
object Messages:
  def notIn[E[_]: Type, R[_]: Type](using Quotes): Expr[Nothing] =
    import quotes.reflect.*
    report.errorAndAbort(missing(TypeRepr.of[E], TypeRepr.of[R]))

  /** a row of one effect may be one nobody wrote — a word with no row expected of it is a program of its effect
   * alone — so the message says how a row is said */
  private def missing(using Quotes)(effect: quotes.reflect.TypeRepr, row: quotes.reflect.TypeRepr): String =
    val alone = !row.typeSymbol.isTypeParam && written(row) != "Pure" && !written(row).contains(" + ")
    val hint = if !alone then "" else ". If this row was not said, it is the row of a word alone: declare the " +
      "program's type — `val p: A ! Row = ...` — or say the row on its last word — `.into[Row]`"
    s"the effect ${written(effect)} is not in the row ${written(row)}$hint"

  /** a handler's `Split`: the effect not in the row — or in it, and the row without it is not the one asked for */
  def notSplit[E[_]: Type, R[_]: Type, Rest[_]: Type](using Quotes): Expr[Nothing] =
    import quotes.reflect.*
    val (e, r) = (written(TypeRepr.of[E]), written(TypeRepr.of[R]))
    val effects = r.replace("(", "").replace(")", "").split(" \\+ ").toSet
    if !effects(e) then report.errorAndAbort(missing(TypeRepr.of[E], TypeRepr.of[R]))
    else report.errorAndAbort(s"the row $r without the effect $e is not ${written(TypeRepr.of[Rest])}: a handler " +
      "leaves the row as it is written, its effect taken out")

  /** `Plain[E]`: refused for a sensitive effect, and for an effect that is a type parameter — its caller says */
  def plain[E[_]: Type](using Quotes): Expr[Plain[E]] =
    import quotes.reflect.*
    val effect = TypeRepr.of[E]
    def head(t: TypeRepr): TypeRepr = t.dealias match
      case TypeLambda(_, _, body) => head(body)
      case AppliedType(f, _) => head(f)
      case other => other
    if head(effect).typeSymbol.isTypeParam then
      report.errorAndAbort(s"the effect ${written(effect)} is not known here, so it may be sensitive: ask it of the " +
        s"caller, `(using Plain[${written(effect)}])`")
    Implicits.search(TypeRepr.of[Sensitive[E]]) match
      case _: ImplicitSearchSuccess =>
        report.errorAndAbort(s"the effect ${written(effect)} is sensitive: it is handled only under its audit — " +
          "`audit` it with an interceptor, then handle it with `handleAudited`")
      case _ => '{ Plain.checked[E] }

  /** a row or an effect as it is written: `+` and `%` for the lambdas they make, `Pure` for `Void` */
  def written(using Quotes)(t: quotes.reflect.TypeRepr): String =
    import quotes.reflect.*
    def of(t: TypeRepr, nested: Boolean): String = t.dealias match
      // the body reduced: a row named by an alias of one's own, `type R[X] = (A + B)[X]`, applies `+` — a Sum
      case TypeLambda(List(_), _, body) => body.dealias.simplified match
        case AppliedType(f, args) =>
          val parameter = args.lastOption.collect { case p: ParamRef => p }
          (f.typeSymbol.fullName, args) match
            case ("okay.effects.scala.Sum", List(r, e, _)) if parameter.isDefined =>
              val sum = s"${of(r, nested = false)} + ${of(e, nested = true)}"
              if nested then s"($sum)" else sum
            case (_, List(s, _)) if parameter.isDefined =>
              s"${f.typeSymbol.name} % ${s.show(using Printer.TypeReprShortCode)}"
            case ("okay.effects.scala.Void", List(_)) if parameter.isDefined => "Pure"
            case (_, List(_)) if parameter.isDefined => f.typeSymbol.name
            case _ => t.show(using Printer.TypeReprShortCode)
        case _ => t.show(using Printer.TypeReprShortCode)
      case tpe if tpe.typeSymbol.fullName == "okay.effects.scala.Void" => "Pure"
      case tpe => tpe.typeSymbol.name
    of(t, nested = false)
