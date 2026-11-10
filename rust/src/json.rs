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
                        let hex = self.text.get(self.at + 1..self.at + 5).and_then(|h| std::str::from_utf8(h).ok());
                        let code = hex.and_then(|h| u32::from_str_radix(h, 16).ok()).and_then(char::from_u32);
                        let c = code.ok_or_else(|| self.fail("four hex digits"))?;
                        out.extend_from_slice(c.encode_utf8(&mut [0; 4]).as_bytes());
                        self.at += 4;
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
        if digits.contains(['.', 'e', 'E']) {
            digits.parse().map(Json::Dec).map_err(|_| self.fail("a number"))
        } else {
            digits.parse().map(Json::Int).map_err(|_| self.fail("a value"))
        }
    }
}
