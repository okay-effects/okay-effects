//! CBOR (RFC 8949), the part the wire uses: integers, floats, text, arrays and maps of a known length, true, false,
//! null. The same tree as the JSON; anything else is refused by name.

use crate::json::Json;
use crate::wire::escapes;

pub fn encode(tree: &Json) -> Vec<u8> {
    let mut out = Vec::new();
    write(&mut out, tree);
    out
}

fn write(out: &mut Vec<u8>, tree: &Json) {
    match tree {
        Json::Null => out.push(0xf6),
        Json::Bool(value) => out.push(if *value { 0xf5 } else { 0xf4 }),
        Json::Int(n) if *n >= 0 => head(out, 0, *n as u64),
        Json::Int(n) => head(out, 1, !*n as u64),
        Json::Dec(x) => {
            out.push(0xfb);
            out.extend_from_slice(&x.to_be_bytes());
        }
        Json::Str(text) => string(out, text),
        Json::Arr(values) => {
            head(out, 4, values.len() as u64);
            values.iter().for_each(|value| write(out, value));
        }
        Json::Obj(fields) => {
            head(out, 5, fields.len() as u64);
            for (name, value) in fields {
                string(out, name);
                write(out, value);
            }
        }
        // a ref is written as its escape, which cannot fail
        Json::Ref(_) => write(out, &escapes::enc(tree).unwrap_or(Json::Null)),
    }
}

fn string(out: &mut Vec<u8>, text: &str) {
    head(out, 3, text.len() as u64);
    out.extend_from_slice(text.as_bytes());
}

/// the major type and its argument, in the fewest bytes
fn head(out: &mut Vec<u8>, major: u8, n: u64) {
    let m = major << 5;
    match n {
        0..24 => out.push(m | n as u8),
        24..0x100 => out.extend_from_slice(&[m | 24, n as u8]),
        0x100..0x1_0000 => {
            out.push(m | 25);
            out.extend_from_slice(&(n as u16).to_be_bytes());
        }
        0x1_0000..0x1_0000_0000 => {
            out.push(m | 26);
            out.extend_from_slice(&(n as u32).to_be_bytes());
        }
        _ => {
            out.push(m | 27);
            out.extend_from_slice(&n.to_be_bytes());
        }
    }
}

pub fn decode(bytes: &[u8]) -> Result<Json, String> {
    let mut input = Input { bytes };
    let tree = input.read()?;
    if input.bytes.is_empty() { Ok(tree) } else { refuse(format!("{} bytes after the message", input.bytes.len())) }
}

fn refuse<T>(why: impl AsRef<str>) -> Result<T, String> {
    Err(format!("CBOR: {}", why.as_ref()))
}

/// the bytes not yet read
struct Input<'a> {
    bytes: &'a [u8],
}

impl Input<'_> {
    fn take<const N: usize>(&mut self) -> Result<[u8; N], String> {
        let Some((taken, rest)) = self.bytes.split_first_chunk() else { return refuse("the message is cut short") };
        self.bytes = rest;
        Ok(*taken)
    }

    fn read(&mut self) -> Result<Json, String> {
        let [first] = self.take()?;
        let (major, info) = (first >> 5, first & 0x1f);
        if major == 7 {
            return Ok(match info {
                20 => Json::Bool(false),
                21 => Json::Bool(true),
                22 | 23 => Json::Null,
                25 => Json::Dec(half(u16::from_be_bytes(self.take()?))),
                26 => Json::Dec(f32::from_be_bytes(self.take()?) as f64),
                27 => Json::Dec(f64::from_be_bytes(self.take()?)),
                other => return refuse(format!("the simple value {other} is not in the wire's subset")),
            });
        }
        let n = match info {
            0..24 => info as u64,
            24 => u8::from_be_bytes(self.take()?) as u64,
            25 => u16::from_be_bytes(self.take()?) as u64,
            26 => u32::from_be_bytes(self.take()?) as u64,
            27 => u64::from_be_bytes(self.take()?),
            other => return refuse(format!("an indefinite or reserved length ({other}) is not in the wire's subset")),
        };
        let Ok(n) = i64::try_from(n) else { return refuse("an integer or a length beyond 63 bits") };
        Ok(match major {
            0 => Json::Int(n),
            1 => Json::Int(-1 - n),
            3 => Json::Str(self.text(n)?),
            4 => Json::Arr((0..self.count(n)?).map(|_| self.read()).collect::<Result<_, _>>()?),
            5 => Json::Obj((0..self.count(n)?).map(|_| self.field()).collect::<Result<_, _>>()?),
            other => refuse(format!("the major type {other} (byte strings, tags) is not in the wire's subset"))?,
        })
    }

    fn field(&mut self) -> Result<(String, Json), String> {
        match self.read()? {
            Json::Str(name) => Ok((name, self.read()?)),
            other => refuse(format!("a map's key that is not text: {other}")),
        }
    }

    /// a count of items that the bytes left could hold: a lying length is refused before it is allocated
    fn count(&self, n: i64) -> Result<usize, String> {
        if n as usize > self.bytes.len() { refuse("the message is cut short") } else { Ok(n as usize) }
    }

    fn text(&mut self, n: i64) -> Result<String, String> {
        let (text, rest) = self.bytes.split_at(self.count(n)?);
        self.bytes = rest;
        String::from_utf8(text.to_vec()).or_else(|_| refuse("a text that is not UTF-8"))
    }
}

/// a float of 16 bits (IEEE 754 binary16): a sign, 5 bits of exponent, 10 of fraction
fn half(bits: u16) -> f64 {
    let (exponent, fraction) = ((bits >> 10) & 0x1f, (bits & 0x3ff) as f64);
    let value = match exponent {
        0 => fraction * 2f64.powi(-24),
        31 if fraction == 0.0 => f64::INFINITY,
        31 => f64::NAN,
        _ => (1024.0 + fraction) * 2f64.powi(exponent as i32 - 25),
    };
    if bits & 0x8000 == 0 { value } else { -value }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bytes(hex: &str) -> Vec<u8> {
        (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..(i + 2).min(hex.len())], 16).unwrap()).collect()
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }

    fn ints(values: &[i64]) -> Json {
        Json::Arr(values.iter().map(|n| Json::Int(*n)).collect())
    }

    #[test]
    fn the_examples_of_rfc_8949_appendix_a() {
        let examples = [
            ("00", Json::Int(0)),
            ("17", Json::Int(23)),
            ("1818", Json::Int(24)),
            ("1864", Json::Int(100)),
            ("1903e8", Json::Int(1000)),
            ("1a000f4240", Json::Int(1000000)),
            ("1b000000e8d4a51000", Json::Int(1000000000000)),
            ("20", Json::Int(-1)),
            ("3863", Json::Int(-100)),
            ("3903e7", Json::Int(-1000)),
            ("3b7fffffffffffffff", Json::Int(i64::MIN)),
            ("fb3ff199999999999a", Json::Dec(1.1)),
            ("fbc010666666666666", Json::Dec(-4.1)),
            ("f4", Json::Bool(false)),
            ("f5", Json::Bool(true)),
            ("f6", Json::Null),
            ("60", Json::str("")),
            ("6449455446", Json::str("IETF")),
            ("62c3bc", Json::str("ü")),
            ("64f0908591", Json::str("𐅑")),
            ("80", ints(&[])),
            ("83010203", ints(&[1, 2, 3])),
            ("a0", Json::Obj(vec![])),
            ("a26161016162820203", Json::obj([("a", Json::Int(1)), ("b", ints(&[2, 3]))])),
        ];
        for (text, tree) in examples {
            assert_eq!(hex(&encode(&tree)), text);
            assert_eq!(decode(&bytes(text)), Ok(tree));
        }
        // read and never written: floats of 16 and 32 bits, `undefined`
        assert_eq!(decode(&bytes("f93c00")), Ok(Json::Dec(1.0)));
        assert_eq!(decode(&bytes("f93e00")), Ok(Json::Dec(1.5)));
        assert_eq!(decode(&bytes("f9c400")), Ok(Json::Dec(-4.0)));
        assert_eq!(decode(&bytes("f90001")), Ok(Json::Dec(5.960464477539063e-8)));
        assert_eq!(decode(&bytes("f97c00")), Ok(Json::Dec(f64::INFINITY)));
        assert_eq!(decode(&bytes("fa47c35000")), Ok(Json::Dec(100000.0)));
        assert_eq!(decode(&bytes("f7")), Ok(Json::Null));
    }

    #[test]
    fn a_message_there_and_back_and_the_frame_of_the_readme() {
        let odd = r#"[{"t":"int","v":"9007199254740993"},{"t":"f","v":3.0},-0.5,1e300,null,true,"é \"q\" 日本 😀"]"#;
        let message = Json::obj([("id", Json::Int(1)), ("ok", Json::parse(odd).unwrap())]);
        assert_eq!(decode(&encode(&message)), Ok(message));
        let call = Json::parse(r#"{"id":1,"op":"call","fn":"t:add","args":[2,3]}"#).unwrap();
        assert_eq!(
            hex(&encode(&call)),
            "a462696401626f706463616c6c62666e65743a61646464617267738202 03".replace(' ', "")
        );
    }

    #[test]
    fn what_is_not_in_the_subset_is_refused_by_name() {
        // a byte string, an indefinite length, a tag, a key that is no text, cut short, 64 bits, a simple value,
        // bytes after the message, a text that is not UTF-8, a length that lies
        let refused = ["4100", "9f01ff", "c074", "a10100", "8301", "1bffffffffffffffff", "f8ff", "0000", "61ff", "9a"];
        for text in refused.iter().chain(&["9affffffff01", "7b7fffffffffffffff"]) {
            let why = decode(&bytes(text)).unwrap_err();
            assert!(why.starts_with("CBOR: "), "{text}: {why}");
        }
    }
}
