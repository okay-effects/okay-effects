package okay.algebraic.scala3.wire

/** JSON, as much as the wire needs: a value, its text, and the value of a text. */
enum Json:
  case Null
  case Bool(value: Boolean)
  case Int(value: Long)
  case Dec(value: Double)
  case Str(value: String)
  case Arr(values: List[Json])
  case Obj(fields: List[(String, Json)])

  /** a field of an object; `Null` when there is none */
  def apply(name: String): Json = this match
    case Obj(fields) => fields.collectFirst { case (`name`, value) => value }.getOrElse(Null)
    case _ => Null

  override def toString: String = this match
    case Null => "null"
    case Bool(value) => value.toString
    case Int(value) => value.toString
    case Dec(value) => value.toString
    case Str(value) => Json.quote(value)
    case Arr(values) => values.mkString("[", ",", "]")
    case Obj(fields) => fields.map((name, value) => Json.quote(name) + ":" + value).mkString("{", ",", "}")

object Json:
  def obj(fields: (String, Json)*): Json = Obj(fields.toList)

  private def quote(s: String): String =
    val out = StringBuilder("\"")
    s.foreach:
      case '"' => out ++= "\\\""
      case '\\' => out ++= "\\\\"
      case '\n' => out ++= "\\n"
      case '\r' => out ++= "\\r"
      case '\t' => out ++= "\\t"
      case c if c < ' ' => out ++= f"\\u${c.toInt}%04x"
      case c => out += c
    (out += '"').toString

  def parse(text: String): Json =
    val parser = Parser(text)
    val value = parser.value()
    parser.spaces()
    if parser.at < text.length then parser.fail("the end")
    value

  private final class Parser(text: String):
    var at = 0

    def fail(expected: String): Nothing =
      throw IllegalArgumentException(s"JSON: expected $expected at $at in: $text")

    def spaces(): Unit = while at < text.length && text(at).isWhitespace do at += 1

    private def take(word: String): Boolean =
      val there = text.startsWith(word, at)
      if there then at += word.length
      there

    def value(): Json =
      spaces()
      if take("null") then Null
      else if take("true") then Bool(true)
      else if take("false") then Bool(false)
      else if take("\"") then Str(string())
      else if take("[") then Arr(items("]")(() => value()))
      else if take("{") then Obj(items("}")(() => field()))
      else number()

    /** the items up to the closing bracket, commas between them */
    private def items[T](close: String)(item: () => T): List[T] =
      val found = List.newBuilder[T]
      spaces()
      if !take(close) then
        found += item()
        spaces()
        while take(",") do
          found += item()
          spaces()
        if !take(close) then fail(close)
      found.result()

    private def field(): (String, Json) =
      spaces()
      if !take("\"") then fail("a field's name")
      val name = string()
      spaces()
      if !take(":") then fail(":")
      (name, value())

    private def string(): String =
      val out = StringBuilder()
      while at < text.length && text(at) != '"' do
        if text(at) == '\\' then
          at += 1
          text(at) match
            case 'n' => out += '\n'
            case 'r' => out += '\r'
            case 't' => out += '\t'
            case 'b' => out += '\b'
            case 'f' => out += '\f'
            case 'u' =>
              out += Integer.parseInt(text.substring(at + 1, at + 5), 16).toChar
              at += 4
            case c => out += c
        else out += text(at)
        at += 1
      if at >= text.length then fail("the end of a string")
      at += 1
      out.toString

    private def number(): Json =
      val from = at
      while at < text.length && "+-.eE0123456789".contains(text(at)) do at += 1
      val digits = text.substring(from, at)
      if digits.isEmpty then fail("a value")
      if digits.exists(".eE".contains(_)) then Dec(digits.toDouble) else Int(digits.toLong)
