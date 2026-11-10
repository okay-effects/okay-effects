package okay.effects.scala.wire

import okay.effects.scala.*

/** The demonstration every language of this repository has, the same: three programs served by a worker, and a
 * host that runs a worker's programs with its own handlers. Any host with any worker prints the same lines. */
object Demo:
  val programs: Worker.Programs = Map(
    "t:pairs" -> (_ => call("choose", Json.Arr(List(Json.Int(1), Json.Int(2)))).map(times(10))),
    "shop:total" -> (items => total(items, 0)),
    "t:count" -> (args => count(args.head, 0))
  )

  private def times(n: Long)(x: Json): Json = x match
    case Json.Int(value) => Json.Int(value * n)
    case other => other

  private def total(items: List[Json], sum: Long): Json ! Foreign = items match
    case Nil => pure(Json.Int(sum))
    case item :: rest => call("price_of", item).flatMap:
      case Json.Int(price) => total(rest, sum + price)
      case other => throw IllegalArgumentException(s"a price: $other")

  private def count(n: Json, done: Long): Json ! Foreign =
    if n == Json.Int(done) then pure(n) else call("tick").flatMap(_ => count(n, done + 1))

  private val prices = Map("tea" -> 3L, "milk" -> 2L)

  /** this side's answers: every choice taken, a price looked up, a tick counted */
  def answered(p: Json ! Foreign): List[Json] ! Any =
    handle(new Handler[Foreign, Json, List[Json], Any]:
      def ret(a: Json) = pure(List(a))
      def on[X](op: Foreign & Op[Foreign, X], k: X => List[Json] ! Any) = op match
        case Call("choose", List(Json.Arr(values))) =>
          values.foldLeft(pure(Nil): List[Json] ! Any)((found, x) => found.flatMap(all => k(x).map(all ++ _)))
        case Call("price_of", List(Json.Str(item))) => k(Json.Int(prices(item)))
        case Call("tick", _) => k(Json.Null)
        case Call(name, _) => throw IllegalArgumentException(s"no answer for $name")
    )(p)

  /** `wireDemo worker`, `wireDemo host <the worker's command>` */
  @main def wireDemo(args: String*): Unit = args.toList match
    case List("worker") => Worker.serve(programs)
    case "host" :: command if command.nonEmpty =>
      val far = Remote(command*)
      try
        println("pairs " + Json.Arr(run(answered(far.program("t:pairs")))))
        println("total " + Json.Arr(run(answered(far.program("shop:total", Json.Str("tea"), Json.Str("milk"))))))
        println("count " + Json.Arr(run(answered(far.program("t:count", Json.Int(10000))))))
      finally far.close()
    case _ =>
      System.err.println("usage: wireDemo worker | wireDemo host <the worker's command>")
      sys.exit(2)
