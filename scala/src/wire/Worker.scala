package okay.effects.scala.wire

import okay.effects.scala.*
import okay.effects.scala.std.{Async, Emit, Throws}
import java.io.{IOException, InputStream, OutputStream}
import scala.collection.mutable

/** THE STREAM A CALLER FEEDS into a function of the far side: the function takes a value at a time. */
enum Fed[X]:
  case Next extends Fed[Option[Json]]

object Fed:
  /** the caller's next value, waited for; none at the stream's end */
  def next: Word[Fed, Option[Json]] = word(Next)

/** THE FAR SIDE: a module served to a host, over the wire. Every function is a program, and what it asks decides
 * what crosses:
 *
 *   Call            a `perform` node — the host answers, and continues the program (as often as it likes)
 *   Emit.Send       a chunk of the call's stream, when the host has given credit; the program waits for it
 *   Fed.Next        a value of the stream the host feeds, waited for; taking one gives the host a credit
 *   Async.Await     the program waits for the callback, and other requests are served meanwhile
 *   Throws.Raise    the call's condition
 *
 * A program that waits is a continuation kept: no thread waits with it. */
object Worker:
  type Row[X] = (Foreign + Emit % Json + Fed + Async + Throws % Condition)[X]
  type Function = List[Json] => Json ! Row

  /** An object kept for the host: its methods and attributes, by name. */
  trait Thing:
    def kind: String
    def methods: PartialFunction[(String, List[Json]), Json]
    def attrs: PartialFunction[String, Json]

  /** functions by name, and classes: the functions that make a `Thing` */
  final case class Module(functions: Map[String, Function], classes: Map[String, List[Json] => Thing] = Map.empty)

  /** the module served until the host closes the wire; with a `secret`, to a host that proves it has the same */
  def serve(module: Module, in: InputStream = System.in, out: OutputStream = System.out,
      secret: Option[String] = None): Unit =
    val worker = Worker(module, Link(in, out), secret)
    worker.hello()
    while worker.served() do ()

  /** a function that waits, and how to go on with it */
  private abstract class Waits:
    var waiting = Option.empty[() => Unit]
    def wake(): Unit =
      val go = waiting
      waiting = None
      go.foreach(_())

  /** a stream the host feeds: the values come and not yet taken; its function waits for one */
  private final class In(val id: Long) extends Waits:
    val values = mutable.Queue.empty[Json]
    var ended = false

  /** a stream this side feeds: the credit left; its function waits for more */
  private final class Out(val id: Long, var credit: Long, val input: Option[In]) extends Waits:
    var open = true

  /** what a running function answers to */
  private enum Task:
    /** a call: its value, or a ref to it when held */
    case Value(id: Json, held: Boolean)
    /** a step of a program: a node */
    case Program(id: Json, run: Long)
    /** a stream: chunks, then its end */
    case Stream(out: Out)

private final class Worker(module: Worker.Module, link: Link, secret: Option[String]):
  import Worker.{In, Out, Row, Task, Thing}

  private val kept = mutable.Map.empty[(Long, Long), Json => Json ! Row]
  private var counter = 0L
  private val held = mutable.Map.empty[Long, Json | Thing]
  private var refs = 0L
  private val streams = mutable.Map.empty[Long, Out]
  private val inputs = mutable.Map.empty[Long, In]
  private lazy val nonce = Auth.nonce()
  private var proven = secret.isEmpty
  private var closing = false

  def hello(): Unit =
    val speaks = Json.obj("format" -> Json.Arr(List(Json.Str("json"), Json.Str("cbor"))),
      "compress" -> Json.Arr(List(Json.Str("deflate"))), "mux" -> Json.Bool(true))
    val auth = Json.obj("scheme" -> Json.Str("hmac-sha256"), "nonce" -> Json.Str(nonce))
    link.send(Json.Obj(List("shim" -> Json.Int(Shim), "python" -> Json.Str("scala"), "speaks" -> speaks) ++
      Option.when(secret.isDefined)("auth" -> auth)))

  /** one request read and served; false at the end of the wire */
  def served(): Boolean =
    try link.receive() match
      case None => false
      case Some(request) =>
        // a callback may come on another thread: one at a time
        synchronized(serve(request))
        !closing
    catch
      case _: IOException => false
      case e: IllegalArgumentException =>
        synchronized(reply(Json.Null, failed(Condition("ValueError", String.valueOf(e.getMessage)))))
        true

  private def serve(request: Json): Unit =
    val id = request("id")
    try
      if !proven then authenticate(id, request)
      else request("op") match
        case Json.Str("call") => call(id, request)
        case Json.Str("program") => advance(Task.Program(id, run(request)), function(request("fn"))(arguments(request)))
        case Json.Str("continue") => continue(id, request)
        case Json.Str("forget") =>
          val run = this.run(request)
          kept.filterInPlace((key, _) => key._1 != run)
          reply(id, ok(Json.Null))
        case Json.Str("release") =>
          held -= number(request("ref"))
          reply(id, ok(Json.Null))
        case Json.Str("credit") =>
          reply(id, ok(Json.Null))
          streams.get(number(request("stream"))).foreach: out =>
            out.credit += number(request("credit"))
            out.wake()
        case Json.Str("chunk") =>
          val value = resolved(Escapes.dec(request("chunk")))
          reply(id, ok(Json.Null))
          inputs.get(number(request("stream"))).foreach: in =>
            in.values.enqueue(value)
            in.wake()
        case Json.Str("end") =>
          reply(id, ok(Json.Null))
          inputs.get(number(request("stream"))).foreach: in =>
            in.ended = true
            in.wake()
        case Json.Str("cancel") =>
          streams.get(number(request("stream"))).foreach(close)
          reply(id, ok(Json.Null))
        case Json.Str("configure") => configure(id, request)
        case op => throw Condition("ValueError", s"no operation $op")
    catch case e: Exception => reply(id, failed(conditionOf(e)))

  // ---- the answers

  private def reply(id: Json, answer: (String, Json)): Unit = link.send(Json.obj("id" -> id, answer))

  private def ok(value: Json): (String, Json) = "ok" -> value

  private def failed(c: Condition): (String, Json) =
    "condition" -> Json.obj("kind" -> Json.Str(c.kind), "message" -> Json.Str(c.message))

  private def conditionOf(e: Exception): Condition = e match
    case c: Condition => c
    case _ => Condition(e.getClass.getSimpleName, String.valueOf(e.getMessage))

  // ---- the request's parts

  private def number(tree: Json): Long = tree match
    case Json.Int(n) => n
    case other => throw Condition("ValueError", s"a number was expected: $other")

  private def run(request: Json): Long = number(request("run"))

  private def function(name: Json): Worker.Function = name match
    case Json.Str(fn) => module.functions.getOrElse(fn, throw Condition("LookupError", s"no function $fn"))
    case other => throw Condition("ValueError", s"a function's name was expected: $other")

  /** the arguments, as values */
  private def arguments(request: Json): List[Json] =
    Escapes.items(request("args")).map(arg => resolved(Escapes.dec(arg)))

  /** the value, with what each ref in it names: a ref crosses back as the value held */
  private def resolved(value: Json): Json = value match
    case Json.Ref(id, _) => heldAt(id) match
      case value: Json => value
      case thing: Thing => throw Condition("TypeError", s"ref $id is a ${thing.kind}: used by its methods, not given")
    case Json.Arr(values) => Json.Arr(values.map(resolved))
    case Json.Obj(fields) => Json.Obj(fields.map((name, field) => (name, resolved(field))))
    case plain => plain

  private def heldAt(ref: Long): Json | Thing = held.getOrElse(ref, throw Condition("LookupError",
    s"ref $ref is not held by this worker (released, or held by a process that is gone)"))

  /** the value or the object kept, and the ref that names it */
  private def hold(what: Json | Thing): Json =
    refs += 1
    held(refs) = what
    Escapes.enc(Json.Ref(refs, what match
      case thing: Thing => thing.kind
      case _: Json.Arr => "list"
      case _: Json.Obj => "dict"
      case _ => "value"
    ))

  // ---- the operations

  private def call(id: Json, request: Json): Unit =
    val args = arguments(request)
    val held = request("held") == Json.Bool(true)
    def answer(value: Json): Unit = reply(id, ok(if held then hold(value) else Escapes.enc(value)))
    (request("fn"), request("stream")) match
      case (name, Json.Int(stream)) => open(id, stream, request, function(name)(args))
      case (Json.Str(name), _) if module.classes.contains(name) =>
        if !held then throw Condition("TypeError", s"$name makes an object: it crosses only held, as a ref")
        reply(id, ok(hold(module.classes(name)(args))))
      case (name: Json.Str, _) => advance(Task.Value(id, held), function(name)(args))
      case (at, _) => (at("ref"), at("method"), at("attr")) match
        case (Json.Int(ref), Json.Str(name), _) => answer(member(ref, name, _.methods.lift((name, args))))
        case (Json.Int(ref), _, Json.Str(name)) => answer(member(ref, name, _.attrs.lift(name)))
        case _ => throw Condition("ValueError", s"a call's address is a name, a method or an attribute: $at")

  /** a method's answer or an attribute, of an object held */
  private def member(ref: Long, name: String, find: Thing => Option[Json]): Json = heldAt(ref) match
    case thing: Thing => find(thing).getOrElse(throw Condition("LookupError", s"a ${thing.kind} has no $name"))
    case _ => throw Condition("LookupError", s"ref $ref is a value: it has no $name")

  /** a call as a stream: answered at once, its chunks follow as the host gives credit */
  private def open(id: Json, stream: Long, request: Json, p: Json ! Row): Unit =
    val input = request("input")("id") match
      case Json.Int(fed) => Some(In(fed))
      case _ => None
    val credit = request("credit") match
      case Json.Int(n) => n
      case _ => 1L
    val out = Out(stream, credit, input)
    streams(stream) = out
    input.foreach(in => inputs(in.id) = in)
    reply(id, ok(Json.Null))
    advance(Task.Stream(out), p)

  private def continue(id: Json, request: Json): Unit =
    val (run, k) = (this.run(request), number(request("k")))
    if request("condition") != Json.Null then
      throw Condition("ValueError", "a program as data is continued with an answer, not a condition")
    val rest = kept.getOrElse((run, k), throw Condition("LookupError", s"no continuation $k of run $run"))
    advance(Task.Program(id, run), rest(resolved(Escapes.dec(request("answer")))))

  private def configure(id: Json, request: Json): Unit =
    val (format, compress) = (request("format"), request("compress"))
    if format != Json.Str("json") && format != Json.Str("cbor") then
      throw Condition("ValueError", s"this worker speaks the formats json, cbor; not $format")
    if compress != Json.Str("none") && compress != Json.Str("deflate") then
      throw Condition("ValueError", s"this worker speaks the compressions none, deflate; not $compress")
    // the answer goes as the request came; the next message is the first in the new way
    reply(id, ok(Json.obj("format" -> format, "compress" -> compress)))
    link.configure(format == Json.Str("cbor"), compress == Json.Str("deflate"))

  /** until the host has proved the secret, only its proof is heard; a wrong one closes the wire */
  private def authenticate(id: Json, request: Json): Unit = (request("op"), request("nonce"), request("mac")) match
    case (Json.Str("auth"), Json.Str(host), Json.Str(mac))
        if host.nonEmpty && Auth.same(mac, Auth.mac(secret.get, "client", nonce, host)) =>
      proven = true
      reply(id, ok(Json.obj("mac" -> Json.Str(Auth.mac(secret.get, "server", nonce, host)))))
    case (Json.Str("auth"), _, _) =>
      closing = true
      throw Condition("PermissionError", "authentication refused: the mac does not prove this worker's secret")
    case _ => throw Condition("PermissionError", "this worker requires hmac-sha256 authentication first")

  // ---- a function, run

  /** the task's program run until it ends or waits: for the host's answer, a credit, a value, a callback */
  private def advance(task: Task, start: Json ! Row): Unit =
    var next = Option(start)
    while next.isDefined do
      next =
        try turn(task, step(next.get))
        catch case e: Exception =>
          fail(task, conditionOf(e))
          None

  /** where the program came to: what goes to the host, and the rest of the program when it need not wait */
  private def turn(task: Task, at: Step[Row, Json]): Option[Json ! Row] = (at, task) match
    case (_, Task.Stream(out)) if !out.open => None
    case (Step.Done(value), Task.Value(id, held)) =>
      reply(id, ok(if held then hold(value) else Escapes.enc(value)))
      None
    case (Step.Done(value), Task.Program(id, _)) =>
      reply(id, ok(Json.obj("done" -> Escapes.enc(value))))
      None
    case (Step.Done(_), Task.Stream(out)) =>
      close(out)
      link.send(Json.obj("stream" -> Json.Int(out.id), "end" -> Json.Bool(true)))
      None
    // an operation: which effect's it is, its place in the row says
    case (asked: Step.Asked[Row, ?, ?, Json], _) =>
      asked.of[Throws % Condition].map(the => raised(task, the.op))
        .orElse(asked.of[Foreign].map(the => called(task, the.op, the.k)))
        .orElse(asked.of[Emit % Json].map(the => sent(task, the.op, the.k)))
        .orElse(asked.of[Fed].map(the => fed(task, the.op, the.k)))
        .orElse(asked.of[Async].map(the => awaited(task, the.op, the.k)))
        .get // the row has these five effects and no other

  private def raised[X](task: Task, op: Throws[Condition, X]): Option[Json ! Row] = op match
    case Throws.Raise(c) =>
      fail(task, c)
      None

  private def called[X](task: Task, op: Foreign[X], k: X => Json ! Row): Option[Json ! Row] = op match
    case Call(name, args) => task match
      case Task.Program(id, run) =>
        counter += 1
        kept((run, counter)) = k
        reply(id, ok(Json.obj("perform" -> Json.Str(name), "args" -> Json.Arr(args.map(Escapes.enc)),
          "k" -> Json.Int(counter))))
        None
      case _ => nothing(op)

  private def sent[X](task: Task, op: Emit[Json, X], k: X => Json ! Row): Option[Json ! Row] = op match
    case Emit.Send(value) => task match
      case Task.Stream(out) =>
        if out.credit > 0 then
          out.credit -= 1
          link.send(Json.obj("stream" -> Json.Int(out.id), "chunk" -> Escapes.enc(value)))
          Some(k(()))
        else
          out.waiting = Some(() => advance(task, Emit.send(value).flatMap(k)))
          None
      case _ => nothing(op)

  private def fed[X](task: Task, op: Fed[X], k: X => Json ! Row): Option[Json ! Row] = op match
    case Fed.Next => task match
      case Task.Stream(out) if out.input.isDefined =>
        val in = out.input.get
        if in.values.nonEmpty then
          // taken: the host may send one more
          link.send(Json.obj("stream" -> Json.Int(in.id), "credit" -> Json.Int(1)))
          Some(k(Some(in.values.dequeue())))
        else if in.ended then Some(k(None))
        else
          in.waiting = Some(() => advance(task, Fed.next.flatMap(k)))
          None
      case _ => Some(k(None))

  private def awaited[X](task: Task, op: Async[X], k: X => Json ! Row): Option[Json ! Row] = op match
    case Async.Await(register) =>
      register(x => synchronized(advance(task, k(x))))
      None

  private def nothing(op: Any): Nothing = throw Condition("LookupError", s"nothing here answers $op")

  private def fail(task: Task, c: Condition): Unit = task match
    case Task.Value(id, _) => reply(id, failed(c))
    case Task.Program(id, _) => reply(id, failed(c))
    case Task.Stream(out) =>
      if out.open then link.send(Json.obj("stream" -> Json.Int(out.id), failed(c)))
      close(out)

  /** the stream is over, by its end, its failure or the host's cancel: its function is not resumed */
  private def close(out: Out): Unit =
    out.open = false
    streams -= out.id
    out.input.foreach(in => inputs -= in.id)
