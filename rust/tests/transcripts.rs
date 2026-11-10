//! THE TRANSCRIPTS of wire/: what a worker must answer, line for line. Here the Rust worker (the demonstration's)
//! is held to them; `examples/transcripts.rs` holds any worker to them, by the same `check`.

use std::io::{Read, Write};
use std::process::{Command, Stdio};

use okay_effects::json::Json;

pub const NAMES: [&str; 6] = ["transcript", "calls", "programs", "streams", "mux", "configure"];

/// Equal as JSON: fields in any order, a number however it is spelled; "*" expected is anything.
fn same(expected: &Json, got: &Json) -> bool {
    match (expected, got) {
        (Json::Str(any), _) if any == "*" => true,
        (Json::Obj(a), Json::Obj(b)) => {
            a.len() == b.len()
                && a.iter().all(|(name, value)| b.iter().any(|(n, found)| n == name && same(value, found)))
        }
        (Json::Arr(a), Json::Arr(b)) => a.len() == b.len() && a.iter().zip(b).all(|(value, found)| same(value, found)),
        (Json::Int(a), Json::Dec(b)) | (Json::Dec(b), Json::Int(a)) => *a as f64 == *b,
        _ => expected == got,
    }
}

/// The transcript `wire/<name>.txt` asked of a worker started by `command`: its `>` lines sent, and all the
/// worker says compared with its `<` lines. How many lines were answered, or what went wrong.
pub fn check(name: &str, command: &[String]) -> Result<usize, String> {
    let path = format!("{}/../wire/{name}.txt", env!("CARGO_MANIFEST_DIR"));
    let text = std::fs::read_to_string(&path).map_err(|e| format!("{path}: {e}"))?;
    let lines = |mark: &str| text.lines().filter_map(|line| line.strip_prefix(mark)).collect::<Vec<_>>();
    let asked = lines("> ").join("\n") + "\n";
    let expected = lines("< ").into_iter().map(Json::parse).collect::<Result<Vec<_>, _>>()?;

    let mut worker = Command::new(&command[0]);
    worker.args(&command[1..]).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null());
    let mut worker = worker.spawn().map_err(|e| format!("{}: {e}", command[0]))?;
    // written from another thread, and the input closed: a worker that answers as it reads is not held up
    let mut input = worker.stdin.take().expect("the worker's input");
    let writer = std::thread::spawn(move || input.write_all(asked.as_bytes()));
    let mut said = String::new();
    worker.stdout.take().expect("the worker's output").read_to_string(&mut said).map_err(|e| e.to_string())?;
    let _ = (writer.join(), worker.wait());

    // the first transcript, the oldest, leaves the hello out
    let hello = expected.first().is_some_and(|first| first.get("shim") != &Json::Null);
    let mut said = said.lines().skip(if hello { 0 } else { 1 });
    for (i, want) in expected.iter().enumerate() {
        let got = said.next().ok_or_else(|| format!("answer {}: expected {want}, got nothing", i + 1))?;
        if !Json::parse(got).is_ok_and(|got| same(want, &got)) {
            return Err(format!("answer {}: expected {want}, got {got}", i + 1));
        }
    }
    match said.next() {
        Some(more) => Err(format!("after the last answer expected, the worker said {more}")),
        None => Ok(expected.len()),
    }
}

/// the demonstration, which `cargo test` builds beside this test: target/<profile>/examples/demo
#[cfg(test)]
fn demo() -> Vec<String> {
    let test = std::env::current_exe().expect("this test's own path");
    let demo = test.parent().and_then(|deps| deps.parent()).expect("target/<profile>/deps").join("examples/demo");
    vec![demo.to_string_lossy().into_owned(), "worker".to_string()]
}

#[test]
fn the_worker_answers_every_transcript() {
    for name in NAMES {
        if let Err(why) = check(name, &demo()) {
            panic!("wire/{name}.txt: {why}");
        }
    }
}

#[test]
fn the_checker_tells_a_worker_that_does_not_answer_so() {
    let cat = ["cat".to_string()];
    assert!(check("calls", &cat).unwrap_err().starts_with("answer 1: expected "));
    assert!(check("no such transcript", &cat).is_err());
}
