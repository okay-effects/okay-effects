package okay.algebraic.scala3.wire

import okay.algebraic.scala3.*
import java.io.{BufferedReader, InputStreamReader, PrintStream}
import scala.collection.mutable

/** THE OPERATION THAT CROSSES a process: a name and arguments in JSON, answered in JSON. A program written over it
 * runs in one process, in one language; its handlers may be in another. Only this data crosses — a continuation
 * stays where it was made, known outside by a number. The messages are the okay wire's (SPEC.md, "The wire"). */
sealed trait Foreign
final case class Call(name: String, args: List[Json]) extends Foreign, Op[Foreign, Json]

def call(name: String, args: Json*): Json ! Foreign = perform(Call(name, args.toList))

/** The far side's failure, by name. */
final class Condition(val kind: String, message: String) extends RuntimeException(s"$kind: $message")

val Shim = 9L

/** THE FAR SIDE: programs by name, run a step at a time for a host; their continuations kept until forgotten. */
object Worker:
  type Programs = Map[String, List[Json] => Json ! Foreign]

  def serve(programs: Programs, in: BufferedReader = BufferedReader(InputStreamReader(System.in)),
      out: PrintStream = System.out): Unit =
    val kept = mutable.Map.empty[(Long, Long), Rest[Foreign, Json, Json]]
    var counter = 0L

    def node(run: Long, p: Json ! Foreign): Json = step(p) match
      case Step.Done(value) => Json.obj("ok" -> Json.obj("done" -> value))
      case Step.Asked(Call(name, args), k) =>
        counter += 1
        kept((run, counter)) = k
        Json.obj("ok" -> Json.obj("perform" -> Json.Str(name), "args" -> Json.Arr(args), "k" -> Json.Int(counter)))
      case Step.Asked(op, _) => throw NoHandler(op)

    def condition(kind: String, message: String): Json =
      Json.obj("condition" -> Json.obj("kind" -> Json.Str(kind), "message" -> Json.Str(message)))

    def answer(m: Json): Json = (m("op"), m("run"), m("fn"), m("k")) match
      case (Json.Str("program"), Json.Int(run), Json.Str(fn), _) =>
        val args = m("args") match { case Json.Arr(values) => values; case _ => Nil }
        programs.get(fn).fold(condition("LookupError", s"no program $fn"))(program => node(run, program(args)))
      case (Json.Str("continue"), Json.Int(run), _, Json.Int(k)) =>
        kept.get((run, k)).fold(condition("LookupError", s"no continuation $k of run $run"))(rest =>
          node(run, rest(m("answer"))))
      case (Json.Str("forget"), Json.Int(run), _, _) =>
        kept.filterInPlace((key, _) => key._1 != run)
        Json.obj("ok" -> Json.Null)
      case (op, _, _, _) => condition("LookupError", s"no operation $op")

    out.println(Json.obj("shim" -> Json.Int(Shim), "scala" -> Json.Str("okay-algebraic 0.1.0"),
      "speaks" -> Json.obj("format" -> Json.Arr(List(Json.Str("json"))))))
    Iterator.continually(in.readLine()).takeWhile(_ != null).filter(_.nonEmpty).foreach: line =>
      val m = Json.parse(line)
      val reply =
        try answer(m)
        catch case e: Exception => condition(e.getClass.getSimpleName, String.valueOf(e.getMessage))
      reply match
        case Json.Obj(fields) => out.println(Json.Obj(("id" -> m("id")) :: fields))
        case other => out.println(other)

/** THE HOST'S VIEW of a far side: a process speaking the wire on its standard input and output. A program of it is a
 * program here — what it asks is asked here as a `Call`, and answered by this side's handlers. */
final class Remote(command: String*) extends AutoCloseable:
  private val process = ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.INHERIT).start()
  private val in = process.inputReader()
  private val out = PrintStream(process.getOutputStream, true)
  private var ids, runs = 0L

  private val hello = Json.parse(in.readLine())
  if hello("shim") != Json.Int(Shim) then throw Condition("Shim", s"the far side said $hello")

  private def ask(op: String, fields: (String, Json)*): Json =
    ids += 1
    out.println(Json.Obj(("id" -> Json.Int(ids)) :: ("op" -> Json.Str(op)) :: fields.toList))
    val reply = Json.parse(in.readLine())
    reply("condition") match
      case Json.Null => reply("ok")
      case failed => throw Condition(failed("kind").toString, failed("message").toString)

  /** the far side's program `fn`, as a program of this side */
  def program(fn: String, args: Json*): Json ! Foreign = defer:
    runs += 1
    val run = Json.Int(runs)
    node(run, ask("program", "run" -> run, "fn" -> Json.Str(fn), "args" -> Json.Arr(args.toList)))

  private def node(run: Json, ok: Json): Json ! Foreign = (ok("perform"), ok("args")) match
    case (Json.Str(name), Json.Arr(args)) =>
      perform(Call(name, args)).flatMap(answer => node(run, ask("continue", "run" -> run, "k" -> ok("k"),
        "answer" -> answer)))
    case _ => pure(ok("done"))

  def close(): Unit = process.destroy()
