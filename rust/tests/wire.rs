//! The wire, both ends: this side's host with the demonstration's worker in another process, and each end alone
//! with what the other might say. The codecs have their tests beside them (src/wire/); the worker alone is held
//! to the transcripts (tests/transcripts.rs).

use std::cell::RefCell;
use std::io::{Cursor, Write, sink};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::rc::Rc;

use okay_effects::effects::{Emit, Throws};
use okay_effects::json::Json;
use okay_effects::wire::{Asking, Condition, Failing, Far, Foreign, Opening, Remote, Streaming};
use okay_effects::{Comp, Handler, K, Prog, perform, pure};

/// the demonstration, which `cargo test` builds beside this test: target/<profile>/examples/demo
fn demo() -> PathBuf {
    let test = std::env::current_exe().expect("this test's own path");
    test.parent().and_then(|deps| deps.parent()).expect("target/<profile>/deps").join("examples/demo")
}

fn worker() -> Vec<String> {
    vec![demo().to_string_lossy().into_owned(), "worker".to_string()]
}

fn far() -> Remote {
    Remote::start(&worker(), false, false, None).unwrap_or_else(|c| panic!("{}: {}", c.kind, c.message))
}

fn on<A: 'static>(far: &Remote, p: impl Prog<A, Failing>) -> Result<A, Condition> {
    far.run(Throws::run(p))
}

fn sent(far: &Remote, p: impl Prog<(), Streaming>) -> Result<Vec<Json>, Condition> {
    on(far, Emit::collect(p).map(|(values, _)| values))
}

fn ints(values: impl IntoIterator<Item = i64>) -> Vec<Json> {
    values.into_iter().map(Json::Int).collect()
}

/// what a host is refused with, by name
fn refused(opened: Result<Remote, Condition>) -> String {
    opened.err().map_or("opened".to_string(), |c| c.kind)
}

#[test]
fn a_host_and_a_worker_in_two_processes_print_what_every_pair_prints() {
    let host = Command::new(demo()).arg("host").args(worker()).output().expect("the demonstration");
    let expected = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../wire/expected.txt")).unwrap();
    assert_eq!(String::from_utf8_lossy(&host.stdout), expected);
    assert!(host.status.success());
}

#[test]
fn each_choice_of_format_and_compression_carries_the_same_answers() {
    let text =
        r#"[9007199254740993,-9007199254740992,3.0,-0.5,1e300,null,true,"é \"q\" \\ \n\t 日本 😀",{"b":1,"a":[]}]"#;
    let odd = Json::parse(text).unwrap();
    let big = Json::Str("x".repeat(100_000));
    for (cbor, deflate) in [(false, false), (false, true), (true, false), (true, true)] {
        let far = Remote::start(&worker(), cbor, deflate, None).unwrap_or_else(|c| panic!("{}", c.message));
        assert_eq!(on(&far, Far::call("t:echo", vec![odd.clone()])), Ok(odd.clone()));
        assert_eq!(on(&far, Far::call("t:echo", vec![big.clone()])), Ok(big.clone()));
        assert_eq!(sent(&far, Far::stream("t:numbers", ints([3]), 1, None)), Ok(ints(1..=3)));
    }
}

#[test]
fn a_far_failure_is_raised_here_and_a_handler_of_this_side_decides() {
    let far = far();
    let rescued = Throws::recover(|c: Condition| pure(Json::Str(c.kind)), Far::call::<Failing, _>("t:boom", vec![]));
    assert_eq!(far.run(rescued), Json::str("ValueError"));
    assert_eq!(on(&far, Far::call("t:nothing", vec![])).map_err(|c| c.kind), Err("LookupError".to_string()));
    // an infinity does not cross: refused on this side, before anything is sent
    let infinity = on(&far, Far::call("t:echo", vec![Json::Dec(f64::INFINITY)]));
    assert_eq!(infinity.map_err(|c| c.kind), Err("ValueError".to_string()));
    // the far side lives on
    assert_eq!(on(&far, Far::call("t:add", ints([2, 3]))), Ok(Json::Int(5)));
    assert!(far.mux());
}

/// what a host writes, kept where a test reads it
#[derive(Clone, Default)]
struct Written(Rc<RefCell<Vec<u8>>>);

impl Write for Written {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0.borrow_mut().extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Written {
    /// what the host has asked so far: each request's name, and the stream or the run it is about
    fn asked(&self) -> Vec<String> {
        let about = |request: &Json| match request.get("stream") {
            Json::Null => request.get("run").to_string(),
            stream => stream.to_string(),
        };
        let named = |request: Json| match request.get("op") {
            Json::Str(name) => format!("{name} {}", about(&request)),
            other => format!("{other} {}", about(&request)),
        };
        String::from_utf8_lossy(&self.0.borrow()).lines().map(|line| named(Json::parse(line).unwrap())).collect()
    }
}

/// a run begun and a stream opened, both left open; then `end`
fn leaving(end: impl FnOnce() -> Comp<(), Failing> + Clone + 'static) -> impl Prog<(), Failing> {
    let opening = || Opening { name: "t:numbers".to_string(), args: Json::Arr(vec![]), credit: 1, input: None };
    perform(Far::Run).then(move || perform(|r| Far::Open(opening(), r))).then(end)
}

#[test]
fn a_run_closes_what_it_left_open_when_it_ends_when_it_is_left_through_an_effect_when_the_host_throws() {
    // a far side that has said all it will say: its hello, and that each of three streams is open
    let hello = r#"{"shim":9,"speaks":{"format":["json"],"mux":true}}"#;
    let heard = format!("{hello}\n{{\"id\":1,\"ok\":null}}\n{{\"id\":4,\"ok\":null}}\n{{\"id\":7,\"ok\":null}}\n");
    let out = Written::default();
    let far = Remote::open(Cursor::new(heard), out.clone(), false, false, None);
    let far = far.unwrap_or_else(|c| panic!("{}", c.message));
    let mut all = vec!["call 2", "cancel 2", "forget 1"];
    assert_eq!(on(&far, leaving(|| pure(()).boxed())), Ok(()));
    assert_eq!(out.asked(), all);
    // left through an effect: the failure's handler is inside the program, which so ends with a value
    all.extend(["call 4", "cancel 4", "forget 3"]);
    let failed = on(&far, leaving(|| Throws::raise(Condition::new("ValueError", "no")).boxed()));
    assert_eq!(failed.map_err(|c| c.kind), Err("ValueError".to_string()));
    assert_eq!(out.asked(), all);
    // the host throws — in Rust, panics: closed at once, while the panic passes `run`
    all.extend(["call 6", "cancel 6", "forget 5"]);
    let thrown = catch_unwind(AssertUnwindSafe(|| on(&far, leaving(|| panic!("host")))));
    assert!(thrown.is_err());
    assert_eq!(out.asked(), all);
    // and nothing is left for a later run to close
    assert_eq!(far.run(pure(1)), 1);
    assert_eq!(out.asked(), all);
}

#[test]
fn a_stream_the_far_side_is_never_more_than_the_credit_ahead_a_long_one_in_constant_stack() {
    let far = far();
    for credit in [1, 3, 10] {
        let taken = Emit::take(4, Far::stream("t:numbers", ints([1000]), credit, None));
        assert_eq!(sent(&far, taken).map(|values| values.len()), Ok(4));
        assert_eq!(on(&far, Far::call("t:sent", vec![])), Ok(Json::Int(4 + credit)));
    }
    let all = sent(&far, Far::stream("t:numbers", ints([50_000]), 64, None));
    assert_eq!(all.map(|values| values.len()), Ok(50_000));
    let fed = Emit::send_all((0..20_000).map(|i| Json::Int(i % 1000)));
    assert_eq!(sent(&far, Far::stream("t:dedup", vec![], 2, Some(fed))), Ok(ints(0..1000)));
}

#[test]
fn a_program_of_the_far_side_asks_this_sides_handlers_as_often_as_they_resume_it() {
    let far = far();
    // every price asked is a choice of two: the far program's rest is continued twice at each, four sums in all
    let p = Far::program::<Asking, _>("shop:total", vec![Json::str("tea"), Json::str("milk")], &["price_of"]);
    let on_call = |op, k: K<Vec<Json>, Failing>| match op {
        Foreign::Call(_, _, r) => k
            .resume(r, Json::Int(1))
            .flat_map(move |low| k.resume(r, Json::Int(10)).map(move |high| [low.clone(), high].concat())),
    };
    let sums = Handler::new(|sum| pure(vec![sum]), on_call).handle(p);
    assert_eq!(on(&far, sums), Ok(ints([2, 11, 11, 20])));
}

#[test]
fn a_stream_that_fails_ends_with_its_condition_a_far_side_with_no_mux_has_no_streams() {
    let far = far();
    assert_eq!(sent(&far, Far::stream("t:boom", vec![], 1, None)), Err(Condition::new("ValueError", "no")));
    assert_eq!(sent(&far, Far::stream("t:nothing", vec![], 1, None)).map_err(|c| c.kind), Err("LookupError".into()));
    let hello = r#"{"shim":9,"speaks":{"format":["json"]}}"#;
    let old = Remote::open(Cursor::new(hello), sink(), false, false, None).unwrap_or_else(|c| panic!("{}", c.message));
    let refused = sent(&old, Far::stream("t:numbers", ints([1]), 1, None));
    assert_eq!(refused.map_err(|c| c.kind), Err("NotStreaming".to_string()));
    // and when the far side is gone, that is a failure too
    assert_eq!(on(&old, Far::call("t:add", vec![])).map_err(|c| c.kind), Err("WorkerDied".to_string()));
}

#[test]
fn auth_the_same_secret_on_both_sides_or_the_wire_is_refused_by_name() {
    // the worker's secret is the host's, given to it as OKAY_WIRE_SECRET
    let proven = Remote::start(&worker(), false, false, Some("s")).unwrap_or_else(|c| panic!("{}", c.message));
    assert_eq!(on(&proven, Far::call("t:add", ints([2, 3]))), Ok(Json::Int(5)));
    // a worker with another secret, started by a host that passes none on
    let another = ["env".to_string(), "OKAY_WIRE_SECRET=another".to_string()].into_iter().chain(worker());
    let another: Vec<String> = another.collect();
    let shell = |script: &str| vec!["sh".to_string(), "-c".to_string(), script.replace("DEMO", &worker()[0])];
    assert_eq!(refused(Remote::start(&another, false, false, None)), "PermissionError");
    let wrong = shell("OKAY_WIRE_SECRET=another exec DEMO worker");
    assert_eq!(refused(Remote::start(&wrong, false, false, Some("s"))), "PermissionError");
    let none = shell("unset OKAY_WIRE_SECRET; exec DEMO worker");
    assert_eq!(refused(Remote::start(&none, false, false, Some("s"))), "PermissionError");
}

/// what a worker with the secret `s` says to these requests
fn said(requests: &[&str]) -> Vec<Json> {
    let mut worker = Command::new(demo());
    worker.arg("worker").env("OKAY_WIRE_SECRET", "s").stdin(Stdio::piped()).stdout(Stdio::piped());
    let mut worker = worker.spawn().expect("the demonstration");
    let mut input = worker.stdin.take().expect("the worker's input");
    // the worker may have closed the wire before the last request is written
    let _ = input.write_all((requests.join("\n") + "\n").as_bytes());
    drop(input);
    let output = worker.wait_with_output().expect("the worker's output");
    String::from_utf8_lossy(&output.stdout).lines().map(|line| Json::parse(line).unwrap()).collect()
}

#[test]
fn auth_until_the_host_has_proved_the_secret_the_worker_serves_nothing_a_wrong_proof_ends_the_wire() {
    let add = r#"{"id":1,"op":"call","fn":"t:add","args":[2,3]}"#;
    let early = said(&[add, add]);
    assert_eq!(early.len(), 3);
    assert_eq!(early[0].get("auth").get("scheme"), &Json::str("hmac-sha256"));
    assert_eq!(early[1].get("condition").get("kind"), &Json::str("PermissionError"));
    assert_eq!(early[2].get("condition").get("kind"), &Json::str("PermissionError"));
    let wrong = said(&[r#"{"op":"auth","nonce":"00","mac":"00"}"#, add]);
    assert_eq!(wrong.len(), 2);
    assert_eq!(wrong[1].get("condition").get("kind"), &Json::str("PermissionError"));
    // each worker has a nonce of its own
    assert_ne!(early[0].get("auth").get("nonce"), wrong[0].get("auth").get("nonce"));
}

#[test]
fn the_handshake_another_shim_or_a_format_the_far_side_does_not_speak_is_refused_by_name() {
    let hello = |text: &'static str, cbor| refused(Remote::open(Cursor::new(text), sink(), cbor, false, None));
    assert_eq!(hello(r#"{"shim":8}"#, false), "Shim");
    assert_eq!(hello(r#"{"shim":9,"speaks":{"format":["json"]}}"#, true), "WireError");
    assert_eq!(hello(r#"{"shim":9,"fatal":"no jsonlite"}"#, false), "WireError");
    assert_eq!(hello("", false), "WireError");
    assert_eq!(hello("not JSON", false), "WireError");
    assert_eq!(hello(r#"{"shim":9,"speaks":{"format":["json"]}}"#, false), "opened");
    // a far side that asks for a proof of a host with no secret, and a host with a secret of one that asks none
    let asking = r#"{"shim":9,"auth":{"scheme":"hmac-sha256","nonce":"00"}}"#;
    assert_eq!(refused(Remote::open(Cursor::new(asking), sink(), false, false, None)), "PermissionError");
    assert_eq!(refused(Remote::open(Cursor::new(r#"{"shim":9}"#), sink(), false, false, Some("s"))), "PermissionError");
    // one that proves nothing back; one that refuses to be configured
    assert_eq!(refused(Remote::open(Cursor::new(asking), sink(), false, false, Some("s"))), "PermissionError");
    let deaf = "{\"shim\":9,\"speaks\":{\"format\":[\"cbor\"]}}\n{\"id\":null,\"condition\":{\"kind\":\"ValueError\"}}";
    assert_eq!(hello(deaf, true), "WireError");
}
