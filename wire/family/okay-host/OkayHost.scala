//> using scala 3.9.0

package okay.effects.family

import okay.codec.{WireAuth, WireCompression, WireFormat}
import okay.foreign.{Address, Condition, ForeignEval, ForeignWorker, Handle, PyNode, Value, Wire}
import okay.foreign.Value.{Arr, Dict, F64, I64, Str}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

/** A REAL OKAY HOST — okay's own `ForeignWorker` (module okay-py), from the classes okay's build left in its
 * `target` directories — against OUR workers' demonstration module (wire/README.md), a line for each feature.
 * Nothing of ours is between the host and the worker: `ForeignWorker.speaking(command)` starts the worker, and
 * every request is one `ForeignEval` given to the worker's handler. wire/family/check.sh starts it. */
object OkayHost:
  private var failed = 0
  private given ExecutionContext = ExecutionContext.global

  /** a feature checked, in at most 30 s: `body` says what is wrong, or nothing */
  private def check(far: String, feature: String)(body: => Option[String]): Unit =
    val wrong =
      try Await.result(Future(body), 30.seconds)
      catch case e: Exception => Some(s"the host threw $e")
    wrong match
      case None => println(s"  + $far: $feature")
      case Some(why) =>
        failed += 1
        println(s"==> X $far: $feature: ${why.replace("\n", "\\n")}")

  private def same(expected: Any, got: Any): Option[String] =
    Option.when(expected.toString != got.toString)(s"expected $expected, got $got")

  private def kind(answer: Either[Condition, Any]): String = answer.fold(_.kind, v => s"no condition: $v")

  /** everything a stream sends, pulled to its end */
  private def pulled(worker: ForeignWorker, stream: Long): Either[Condition, Vector[Value]] =
    val h = worker.handler
    def rest(got: Vector[Value]): Either[Condition, Vector[Value]] = h.handle(ForeignEval.Pull(stream)) match
      case Right(Some(chunk)) => rest(got :+ chunk)
      case Right(None) => Right(got)
      case Left(c) => Left(c)
    rest(Vector.empty)

  /** t:pairs with every choice taken: the same continuation continued with each */
  private def pairs(worker: ForeignWorker, run: Long): Any =
    val h = worker.handler
    val answers = h.handle(ForeignEval.Program(run, "t:pairs", Vector.empty, Vector("choose"))) match
      case Right(PyNode.Perform("choose", Vector(Arr(choices)), k, false)) =>
        choices.map(x => h.handle(ForeignEval.Continue(run, k, Right(x))))
      case other => Vector(other)
    h.handle(ForeignEval.Forget(run))
    answers

  private def ints(values: Long*): Vector[Value] = values.toVector.map(I64(_))

  /** our demonstration, by okay's host, on JSON lines; the far side says its language in its hello */
  private def plain(far: String, language: String, command: Seq[String]): Unit =
    val worker = ForeignWorker.speaking(command)
    val h = worker.handler
    def call(fn: Address, args: Value*) = h.handle(ForeignEval.Call(fn, args.toVector))
    try
      check(far, "the hello: shim 9 accepted, the far side's name, mux, JSON lines"):
        same((language, true, "json/none"), (worker.pythonVersion, worker.muxed, worker.wire))
      check(far, "call: t:add(2, 3)")(same(Right(I64(5)), call("t:add", ints(2, 3)*)))
      check(far, "an object held: its type, a method, an attribute; released, refused by name"):
        val ref = h.handle(ForeignEval.Call("t:counter", ints(10), held = true)).flatMap(Wire.asRef)
          .fold(c => throw IllegalStateException(s"hold: $c"), identity)
        val got = (ref.pyType, call(Address.Method(ref, "inc"), I64(5)), call(Address.Attr(ref, "n")))
        h.handle(ForeignEval.Release(ref))
        same(("t.Counter", Right(I64(15)), Right(I64(15)), "LookupError"),
          (got._1, got._2, got._3, kind(call(Address.Attr(ref, "n")))))
      check(far, "a value held: its ref as an argument is the value again; released"):
        val ref = h.handle(ForeignEval.Call("t:echo", Vector(Arr(ints(1, 2, 3))), held = true)).flatMap(Wire.asRef)
          .fold(c => throw IllegalStateException(s"hold: $c"), identity)
        val sum = call("t:sum", Value.Ref(ref))
        h.handle(ForeignEval.Release(ref))
        same(("list", Right(I64(6)), "LookupError"), (ref.pyType, sum, kind(call("t:sum", Value.Ref(ref)))))
      check(far, "multi-shot: t:pairs, one continuation continued twice; forgotten, refused by name"):
        val forgotten = h.handle(ForeignEval.Continue(1, 1, Right(I64(1))))
        same((Vector(Right(PyNode.Done(I64(10))), Right(PyNode.Done(I64(20)))), "LookupError"),
          (pairs(worker, 1), kind(forgotten)))
      check(far, "a program's operations answered by the host: shop:total(tea, milk), t:quote(tea)"):
        def answered(node: Either[Condition, PyNode], run: Long): Either[Condition, Value] = node match
          case Right(PyNode.Perform("price_of", Vector(Str(item)), k, _)) =>
            val price = Map[String, Value]("tea" -> I64(3), "milk" -> I64(2), "coffee" -> F64(4.25))(item)
            answered(h.handle(ForeignEval.Continue(run, k, Right(price))), run)
          case Right(PyNode.Done(value)) => Right(value)
          case Right(other) => Left(Condition("Unexpected", other.toString))
          case Left(c) => Left(c)
        val total = answered(h.handle(ForeignEval.Program(2, "shop:total", Vector(Str("tea"), Str("milk")),
          Vector("price_of"))), 2)
        val quote = answered(h.handle(ForeignEval.Program(3, "t:quote", Vector(Str("coffee")), Vector("price_of"),
          direct = true)), 3)
        h.handle(ForeignEval.Forget(2))
        h.handle(ForeignEval.Forget(3))
        same((Right(I64(5)), Right(F64(8.5))), (total, quote))
      check(far, "a continue with a condition is refused by name (ValueError: a program here is data)"):
        val k = h.handle(ForeignEval.Program(4, "t:pairs", Vector.empty, Vector("choose"))) match
          case Right(PyNode.Perform(_, _, k, _)) => k
          case other => throw IllegalStateException(s"t:pairs: $other")
        val refused = h.handle(ForeignEval.Continue(4, k, Left(Condition("KeyError", "no such choice"))))
        h.handle(ForeignEval.Forget(4))
        same("ValueError", kind(refused))
      check(far, "a failure is a condition by name, and the far side lives on"):
        same((Left(Condition("ValueError", "no")), Right(I64(5))), (call("t:boom"), call("t:add", ints(2, 3)*)))
      check(far, "the value escapes round trip (t:echo): a big integer, nan, 3.0, 0.5, a dict, a string"):
        val odd = Arr(Vector(I64(9007199254740993L), F64(Double.NaN), F64(3.0), F64(0.5),
          Dict(Vector("a" -> I64(1))), Str("é \"\\\n\u0001")))
        same(Right(odd), call("t:echo", odd))
      check(far, "mux: t:wait is answered once t:open comes, on one worker"):
        val waiting = Future(call("t:wait", Str("g")))
        // the waiting call first: sent second it would not wait at all
        Thread.sleep(200)
        val opened = call("t:open", Str("g"))
        same((Right(I64(1)), Right(Str("g"))), (opened, Await.result(waiting, 20.seconds)))
      check(far, "stream: t:numbers(5) under a credit of 2, every chunk in order, then its end"):
        val opened = h.handle(ForeignEval.Stream(1, "t:numbers", ints(5), 2))
        same((Right(()), Right(ints(1, 2, 3, 4, 5))), (opened, pulled(worker, 1)))
      check(far, "stream: three taken of a hundred, cancelled: the far side sent 3 + the credit of 2"):
        h.handle(ForeignEval.Stream(2, "t:numbers", ints(100), 2))
        val taken = Vector.fill(3)(h.handle(ForeignEval.Pull(2)))
        h.handle(ForeignEval.Cancel(2))
        Thread.sleep(300)
        same((ints(1, 2, 3).map(x => Right(Some(x))), Right(I64(5))), (taken, call("t:sent")))
      check(far, "duplex: t:dedup fed 20000 values by the host under credit, the 1000 distinct ones out in order"):
        val input = Iterator.tabulate(20000)(i => I64(i % 1000): Value)
        val opened = h.handle(ForeignEval.Stream(3, "t:dedup", Vector.empty, 2, Some(input)))
        same((Right(()), Right(ints(0L until 1000L*))), (opened, pulled(worker, 3)))
      check(far, "a stream that cannot start: the call's own answer is the condition"):
        same("LookupError", kind(h.handle(ForeignEval.Stream(4, "t:nothing", Vector.empty, 2))))
    finally worker.close()

  /** the same far side configured by okay's host to CBOR and DEFLATE: frames */
  private def framed(far: String, command: Seq[String]): Unit =
    import WireFormat.Cbor.given
    import WireCompression.Deflate.given
    val worker = ForeignWorker.speaking(command)
    try
      check(far, "configure: cbor and deflate chosen by the host; a call, a program, a stream in frames"):
        val h = worker.handler
        val sum = h.handle(ForeignEval.Call("t:add", ints(2, 3)))
        val both = pairs(worker, 1)
        h.handle(ForeignEval.Stream(1, "t:numbers", ints(3), 1))
        same(("cbor/deflate", Right(I64(5)), Vector(Right(PyNode.Done(I64(10))), Right(PyNode.Done(I64(20)))),
          Right(ints(1, 2, 3))), (worker.wire, sum, both, pulled(worker, 1)))
    finally worker.close()

  /** the far side started with a secret, okay's host proving the same */
  private def proven(far: String, command: Seq[String]): Unit =
    val secret = "a secret of the family"
    check(far, "auth: hmac-sha256 both ways over the pipes, then a call"):
      given WireAuth = WireAuth.secret(secret.getBytes("UTF-8"))
      val worker = ForeignWorker.speaking(command, Map("OKAY_WIRE_SECRET" -> secret))
      try same(Right(I64(5)), worker.handler.handle(ForeignEval.Call("t:add", ints(2, 3))))
      finally worker.close()
    check(far, "auth: a host with another secret is refused"):
      given WireAuth = WireAuth.secret("another".getBytes("UTF-8"))
      try
        val worker = ForeignWorker.speaking(command, Map("OKAY_WIRE_SECRET" -> secret))
        worker.close()
        Some("the wire was opened")
      catch case _: Exception => None

  def main(args: Array[String]): Unit =
    // a far side that stalls must fail the check, not hang it
    val watchdog = Thread: () =>
      Thread.sleep(180000)
      println(s"==> X ${args.headOption.getOrElse("?")}: no end in 180 s")
      Runtime.getRuntime.halt(1)
    watchdog.setDaemon(true)
    watchdog.start()
    args.toList match
      case language :: command if command.nonEmpty =>
        val far = s"our $language worker"
        plain(far, language, command)
        framed(far, command)
        proven(far, command)
        // halt: a check that ran out of time has left a thread waiting on the wire
        Runtime.getRuntime.halt(if failed == 0 then 0 else 1)
      case _ =>
        System.err.println("usage: OkayHost <scala|java|rust> <the worker's command...>")
        sys.exit(2)
