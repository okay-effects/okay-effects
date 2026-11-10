//! THE VALUE ESCAPES: what a JSON tree cannot say of a value, said by an object tagged `t`. A value is escaped as
//! it leaves and read back as it comes; the messages around it are plain JSON.

use crate::json::{Json, Ref};
use crate::wire::Condition;

/// integers are exact in every JSON reader only below this
const EXACT: i64 = 1 << 53;

fn tagged<const N: usize>(tag: &str, fields: [(&str, Json); N]) -> Json {
    let fields = fields.into_iter().map(|(name, value)| (name.to_string(), value));
    Json::Obj([("t".to_string(), Json::str(tag))].into_iter().chain(fields).collect())
}

/// The value as it crosses.
pub fn enc(value: &Json) -> Result<Json, Condition> {
    Ok(match value {
        Json::Int(n) if *n <= -EXACT || *n >= EXACT => tagged("int", [("v", Json::Str(n.to_string()))]),
        Json::Dec(x) if x.is_nan() => tagged("nan", []),
        Json::Dec(x) if x.is_infinite() => {
            return Err(Condition::new("ValueError", "an infinity does not cross the wire"));
        }
        // a whole float would come back an integer: the tag keeps 3.0 a float
        Json::Dec(x) if *x == x.round() && x.abs() < 1e15 => tagged("f", [("v", value.clone())]),
        Json::Arr(values) => Json::Arr(values.iter().map(enc).collect::<Result<_, _>>()?),
        Json::Obj(fields) => {
            let pair = |(name, field): &(String, Json)| Ok(Json::Arr(vec![Json::str(name), enc(field)?]));
            tagged("dict", [("kv", Json::Arr(fields.iter().map(pair).collect::<Result<_, _>>()?))])
        }
        Json::Ref(r) => tagged("ref", [("id", Json::Int(r.id)), ("type", Json::str(&r.kind))]),
        plain => plain.clone(),
    })
}

/// The value that crossed.
pub fn dec(tree: &Json) -> Result<Json, Condition> {
    let fields = match tree {
        Json::Arr(values) => return Ok(Json::Arr(values.iter().map(dec).collect::<Result<_, _>>()?)),
        Json::Obj(fields) => fields,
        plain => return Ok(plain.clone()),
    };
    let dict = |(name, field): &(String, Json)| Ok((name.clone(), dec(field)?));
    Ok(match (tree.get("t"), tree.get("v")) {
        (Json::Str(tag), _) if tag == "dict" => {
            let pair = |pair: &Json| match pair {
                Json::Arr(pair) => match pair.as_slice() {
                    [Json::Str(name), field] => Some(dec(field).map(|field| (name.clone(), field))),
                    _ => None,
                },
                _ => None,
            };
            Json::Obj(items(tree.get("kv")).iter().filter_map(pair).collect::<Result<_, _>>()?)
        }
        (Json::Str(tag), Json::Str(digits)) if tag == "int" => {
            Json::Int(digits.parse().map_err(|_| Condition::new("ValueError", format!("not an integer: {digits}")))?)
        }
        (Json::Str(tag), Json::Int(n)) if tag == "f" => Json::Dec(*n as f64),
        (Json::Str(tag), whole) if tag == "f" => whole.clone(),
        (Json::Str(tag), _) if tag == "nan" => Json::Dec(f64::NAN),
        (Json::Str(tag), _) if tag == "na" => Json::Null,
        (Json::Str(tag), _) if tag == "ref" => match (tree.get("id"), tree.get("type")) {
            (Json::Int(id), Json::Str(kind)) => Json::Ref(Ref { id: *id, kind: kind.clone() }),
            (Json::Int(id), _) => Json::Ref(Ref { id: *id, kind: String::new() }),
            _ => return Err(Condition::new("ValueError", format!("a ref without a number: {tree}"))),
        },
        (Json::Str(tag), _) => return Err(Condition::new("ValueError", format!("unknown tagged value: {tag}"))),
        // an object with no tag is a dict too: some far sides send theirs plain
        _ => Json::Obj(fields.iter().map(dict).collect::<Result<_, _>>()?),
    })
}

/// The values of an array; none of anything else.
pub fn items(tree: &Json) -> &[Json] {
    if let Json::Arr(values) = tree { values } else { &[] }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn odd() -> Json {
        Json::Arr(vec![
            Json::Int(9007199254740993),
            Json::Int(-9007199254740992),
            Json::Int(9007199254740991),
            Json::Dec(3.0),
            Json::Dec(-0.5),
            Json::Dec(1e300),
            Json::Null,
            Json::Bool(true),
            Json::str("é \"q\" \\ \n\t 日本 😀"),
            Json::obj([("b", Json::Int(1)), ("a", Json::Arr(vec![])), ("t", Json::Obj(vec![]))]),
        ])
    }

    fn shape(value: Json) -> String {
        enc(&value).unwrap().to_string()
    }

    #[test]
    fn a_value_comes_back_the_value_it_was() {
        let counter = Json::Ref(Ref { id: 7, kind: "t.Counter".to_string() });
        assert_eq!(dec(&enc(&odd()).unwrap()), Ok(odd()));
        assert_eq!(dec(&enc(&counter).unwrap()), Ok(counter));
        assert_eq!(dec(&Json::parse(&shape(odd())).unwrap()), Ok(odd()));
        assert!(matches!(dec(&enc(&Json::Dec(f64::NAN)).unwrap()), Ok(Json::Dec(x)) if x.is_nan()));
    }

    #[test]
    fn their_shapes_on_the_wire() {
        assert_eq!(shape(Json::Int(1 << 53)), r#"{"t":"int","v":"9007199254740992"}"#);
        assert_eq!(shape(Json::Int((1 << 53) - 1)), "9007199254740991");
        assert_eq!(shape(Json::Dec(3.0)), r#"{"t":"f","v":3.0}"#);
        assert_eq!(shape(Json::Dec(0.5)), "0.5");
        assert_eq!(shape(Json::Dec(1e15)), "1000000000000000.0");
        assert_eq!(shape(Json::Dec(f64::NAN)), r#"{"t":"nan"}"#);
        assert_eq!(shape(Json::obj([("a", Json::Dec(2.0))])), r#"{"t":"dict","kv":[["a",{"t":"f","v":2.0}]]}"#);
        let counter = Ref { id: 1, kind: "t.Counter".to_string() };
        assert_eq!(shape(Json::Ref(counter.clone())), r#"{"t":"ref","id":1,"type":"t.Counter"}"#);
        assert_eq!(Json::Ref(counter).to_string(), r#"{"t":"ref","id":1,"type":"t.Counter"}"#);
    }

    #[test]
    fn what_other_far_sides_send_and_what_is_refused() {
        // a whole float's `v` spelled as an integer, an object with no tag, R's absence, a ref with no type
        let theirs = Json::parse(r#"[{"t":"f","v":3},{"a":{"t":"na"}},{"t":"ref","id":2}]"#).unwrap();
        let ours = vec![Json::Dec(3.0), Json::obj([("a", Json::Null)]), Json::Ref(Ref { id: 2, kind: String::new() })];
        assert_eq!(dec(&theirs), Ok(Json::Arr(ours)));
        let kind = |refused: Result<Json, Condition>| refused.unwrap_err().kind;
        assert_eq!(kind(dec(&Json::parse(r#"{"t":"frame","cols":[]}"#).unwrap())), "ValueError");
        assert_eq!(kind(dec(&Json::parse(r#"[{"t":"bytes","v":"AA=="}]"#).unwrap())), "ValueError");
        assert_eq!(kind(dec(&Json::parse(r#"{"t":"int","v":"many"}"#).unwrap())), "ValueError");
        assert_eq!(kind(enc(&Json::Arr(vec![Json::Dec(f64::INFINITY)]))), "ValueError");
    }
}
