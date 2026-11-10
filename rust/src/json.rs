//! JSON, as much as the wire needs: a value, its text, and the value of a text.

use std::fmt;

#[derive(Clone, Debug, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Int(i64),
    Dec(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
    /// not JSON: something the far side keeps, known here by a number. On the wire it is the escape `ref`.
    Ref(Ref),
}

/// What a far side keeps for this one: its number there, and the kind of what is kept.
#[derive(Clone, Debug, PartialEq)]
pub struct Ref {
    pub id: i64,
    pub kind: String,
}

impl Json {
    pub fn obj<const N: usize>(fields: [(&str, Json); N]) -> Json {
        Json::Obj(fields.into_iter().map(|(name, value)| (name.to_string(), value)).collect())
    }

    pub fn str(value: &str) -> Json {
        Json::Str(value.to_string())
    }

    /// A field of an object; `Null` when there is none.
    pub fn get(&self, name: &str) -> &Json {
        match self {
            Json::Obj(fields) => fields.iter().find(|(n, _)| n == name).map_or(&Json::Null, |(_, value)| value),
            _ => &Json::Null,
        }
    }

    pub fn parse(text: &str) -> Result<Json, String> {
        let mut parser = Parser { text: text.as_bytes(), at: 0 };
        let value = parser.value()?;
        parser.spaces();
        if parser.at < parser.text.len() { Err(parser.fail("the end")) } else { Ok(value) }
    }
}

fn quote(s: &str, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    write!(f, "\"")?;
    for c in s.chars() {
        match c {
            '"' => write!(f, "\\\"")?,
            '\\' => write!(f, "\\\\")?,
            '\n' => write!(f, "\\n")?,
            '\r' => write!(f, "\\r")?,
            '\t' => write!(f, "\\t")?,
            c if c < ' ' => write!(f, "\\u{:04x}", c as u32)?,
            c => write!(f, "{c}")?,
        }
    }
    write!(f, "\"")
}

impl fmt::Display for Json {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Json::Null => write!(f, "null"),
            Json::Bool(value) => write!(f, "{value}"),
            Json::Int(value) => write!(f, "{value}"),
            Json::Dec(value) => write!(f, "{value:?}"),
            Json::Str(value) => quote(value, f),
            Json::Arr(values) => {
                write!(f, "[")?;
                for (i, value) in values.iter().enumerate() {
                    write!(f, "{}{value}", if i > 0 { "," } else { "" })?;
                }
                write!(f, "]")
            }
            Json::Obj(fields) => {
                write!(f, "{{")?;
                for (i, (name, value)) in fields.iter().enumerate() {
                    write!(f, "{}", if i > 0 { "," } else { "" })?;
                    quote(name, f)?;
                    write!(f, ":{value}")?;
                }
                write!(f, "}}")
            }
            Json::Ref(r) => {
                let fields = [("t", Json::str("ref")), ("id", Json::Int(r.id)), ("type", Json::str(&r.kind))];
                write!(f, "{}", Json::obj(fields))
            }
        }
    }
}

struct Parser<'a> {
    text: &'a [u8],
    at: usize,
}

impl Parser<'_> {
    fn fail(&self, expected: &str) -> String {
        format!("JSON: expected {expected} at {} in: {}", self.at, String::from_utf8_lossy(self.text))
    }

    fn spaces(&mut self) {
        while self.at < self.text.len() && self.text[self.at].is_ascii_whitespace() {
            self.at += 1;
        }
    }

    fn take(&mut self, word: &str) -> bool {
        let there = self.text[self.at..].starts_with(word.as_bytes());
        if there {
            self.at += word.len();
        }
        there
    }

    fn value(&mut self) -> Result<Json, String> {
        self.spaces();
        if self.take("null") {
            Ok(Json::Null)
        } else if self.take("true") {
            Ok(Json::Bool(true))
        } else if self.take("false") {
            Ok(Json::Bool(false))
        } else if self.take("\"") {
            Ok(Json::Str(self.string()?))
        } else if self.take("[") {
            Ok(Json::Arr(self.items("]", Parser::value)?))
        } else if self.take("{") {
            Ok(Json::Obj(self.items("}", Parser::field)?))
        } else {
            self.number()
        }
    }

    /// The items up to the closing bracket, commas between them.
    fn items<T>(&mut self, close: &str, item: fn(&mut Self) -> Result<T, String>) -> Result<Vec<T>, String> {
        let mut found = Vec::new();
        self.spaces();
        if self.take(close) {
            return Ok(found);
        }
        loop {
            found.push(item(self)?);
            self.spaces();
            if !self.take(",") {
                break;
            }
        }
        if self.take(close) { Ok(found) } else { Err(self.fail(close)) }
    }

    fn field(&mut self) -> Result<(String, Json), String> {
        self.spaces();
        if !self.take("\"") {
            return Err(self.fail("a field's name"));
        }
        let name = self.string()?;
        self.spaces();
        if !self.take(":") {
            return Err(self.fail(":"));
        }
        Ok((name, self.value()?))
    }

    fn string(&mut self) -> Result<String, String> {
        let mut out = Vec::new();
        while self.at < self.text.len() && self.text[self.at] != b'"' {
            if self.text[self.at] == b'\\' {
                self.at += 1;
                match self.text.get(self.at) {
                    Some(b'n') => out.push(b'\n'),
                    Some(b'r') => out.push(b'\r'),
                    Some(b't') => out.push(b'\t'),
                    Some(b'b') => out.push(8),
                    Some(b'f') => out.push(12),
                    Some(b'u') => {
                        let mut code = self.hex()?;
                        // a character beyond 16 bits is written as two: a surrogate pair
                        if (0xd800..0xdc00).contains(&code) && self.text[self.at + 1..].starts_with(b"\\u") {
                            let high = self.at;
                            self.at += 2;
                            match self.hex()? {
                                low @ 0xdc00..0xe000 => code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00),
                                _ => self.at = high,
                            }
                        }
                        let c = char::from_u32(code).unwrap_or(char::REPLACEMENT_CHARACTER);
                        out.extend_from_slice(c.encode_utf8(&mut [0; 4]).as_bytes());
                    }
                    Some(c) => out.push(*c),
                    None => return Err(self.fail("the end of a string")),
                }
            } else {
                out.push(self.text[self.at]);
            }
            self.at += 1;
        }
        if self.at >= self.text.len() {
            return Err(self.fail("the end of a string"));
        }
        self.at += 1;
        String::from_utf8(out).map_err(|_| self.fail("text in UTF-8"))
    }

    fn number(&mut self) -> Result<Json, String> {
        let from = self.at;
        while self.at < self.text.len() && b"+-.eE0123456789".contains(&self.text[self.at]) {
            self.at += 1;
        }
        let digits = std::str::from_utf8(&self.text[from..self.at]).unwrap_or("");
        // with a fraction or an exponent it is a float; so is an integer too long for 64 bits
        match digits.parse() {
            Ok(whole) => Ok(Json::Int(whole)),
            Err(_) => digits.parse().map(Json::Dec).map_err(|_| self.fail("a value")),
        }
    }

    /// The four hex digits after a `\u`, which `at` is on; `at` is left on the last of them.
    fn hex(&mut self) -> Result<u32, String> {
        let digits = self.text.get(self.at + 1..self.at + 5).and_then(|hex| std::str::from_utf8(hex).ok());
        let code = digits.and_then(|hex| u32::from_str_radix(hex, 16).ok());
        let code = code.ok_or_else(|| self.fail("four hex digits"))?;
        self.at += 4;
        Ok(code)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_text_is_read_to_the_value_that_prints_it() {
        let text = r#"{"a":[1,-2,3.5,1e-7,true,false,null],"b":{"c":"x\ny \"z\" \\ é"},"d":[]}"#;
        assert_eq!(Json::parse(text).unwrap().to_string(), text);
        let spaced = Json::parse(r#" { "a" : [ 1 , 2 ] } "#);
        assert_eq!(spaced, Ok(Json::obj([("a", Json::Arr(vec![Json::Int(1), Json::Int(2)]))])));
        assert!(Json::parse(r#"{"a":1} x"#).is_err());
        assert!(Json::parse("").is_err());
    }

    #[test]
    fn a_number_with_a_fraction_or_an_exponent_is_a_float_any_other_an_integer() {
        assert_eq!(Json::parse("3.0"), Ok(Json::Dec(3.0)));
        assert_eq!(Json::parse("3"), Ok(Json::Int(3)));
        assert_eq!(Json::parse("-2e3"), Ok(Json::Dec(-2000.0)));
        assert_eq!(Json::parse("9223372036854775807"), Ok(Json::Int(i64::MAX)));
        // an integer too long for 64 bits is a float
        assert_eq!(Json::parse("9223372036854775808"), Ok(Json::Dec(9223372036854775808.0)));
        assert_eq!(Json::Dec(3.0).to_string(), "3.0");
        assert_eq!(Json::Dec(1e300).to_string(), "1e300");
    }

    #[test]
    fn a_character_escaped_in_one_unit_of_16_bits_or_in_two() {
        assert_eq!(Json::parse(r#""é😀""#), Ok(Json::str("é😀")));
        assert_eq!(Json::parse(r#""\ud83d!\ud83dA""#), Ok(Json::str("\u{fffd}!\u{fffd}A")));
        assert_eq!(Json::str("a\u{1}\t😀").to_string(), r#""a\u0001\t😀""#);
        assert!(Json::parse(r#""\u00"#).is_err());
    }
}
