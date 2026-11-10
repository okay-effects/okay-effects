//! Every transcript of wire/ asked of a worker, of any language: `transcripts <the worker's command...>`.
//! The checker is the test's own (tests/transcripts.rs).

#[path = "../tests/transcripts.rs"]
mod transcripts;

fn main() {
    let command: Vec<String> = std::env::args().skip(1).collect();
    if command.is_empty() {
        eprintln!("usage: transcripts <the worker's command...>");
        std::process::exit(2)
    }
    let mut failed = false;
    for name in transcripts::NAMES {
        match transcripts::check(name, &command) {
            Ok(answers) => println!("  + wire/{name}.txt: {answers} answers"),
            Err(why) => {
                failed = true;
                println!("==> X wire/{name}.txt: {why}")
            }
        }
    }
    std::process::exit(failed as i32)
}
