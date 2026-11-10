package okay.effects.scala.wire

import okay.effects.scala.*
import okay.effects.scala.std.{Emit, Throws}
import java.io.{IOException, InputStream, OutputStream}
import scala.collection.mutable

/** A FAR SIDE, as an effect: what a program of this side asks of it. The operations are the wire's moves and
 * nothing more; calls, objects, programs and streams are programs over them (below), and `Remote.run` is their
 * handler — the only place where bytes move. */
enum Far[X]:
  /** a request sent, not waited for: the number its answer will carry */
  case Send(op: String, fields: List[(String, Json)]) extends Far[Long]
  /** the answer to a request, waited for: `ok`'s value, or the far side's condition */
  case Receive(id: Long) extends Far[Either[Condition, Json]]
  /** the number of a new run: its continuations are forgotten when the program ends */
  case Run extends Far[Long]
  /** a call opened as a stream, with the values this side feeds it, if any: the stream's number */
  case Open(fn: String, args: List[Json], credit: Int, input: Option[LazyList[Json]])
      extends Far[Either[Condition, Long]]
  /** the stream's next chunk, waited for and paid back with a credit; none at its end */
  case Pull(stream: Long) extends Far[Either[Condition, Option[Json]]]

object Far:
  /** a far side's failure is raised here: a handler of `Throws % Condition` decides what it means */
  type Failing[X] = (Far + Throws % Condition)[X]

  private def orRaise[A, R[_]: Has[Throws % Condition]](answer: Either[Condition, A]): A ! R =
    answer.fold(Throws.raise, pure)

  /** a request sent, and its answer waited for */
  private def ask(op: String, fields: (String, Json)*): Json ! Failing =
    word(Send(op, fields.toList)).flatMap(id => word(Receive(id)).flatMap(orRaise))

  private def arguments(args: Seq[Json]): (String, Json) = "args" -> Json.Arr(args.toList.map(Escapes.enc))

  /** the far function's answer */
  def call(fn: String, args: Json*): Json ! Failing = start(fn, args*).flatMap(result)

  /** a call begun and not waited for: on a far side that speaks `mux`, others may be answered before it */
  def start(fn: String, args: Json*): Word[Far, Long] =
    word(Send("call", List("fn" -> Json.Str(fn), arguments(args))))

  /** the answer of a call begun */
  def result(id: Long): Json ! Failing =
    for answer <- word(Receive(id)); value <- orRaise(answer) yield Escapes.dec(value)

  /** the far function's answer kept on the far side: a ref to it */
  def hold(fn: String, args: Json*): Json.Ref ! Failing =
    ask("call", "fn" -> Json.Str(fn), arguments(args), "held" -> Json.Bool(true)).map(Escapes.dec).flatMap:
      case ref: Json.Ref => pure(ref)
      case other => Throws.raise(Condition("WireError", s"a ref was expected: $other"))

  /** a method of an object held */
  def method(ref: Json.Ref, name: String, args: Json*): Json ! Failing =
    ask("call", "fn" -> Json.obj("ref" -> Json.Int(ref.id), "method" -> Json.Str(name)), arguments(args))
      .map(Escapes.dec)

  /** an attribute of an object held */
  def attr(ref: Json.Ref, name: String): Json ! Failing =
    ask("call", "fn" -> Json.obj("ref" -> Json.Int(ref.id), "attr" -> Json.Str(name)), arguments(Nil))
      .map(Escapes.dec)

  /** what the ref names is dropped on the far side */
  def release(ref: Json.Ref): Unit ! Failing =
    ask("release", "ref" -> Json.Int(ref.id)).map(_ => ())

  /** THE FAR PROGRAM `fn`, as a program of this side: what it asks is asked here as a `Call`, and answered by this
   * side's handlers — once, many times, or never. `callbacks` names what this side offers, for a far side that
   * checks. */
  def program(fn: String, args: List[Json], callbacks: List[String] = Nil): Json ! Failing + Foreign =
    type R[X] = (Failing + Foreign)[X]
    word(Run).flatMap: number =>
      val run = "run" -> Json.Int(number)
      def node(ok: Json): Json ! R = ok("perform") match
        case Json.Str(name) =>
          word(Call(name, Escapes.items(ok("args")).map(Escapes.dec))).flatMap: answer =>
            ask("continue", run, "k" -> ok("k"), "answer" -> Escapes.enc(answer)).flatMap(node)
        case _ => pure(Escapes.dec(ok("done")))
      val offered = Option.when(callbacks.nonEmpty)("callbacks" -> Json.Arr(callbacks.map(Json.Str(_))))
      ask("program", List(run, "fn" -> Json.Str(fn), arguments(args)) ++ offered*).flatMap(node)

  /** THE FAR FUNCTION'S STREAM, as a stream of this side: it sends what the far side sends. The far side runs at
   * most `credit` chunks ahead of whoever takes them here. With an `input`, that stream is fed to the far function,
   * under the far side's credit, while this one is pulled. */
  def stream(fn: String, args: List[Json], credit: Int, input: Option[Unit ! Emit % Json] = None)
      : Unit ! Failing + Emit % Json =
    type R[X] = (Failing + Emit % Json)[X]
    def fed = input.map(Emit.pull(_).map(Escapes.enc))
    def rest(stream: Long): Unit ! R = word(Pull(stream)).flatMap: pulled =>
      orRaise(pulled).flatMap:
        case Some(chunk) => Emit.send(Escapes.dec(chunk)).flatMap(_ => rest(stream))
        case None => unit
    for
      opened <- defer(word(Open(fn, args.map(Escapes.enc), credit, fed)))
      stream <- orRaise(opened)
      _ <- rest(stream)
    yield ()

/** THE HOST'S END of a wire to a far side. Opening it is the handshake: the far side's hello, the proof of the
 * `secret` if there is one, and `configure` when CBOR or DEFLATE is asked for; each mismatch is refused by name. */
final class Remote(in: InputStream, out: OutputStream, cbor: Boolean = false, deflate: Boolean = false,
    secret: Option[String] = None, onClose: () => Unit = () => ()) extends AutoCloseable:
  private val link = Link(in, out)
  private val hello = link.receive().getOrElse(throw Condition("WireError", "the far side said nothing"))
  if hello("fatal") != Json.Null then throw Condition("WireError", s"the far side cannot run: ${hello("fatal")}")
  if hello("shim") != Json.Int(Shim) then throw Condition("Shim", s"the far side said $hello")

  /** whether the far side answers requests as they finish, not in the order they came */
  val mux: Boolean = hello("speaks")("mux") == Json.Bool(true)

  authenticate()
  configure()

  private def authenticate(): Unit = (hello("auth")("nonce"), secret) match
    case (Json.Str(far), Some(secret)) if hello("auth")("scheme") == Json.Str("hmac-sha256") =>
      val mine = Auth.nonce()
      link.send(Json.obj("op" -> Json.Str("auth"), "nonce" -> Json.Str(mine),
        "mac" -> Json.Str(Auth.mac(secret, "client", far, mine))))
      link.receive().map(_("ok")("mac")) match
        case Some(Json.Str(proof)) if Auth.same(proof, Auth.mac(secret, "server", far, mine)) => ()
        case _ => throw Condition("PermissionError", "the far side did not prove the secret")
    case (Json.Null, None) => ()
    case (Json.Null, Some(_)) =>
      throw Condition("PermissionError", "this side has a secret and the far side asks for no authentication")
    case _ => throw Condition("PermissionError", s"the far side asks for authentication: ${hello("auth")}")

  private def configure(): Unit = if cbor || deflate then
    def speaks(what: String, name: String): Unit =
      if !Escapes.items(hello("speaks")(what)).contains(Json.Str(name)) then
        throw Condition("WireError", s"the far side does not speak the $what $name: $hello")
    if cbor then speaks("format", "cbor")
    if deflate then speaks("compress", "deflate")
    link.send(Json.obj("op" -> Json.Str("configure"), "format" -> Json.Str(if cbor then "cbor" else "json"),
      "compress" -> Json.Str(if deflate then "deflate" else "none")))
    val answer = link.receive()
    if !answer.exists(_("ok") != Json.Null) then throw Condition("WireError", s"configure was refused: $answer")
    link.configure(cbor, deflate)

  /** values this side feeds a far function, and the credit the far side has given for them */
  private final class Feed(var values: LazyList[Json], var credit: Long)

  private var ids, numbers = 0L
  private var gone = false
  private val awaited = mutable.Set.empty[Long]
  private val answers = mutable.Map.empty[Long, Json]
  private val runs = mutable.Set.empty[Long]
  private val streams = mutable.Map.empty[Long, mutable.Queue[Json]]
  private val feeds = mutable.Map.empty[Long, Feed]

  private val lost = Condition("WorkerDied", "the far side's wire ended")

  /** a request written; its answer is kept only if somebody will wait for it */
  private def send(op: String, fields: (String, Json)*): Long =
    ids += 1
    try link.send(Json.Obj(("id" -> Json.Int(ids)) :: ("op" -> Json.Str(op)) :: fields.toList))
    catch case _: IOException => gone = true
    ids

  /** THE WIRE MOVES ONE STEP, while the program waits for something: a value is fed where the far side has given
   * credit; otherwise a message is read, and put where it is waited for. */
  private def turn(): Unit =
    feeds.find((_, feed) => feed.values.isEmpty || feed.credit > 0) match
      case Some((stream, feed)) if feed.values.isEmpty =>
        feeds -= stream
        send("end", "stream" -> Json.Int(stream))
      case Some((stream, feed)) =>
        feed.credit -= 1
        send("chunk", "stream" -> Json.Int(stream), "chunk" -> feed.values.head)
        feed.values = feed.values.tail
      case None =>
        val message = try link.receive() catch case _: IOException => None
        message match
          case None => gone = true
          case Some(m) => (m("id"), m("stream"), m("credit")) match
            case (Json.Int(id), _, _) => if awaited.remove(id) then answers(id) = m
            case (_, Json.Int(stream), Json.Int(credit)) => feeds.get(stream).foreach(_.credit += credit)
            case (_, Json.Int(stream), _) => streams.get(stream).foreach(_.enqueue(m))
            case _ => ()

  private def receive(id: Long): Either[Condition, Json] =
    while !gone && !answers.contains(id) do turn()
    answers.remove(id).toRight(lost).flatMap(answer => failure(answer).toLeft(answer("ok")))

  private def failure(message: Json): Option[Condition] = message("condition") match
    case Json.Null => None
    case c => Some(Condition(text(c("kind")), text(c("message"))))

  private def text(tree: Json): String = tree match
    case Json.Str(value) => value
    case other => other.toString

  private def open(call: Far.Open): Either[Condition, Long] =
    if !mux then Left(Condition("NotStreaming", "the far side claims no mux: a stream needs answers matched by id"))
    else
      numbers += 1
      val stream = numbers
      // the stream's own number is the far side's; this side's input to the same call has the number negated
      val input = call.input.map(_ => "input" -> Json.obj("id" -> Json.Int(-stream), "credit" -> Json.Int(call.credit)))
      streams(stream) = mutable.Queue.empty
      val id = send("call", List("fn" -> Json.Str(call.fn), "args" -> Json.Arr(call.args),
        "stream" -> Json.Int(stream), "credit" -> Json.Int(call.credit)) ++ input*)
      awaited += id
      receive(id) match
        case Left(c) =>
          streams -= stream
          Left(c)
        case Right(_) =>
          call.input.foreach(values => feeds(-stream) = Feed(values, call.credit))
          Right(stream)

  private def pull(stream: Long): Either[Condition, Option[Json]] = streams.get(stream) match
    case None => Left(Condition("LookupError", s"stream $stream is not open"))
    case Some(messages) =>
      while !gone && messages.isEmpty do turn()
      val message = if messages.isEmpty then Json.Null else messages.dequeue()
      if message == Json.Null || message("end") == Json.Bool(true) || failure(message).isDefined then
        streams -= stream
        feeds -= -stream
        if message == Json.Null then Left(lost) else failure(message).toLeft(None)
      else
        // taken: the far side may send one more
        send("credit", "stream" -> Json.Int(stream), "credit" -> Json.Int(1))
        Right(Some(message("chunk")))

  /** THE PROGRAM RUN to its value, its far operations done on this wire. When it ends, what it left open on the far
   * side is closed: its runs forgotten, its streams cancelled. Refs live on, until released. */
  def run[A](p: A ! Far): A =
    val handler = Handler.tail[Far, Pure, A, A](pure)([X] => (op: Far[X]) => op match
      case Far.Send(name, fields) =>
        val id = send(name, fields*)
        awaited += id
        id
      case Far.Receive(id) => receive(id)
      case Far.Run =>
        numbers += 1
        runs += numbers
        numbers
      case call: Far.Open => open(call)
      case Far.Pull(stream) => pull(stream))
    okay.effects.scala.run(handle(p.ensuring(closed), handler))

  /** what a program left open on the far side, closed: its finalizer */
  private def closed: Unit ! Far = pure:
    streams.keys.foreach(stream => send("cancel", "stream" -> Json.Int(stream)))
    runs.foreach(run => send("forget", "run" -> Json.Int(run)))
    streams.clear()
    feeds.clear()
    runs.clear()

  def close(): Unit =
    try out.close()
    catch case _: IOException => ()
    onClose()

object Remote:
  /** a far side started as a process, speaking the wire on its standard input and output; a `secret` is given to
   * it as OKAY_WIRE_SECRET */
  def start(command: Seq[String], cbor: Boolean = false, deflate: Boolean = false,
      secret: Option[String] = None): Remote =
    val builder = ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.INHERIT)
    secret.foreach(builder.environment().put("OKAY_WIRE_SECRET", _))
    val process = builder.start()
    try Remote(process.getInputStream, process.getOutputStream, cbor, deflate, secret, () => process.destroy())
    catch case e: Exception =>
      process.destroy()
      throw e
