//> using scala 3.3.8
//> using files ../../../scala/src
//> using options -deprecation -feature

package okay.effects.family

import okay.effects.scala.*
import okay.effects.scala.std.{Emit, Throws}
import okay.effects.scala.wire.*
import okay.effects.scala.wire.Far.Failing
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** OUR HOST (the Scala kernel's `Remote`, compiled from scala/src by path) against the family's real far sides, a
 * line for each feature: `  + far: feature`, or `==> X far: feature: what was expected and what came`.
 * wire/family/check.sh starts it; wire/family/README.md says what each mode covers. */
final class Report(val far: String):
  var failed = 0

  /** a feature checked: `body` says what is wrong, or nothing */
  def check(feature: String)(body: => Option[String]): Unit =
    val wrong = try body catch case e: Exception => Some(s"the host threw $e")
    wrong match
      case None => println(s"  + $far: $feature")
      case Some(why) =>
        failed += 1
        println(s"==> X $far: $feature: $why")

  def same(expected: String, got: String): Option[String] =
    Option.when(expected != got)(s"expected ${short(expected)}, got ${short(got)}")

/** a value for a line of the report: its beginning, when it is long */
def short(text: String): String = if text.length <= 300 then text else text.take(300) + s"... (${text.length} chars)"

/** the program's value on a far side, or the far side's failure as `kind: message` */
def on[A](far: Remote)(p: A ! Failing): String = far.run(Throws.run(p)) match
  case Right(value) => value.toString
  case Left(Condition(kind, message)) => s"$kind: $message"

/** a far failure, raised in the host's program */
def orRaise[A, R[_]: Has[Throws % Condition]](answer: Either[Condition, A]): A ! R =
  answer.fold(Throws.raise, pure)

def number(value: Json): Double = value match
  case Json.Int(n) => n.toDouble
  case Json.Dec(x) => x
  case other => throw IllegalArgumentException(s"a number was expected: $other")

/** this side's answers to a far program: every choice taken, a price looked up, a discount of a half */
def answered[R[_], Rest[_]](price: Double)(p: Json ! R)(using Split[Foreign, R, Rest]): List[Json] ! Rest =
  handle(p, Handler.general[Foreign, Rest, Json, List[Json]](a => pure(List(a)))(
    [X] => (op: Foreign[X], k: X => List[Json] ! Rest) => op match
      case Call("choose", List(Json.Arr(values))) =>
        values.foldLeft(pure[List[Json], Rest](Nil))((found, x) => found.flatMap(all => k(x).map(all ++ _)))
      case Call("price_of", List(Json.Str("tea"))) => k(Json.Dec(price))
      case Call("discount", List(amount)) => k(Json.Dec(number(amount) * 0.5))
      case Call(name, args) => throw IllegalArgumentException(s"no answer for $name$args")))

def all(price: Double)(p: Json ! Failing + Foreign): Json ! Failing = answered(price)(p).map(Json.Arr(_))

/** a stream's chunks, as they came */
def sent(p: Unit ! Failing + Emit % Json): List[Json] ! Failing = Emit.collect(p).map((values, _) => values)

/** okay's streams carry a list of rows in each chunk: the rows */
def rows(chunks: List[Json]): List[Json] = chunks.flatMap(Escapes.items)

def ints(values: Long*): List[Json] = values.toList.map(Json.Int(_))

/** THE CONFORMANCE MODULE of okay's `WireConformance`, as okay-zig, okay-lean and okay-ts serve it: what our host
 * can ask of it. `has` names what this far side serves beyond programs: held, direct, streams. */
def conformance(report: Report, command: Seq[String], has: Set[String]): Unit =
  import report.{check, same}
  val far = Remote.start(command)
  def tag(name: String) = s"$name${System.nanoTime}"
  def pairs = on(far)(all(4.0)(Far.program("pairs", Nil, List("choose"))))
  /** how many chunks the tagged stream has sent, once the number stops moving */
  def settled(tag: String, last: Long = -1, tries: Int = 30): Long ! Failing =
    Far.call("emitted_of", Json.Str(tag)).flatMap:
      case Json.Int(now) if now == last || tries == 0 => pure(now)
      case Json.Int(now) => defer { Thread.sleep(150); settled(tag, now, tries - 1) }
      case other => Throws.raise(Condition("WireError", s"emitted_of answered $other"))
  def stopped(tag: String, tries: Int = 100): Boolean ! Failing =
    Far.call("stopped_of", Json.Str(tag)).flatMap:
      case Json.Bool(false) if tries > 0 => defer { Thread.sleep(50); stopped(tag, tries - 1) }
      case answer => pure(answer == Json.Bool(true))
  try
    check("the hello: shim 9" + (if has("streams") then ", mux" else "")):
      Option.when(has("streams") && !far.mux)("the far side claims no mux")
    check("multi-shot: pairs, every branch of two choices")(same("[11,21,12,22]", pairs))
    check("a program's operations answered by the host: total(tea, 3)"):
      val total = Far.program("total", List(Json.Str("tea"), Json.Int(3)), List("price_of", "discount"))
      same("List(6.0)", far.run(Throws.run(answered(4.0)(total))).map(_.map(number)).merge.toString)
    if has("direct") then check("direct style: quote(tea, 3), ordinary code asking the host"):
      val quote = Far.program("quote", List(Json.Str("tea"), Json.Int(3)), List("price_of", "discount"))
      same("List(6.0)", far.run(Throws.run(answered(4.0)(quote))).map(_.map(number)).merge.toString)
    check("a failure is a condition by name, and the far side lives on"):
      val boom = on(far)(all(4.0)(Far.program("boom", Nil, List("choose"))))
      if !boom.contains("says no") || boom.startsWith("[") then Some(s"expected a condition `... says no`, got $boom")
      else same("[11,21,12,22]", pairs)
    if has("held") then check("a held value: used twice by its ref, released, then refused"):
      def describe(ref: Json.Ref, k: Long) = Far.call("describe", ref, Json.Int(k)).map(number)
      val got = on(far)(
        for
          ref <- Far.hold("counter", Json.Int(10))
          a <- describe(ref, 2)
          b <- describe(ref, 5)
          _ <- Far.release(ref)
          c <- Throws.run[Condition, Failing, Far, Double](describe(ref, 1))
        yield s"$a $b ${c.isLeft}")
      same("12.0 15.0 true", got)
    if has("streams") then
      check("mux: a call that waits is answered once another call opens it"):
        val gate = Json.Str(tag("g"))
        val got = on(far)(
          for
            waits <- Far.start("await_open", gate)
            opens <- Far.start("open", gate)
            opened <- Far.result(opens)
            waited <- Far.result(waits)
          yield Json.Arr(List(opened, waited)))
        same(Json.Arr(List(gate, gate)).toString, got)
      check("stream: 100 rows in chunks of 7, in order, under a credit of 2"):
        val numbers = Far.stream("numbers", ints(100, 7) :+ Json.Str(tag("s")), credit = 2)
        same(ints(0L until 100L*).toString, on(far)(sent(numbers).map(rows)))
      check("stream: the far side runs ahead by the credit and no further (counted there)"):
        val t = tag("c")
        val got = on(far)(
          for
            opened <- word(Far.Open("numbers", ints(1000, 1) :+ Json.Str(t), 3, None))
            stream <- orRaise(opened)
            ahead <- settled(t)
            taken <- word(Far.Pull(stream))
            after <- settled(t)
          yield s"sent $ahead with none taken, $after with one taken (${taken.map(_.isDefined)})")
        same("sent 3 with none taken, 4 with one taken (Right(true))", got)
      check("stream: a consumer that stops early cancels it, and the far function returns"):
        val t = tag("e")
        val four = Emit.take(4)(Far.stream("numbers", ints(100000, 1) :+ Json.Str(t), credit = 2))
        val taken = on(far)(sent(four).map(rows))
        val after = far.run(Throws.run(settled(t).flatMap(n => stopped(t).map(s => (n, s)))))
        if taken != ints(0, 1, 2, 3).toString then Some(s"expected the rows 0..3, got $taken")
        else after match
          case Right((n, true)) if n <= 4 + 2 => None
          case other => Some(s"expected at most 6 chunks sent and the function stopped, got $other")
      check("fed: the host feeds sum_after 1000 rows, never more than its credit ahead"):
        val t = tag("f")
        var fed = 0
        val input = LazyList.tabulate(1000)(i => { fed += 1; Json.Arr(ints(i)) })
        val got = on(far)(
          for
            opened <- word(Far.Open("sum_after", List(Json.Str(t)), 3, Some(input)))
            stream <- orRaise(opened)
            // nothing is taken until the gate opens: answers are read meanwhile, and the host holds at the credit
            _ <- settled(t)
            held = fed
            _ <- Far.call("open", Json.Str(t))
            sum <- word(Far.Pull(stream))
            end <- word(Far.Pull(stream))
          yield s"fed $held before the gate, $fed in all, sum ${sum.map(_.toList.flatMap(Escapes.items))}, $end")
        val expected = s"in all, sum Right(List(${(0 until 1000).sum})), Right(None)"
        if got.endsWith(s"1000 $expected") && fed == 1000
          && got.drop(4).takeWhile(_.isDigit).toIntOption.exists(_ <= 3 + 1) then None
        else Some(s"expected `fed <=4 before the gate, 1000 $expected`, got `$got`")
      check("duplex: dedup of 20000 rows fed in chunks of 512, the 1000 distinct ones out in order"):
        val chunks = (0 until 20000).map(i => Json.Int(i % 1000)).grouped(512).map(c => Json.Arr(c.toList))
        val dedup = Far.stream("dedup", Nil, credit = 2, input = Some(Emit.sendAll(chunks)))
        same(ints(0L until 1000L*).toString, on(far)(sent(dedup).map(rows)))
  finally far.close()

/** THE REFERENCE FAR SIDE, okay's Python shim serving the module of wire.txt's header, over one of the wire's
 * formats */
def reference(report: Report, command: Seq[String], cbor: Boolean, deflate: Boolean): Unit =
  import report.{check, same}
  val far = Remote.start(command, cbor, deflate)
  try
    check("call: t:add(2, 3)")(same("5", on(far)(Far.call("t:add", ints(2, 3)*))))
    check("the value escapes round trip (copy:copy): a big integer, nan, 3.0, 0.5, a dict, a string"):
      val odd = Json.Arr(List(Json.Int(9007199254740993L), Json.Dec(Double.NaN), Json.Dec(3.0), Json.Dec(0.5),
        Json.obj("a" -> Json.Int(1)), Json.Str("é \"\\\n\u0001")))
      same(Escapes.enc(odd).toString, on(far)(Far.call("copy:copy", odd).map(Escapes.enc)))
    check("an object held: its type, a method, an attribute; released, refused by name"):
      val got = on(far)(
        for
          counter <- Far.hold("t:counter", ints(10)*)
          after <- Far.method(counter, "inc", ints(5)*)
          n <- Far.attr(counter, "n")
          _ <- Far.release(counter)
          gone <- Throws.run[Condition, Failing, Far, Json](Far.attr(counter, "n"))
        yield s"${counter.kind} $after $n ${gone.left.map(_.kind)}")
      same("t.Counter 15 15 Left(LookupError)", got)
    check("multi-shot: t:pairs, the same continuation continued twice"):
      same("[10,20]", on(far)(all(4.25)(Far.program("t:pairs", Nil, List("choose")))))
    check("direct style: t:quote(tea), ordinary code asking the host"):
      same("[8.5]", on(far)(all(4.25)(Far.program("t:quote", List(Json.Str("tea")), List("price_of")))))
    check("a failure is a condition by name, and the far side lives on"):
      same("ValueError: no", on(far)(Far.call("t:boom"))).orElse(same("5", on(far)(Far.call("t:add", ints(2, 3)*))))
  finally far.close()

/** OKAY'S TRANSCRIPT, its `wire.txt`: each `>` line sent, the answer held to the `<` line after it — a line of the
 * report for each of its sections. `strict` is for okay's own shim: every line as written. Our workers are held to all
 * of it but what wire/README.md says is not here — the table call is not sent, a node's `once` is not expected —
 * and `k` is taken as the far side's own number (okay's far sides number it differently among themselves): a
 * `continue` is sent with the `k` the far side gave where the transcript gave its own. */
def transcript(report: Report, file: Path, command: Seq[String], strict: Boolean): Unit =
  def same(expected: Json, got: Json): Boolean = (expected, got) match
    case (Json.Str("*"), _) => true
    case (Json.Obj(a), Json.Obj(b)) =>
      a.size == b.size && a.forall((name, value) => b.exists((n, v) => n == name && same(value, v)))
    case (Json.Arr(a), Json.Arr(b)) => a.size == b.size && a.zip(b).forall(same)
    case (Json.Int(a), Json.Dec(b)) => a.toDouble == b
    case (Json.Dec(a), Json.Int(b)) => a == b.toDouble
    case _ => expected == got
  /** an expected answer, as our workers are held to it: no `once`, any `k` */
  def ours(tree: Json): Json = tree match
    case Json.Obj(fields) => Json.Obj(fields.collect:
      case ("k", _) => ("k", Json.Str("*"))
      case (name, value) if name != "once" => (name, ours(value)))
    case other => other
  // (run, the transcript's k) -> the k this far side gave
  val named = scala.collection.mutable.Map.empty[(Json, Json), Json]
  def renamed(request: Json): Json = (request, named.get((request("run"), request("k")))) match
    case (Json.Obj(fields), Some(k)) => Json.Obj(fields.map((name, value) => (name, if name == "k" then k else value)))
    case _ => request
  val process = ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.INHERIT).start()
  val in = java.io.BufferedReader(java.io.InputStreamReader(process.getInputStream, UTF_8))
  val out = java.io.PrintStream(process.getOutputStream, true, UTF_8)
  def answer(): Json = Option(in.readLine()).map(Json.parse).getOrElse(Json.Str("the end of the far side's output"))
  try
    // a section: its title, and its lines marked `>` and `<`
    val sections = Files.readString(file).linesIterator.foldLeft(List.empty[(String, List[String])]):
      case (found, line) if line.startsWith("# ----") =>
        (line.drop(6).trim.reverse.dropWhile(_ == '-').reverse.trim, Nil) :: found
      case ((title, lines) :: found, line) if line.startsWith("> ") || line.startsWith("< ") =>
        (title, lines :+ line) :: found
      case (found, _) => found
    sections.reverse.foreach: (title, lines) =>
      val sends = lines.filter(_.startsWith("> ")).map(line => Json.parse(line.drop(2)))
      val expects = lines.filter(_.startsWith("< ")).map(line => Json.parse(line.drop(2)))
      if sends.isEmpty then report.check(s"wire.txt: $title"):
        // the hello: what it speaks is the far side's own, the version is the transcript's
        val hello = answer()
        Option.when(expects.head("shim") != hello("shim"))(s"expected shim ${expects.head("shim")}, got $hello")
      else if !strict && sends.exists(_("table") == Json.Bool(true)) then
        println(s"  - skipped: ${report.far}: wire.txt: $title (no tables here: wire/README.md, Not here)")
      else report.check(s"wire.txt: $title"):
        sends.zip(expects).foldLeft(Option.empty[String]):
          case (None, (request, expected)) =>
            val sent = if strict then request else renamed(request)
            out.println(sent.toString)
            val wanted = if strict then expected else ours(expected)
            val got = answer()
            if got("ok")("k") != Json.Null then named((request("run"), expected("ok")("k"))) = got("ok")("k")
            Option.when(!same(wanted, got))(s"to $sent expected $wanted, got $got")
          case (wrong, _) => wrong
  finally process.destroy()

@main def family(args: String*): Unit =
  // a far side that stalls must fail the check, not hang it
  val watchdog = Thread: () =>
    Thread.sleep(120000)
    println(s"==> X ${args.take(2).mkString(" ")}: no end in 120 s")
    Runtime.getRuntime.halt(1)
  watchdog.setDaemon(true)
  watchdog.start()
  args.toList match
    case "conformance" :: name :: has :: command if command.nonEmpty =>
      val report = Report(name)
      conformance(report, command, has.split(',').toSet)
      sys.exit(if report.failed == 0 then 0 else 1)
    case "reference" :: name :: format :: command if command.nonEmpty =>
      val report = Report(name)
      reference(report, command, cbor = format.contains("cbor"), deflate = format.contains("deflate"))
      sys.exit(if report.failed == 0 then 0 else 1)
    case "transcript" :: name :: how :: file :: command if command.nonEmpty =>
      val report = Report(name)
      transcript(report, Path.of(file), command, strict = how == "strict")
      sys.exit(if report.failed == 0 then 0 else 1)
    case _ =>
      System.err.println("usage: family conformance <name> <held,direct,streams|-> <the worker's command...>\n" +
        "       family reference <name> <json|cbor|json+deflate|cbor+deflate> <the worker's command...>\n" +
        "       family transcript <name> <strict|ours> <wire.txt> <the worker's command...>")
      sys.exit(2)
