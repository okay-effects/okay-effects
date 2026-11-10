package okay.effects.scala.wire

import okay.effects.scala.*
import okay.effects.scala.std.{Async, Emit, Throws}
import scala.collection.mutable

/** The demonstration every language of this repository has, the same: a module served by a worker, and a host
 * that uses every feature of the wire on it, a line for each. Any host with any worker prints the same lines
 * (wire/expected.txt; the module is described in wire/README.md). */
object Demo:
  import Far.Failing
  import Worker.Row

  /** a function of the module, from its cases; any other arguments are refused by name */
  private def function(name: String)(cases: PartialFunction[List[Json], Json ! Row])
      : (String, Worker.Function) =
    name -> (args => cases.applyOrElse(args, _ => Throws.raise(Condition("TypeError", s"$name: not its arguments"))))

  private final class Counter(private var n: Long) extends Worker.Thing:
    val kind = "t.Counter"
    val methods = { case ("inc", List(Json.Int(by))) => n += by; Json.Int(n) }
    val attrs = { case "n" => Json.Int(n) }

  /** THE MODULE the worker serves; what it remembers is the worker's, for as long as it lives */
  def module(): Worker.Module =
    var sent = 0L
    val waiting = mutable.Map.empty[String, List[Json => Unit]].withDefaultValue(Nil)

    def total[R[_]: Has[Foreign]](items: List[Json], sum: Long): Json ! R = items match
      case Nil => pure(Json.Int(sum))
      case item :: rest => call("price_of", item).flatMap:
        case Json.Int(price) => total(rest, sum + price)
        case other => throw IllegalArgumentException(s"a price: $other")

    def count[R[_]: Has[Foreign]](n: Json, done: Long): Json ! R =
      if n == Json.Int(done) then pure(n) else call("tick").flatMap(_ => count(n, done + 1))

    def numbers[R[_]: Has[Emit % Json]](next: Long, last: Long): Json ! R =
      if next > last then pure(Json.Null)
      else Emit.send(Json.Int(next)).flatMap: _ =>
        sent += 1
        numbers(next + 1, last)

    def dedup[R[_]: Has[Fed] : Has[Emit % Json]](seen: Set[Json]): Json ! R = Fed.next.flatMap:
      case None => pure(Json.Null)
      case Some(value) if seen(value) => dedup(seen)
      case Some(value) => Emit.send(value).flatMap(_ => dedup(seen + value))

    Worker.Module(
      functions = Map(
        function("t:add") { case List(Json.Int(a), Json.Int(b)) => pure(Json.Int(a + b)) },
        function("t:echo") { case List(value) => pure(value) },
        function("t:sum") { case List(Json.Arr(values)) =>
          pure(Json.Int(values.collect { case Json.Int(n) => n }.sum))
        },
        function("t:boom") { case Nil => Throws.raise(Condition("ValueError", "no")) },
        function("t:pairs") { case Nil => call("choose", Json.Arr(ints(1, 2))).map(times(10)) },
        function("t:quote") { case List(sku) => call("price_of", sku).map(times(2)) },
        function("shop:total") { case items => total(items, 0) },
        function("t:count") { case List(n) => count(n, 0) },
        function("t:numbers") { case List(Json.Int(n)) =>
          sent = 0
          numbers(1, n)
        },
        function("t:sent") { case Nil => defer(pure(Json.Int(sent))) },
        function("t:dedup") { case Nil => dedup(Set.empty) },
        function("t:wait") { case List(Json.Str(name)) =>
          Async.await[Json](resume => waiting(name) = waiting(name) :+ resume)
        },
        function("t:open") { case List(name @ Json.Str(gate)) =>
          val woken = waiting.remove(gate).getOrElse(Nil)
          woken.foreach(resume => resume(name))
          pure(Json.Int(woken.length))
        }
      ),
      classes = Map("t:counter" -> {
        case List(Json.Int(n)) => Counter(n)
        case _ => throw Condition("TypeError", "t:counter: not its arguments")
      })
    )

  private def times(n: Long)(x: Json): Json = x match
    case Json.Int(value) => Json.Int(value * n)
    case Json.Dec(value) => Json.Dec(value * n)
    case other => other

  private def ints(values: Long*): List[Json] = values.toList.map(Json.Int(_))

  private val prices = Map("tea" -> 3L, "milk" -> 2L)

  /** this side's answers: every choice taken, a price looked up, a tick counted */
  def answered[R[_], Rest[_]](p: Json ! R)(using Split[Foreign, R, Rest]): List[Json] ! Rest =
    handle(p, Handler.general[Foreign, Rest, Json, List[Json]](a => pure(List(a)))(
      [X] => (op: Foreign[X], k: X => List[Json] ! Rest) => op match
        case Call("choose", List(Json.Arr(values))) =>
          values.foldLeft(pure[List[Json], Rest](Nil))((found, x) => found.flatMap(all => k(x).map(all ++ _)))
        case Call("price_of", List(Json.Str(item))) => k(Json.Int(prices(item)))
        case Call("tick", _) => k(Json.Null)
        case Call(name, _) => throw IllegalArgumentException(s"no answer for $name")))

  /** THE HOST'S SCRIPT: a line for each feature. `open` gives a far side: over CBOR and DEFLATE or over JSON
   * lines, with a secret or with none. */
  def script(open: (Boolean, Option[String]) => Remote): List[String] =
    /** the program's value on a far side, or the far side's failure */
    def on[A](far: Remote)(p: A ! Failing): String = far.run(Throws.run(p)) match
      case Right(value) => value.toString
      case Left(Condition(kind, message)) => s"$kind: $message"
    def all(p: Json ! Failing + Foreign): Json ! Failing = answered(p).map(Json.Arr(_))
    def sent(p: Unit ! Failing + Emit % Json): Json ! Failing =
      Emit.collect(p).map((values, _) => Json.Arr(values))

    val lines = List.newBuilder[String]
    val far = open(false, None)
    try
      lines += "pairs " + on(far)(all(Far.program("t:pairs", Nil)))
      lines += "total " + on(far)(all(Far.program("shop:total", List(Json.Str("tea"), Json.Str("milk")))))
      lines += "count " + on(far)(all(Far.program("t:count", ints(10000))))
      lines += "call " + on(far)(Far.call("t:add", ints(2, 3)*))
      // an object kept on the far side: its method, its attribute; released, it is refused by name
      val counter = far.run(Throws.run(Far.hold("t:counter", ints(10)*))).toOption.get
      lines += s"held ${counter.kind} " + on(far)(
        for
          after <- Far.method(counter, "inc", ints(5)*)
          n <- Far.attr(counter, "n")
        yield s"$after $n")
      val gone = Far.release(counter).flatMap(_ => Far.attr(counter, "n"))
      lines += "released " + on(far)(gone).takeWhile(_ != ':')
      // a value kept: its ref, given to a call, is the value again
      lines += "value " + on(far)(
        for
          list <- Far.hold("t:echo", Json.Arr(ints(1, 2, 3)))
          sum <- Far.call("t:sum", list)
          _ <- Far.release(list)
        yield s"${list.kind} $sum")
      lines += "condition " + on(far)(Far.call("t:boom"))
      val odd = Json.Arr(List(Json.Int(9007199254740993L), Json.Dec(Double.NaN), Json.Dec(3.0), Json.Dec(0.5),
        Json.obj("a" -> Json.Int(1))))
      lines += "escapes " + on(far)(Far.call("t:echo", odd).map(Escapes.enc))
      lines += "stream " + on(far)(sent(Far.stream("t:numbers", ints(5), credit = 2)))
      // three taken of a hundred: the far side was two ahead, and was stopped there
      val three = Emit.take(3)(Far.stream("t:numbers", ints(100), credit = 2))
      lines += "cancel " + on(far)(sent(three)) + " " + on(far)(Far.call("t:sent"))
      val fed = Emit.sendAll(ints(3, 1, 3, 2, 1))
      lines += "duplex " + on(far)(sent(Far.stream("t:dedup", Nil, credit = 2, input = Some(fed))))
      // the second call is answered while the first still waits
      lines += "mux " + on(far)(
        for
          waits <- Far.start("t:wait", Json.Str("g"))
          opens <- Far.start("t:open", Json.Str("g"))
          opened <- Far.result(opens)
          waited <- Far.result(waits)
        yield Json.Arr(List(opened, waited)))
    finally far.close()

    val framed = open(true, None)
    try
      lines += "cbor+deflate " + on(framed)(
        for
          sum <- Far.call("t:add", ints(2, 3)*)
          pairs <- all(Far.program("t:pairs", Nil))
          numbers <- sent(Far.stream("t:numbers", ints(3), credit = 1))
        yield s"$sum $pairs $numbers")
    finally framed.close()

    val proven = open(false, Some("a secret of the demonstration"))
    try lines += "auth " + on(proven)(Far.call("t:add", ints(2, 3)*))
    finally proven.close()
    lines.result()

  /** `wireDemo worker`, `wireDemo host <the worker's command>` */
  @main def wireDemo(args: String*): Unit = args.toList match
    case List("worker") => Worker.serve(module(), secret = sys.env.get("OKAY_WIRE_SECRET"))
    case "host" :: command if command.nonEmpty =>
      script((framed, secret) => Remote.start(command, cbor = framed, deflate = framed, secret)).foreach(println)
    case _ =>
      System.err.println("usage: wireDemo worker | wireDemo host <the worker's command>")
      sys.exit(2)
