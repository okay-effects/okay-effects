#!/bin/sh
# The wire of this repository against the family's: our host with the real far sides of the sibling repositories
# (okay-zig, okay-lean, okay-ts, okay's reference Python shim), okay's transcript against our three workers, and
# okay's own host against our three workers. A sibling that is absent or not built is skipped, by name; nothing
# is written outside wire/family/out, but a sibling's worker built by its own documented command. README.md here
# says what each line means.
cd "$(dirname "$0")" || exit 1
here=$PWD
root=$(cd ../.. && pwd)
siblings=$(cd ../../.. && pwd)
out=$here/out
mkdir -p "$out"
passed=0
failed=0
skipped=0

skip() { # a far side, or a direction, that could not be checked here: said, and no failure
  echo "  - skipped: $1 ($2)"
  skipped=$((skipped + 1))
}

checked() { # a checking program run: its lines shown and counted; one that broke without saying so is one more
  name=$1
  shift
  "$@" > "$out/lines" 2> "$out/errors"
  status=$?
  cat "$out/lines"
  passed=$((passed + $(grep -c '^  + ' "$out/lines")))
  skipped=$((skipped + $(grep -c '^  - skipped' "$out/lines")))
  broken=$(grep -c '^==> X' "$out/lines")
  if [ $status -ne 0 ] && [ "$broken" -eq 0 ]; then
    echo "==> X $name: ended with status $status: $(grep -v '^WARNING' "$out/errors" | head -3 | tr '\n' ' ')"
    broken=1
  fi
  failed=$((failed + broken))
}

commit() { # a sibling's commit, for the record
  git -C "$1" rev-parse --short HEAD 2>/dev/null || echo "no commit"
}

newer() { # the first of the files under the paths ($2...) that is newer than the file $1
  than=$1
  shift
  find "$@" -type f -newer "$than" 2>/dev/null | head -1
}

# ---- ours: the host and the three workers, built into wire/family/out (nothing is written to scala/, java/, rust/)
echo "== ours"
JAVA=$(command -v java)
if [ -z "$JAVA" ] || ! command -v scala-cli > /dev/null; then
  skip "everything" "java and scala-cli are needed for our host"
  echo "family: 0 passed, 0 failed, $skipped skipped"
  exit 0
fi
HOST_CP=$(scala-cli compile host --server=false --print-class-path 2> "$out/host.log" | tail -1)
if [ -z "$HOST_CP" ]; then
  echo "==> X our Scala host (wire/family/host over scala/src) does not compile: $out/host.log"
  echo "family: 0 passed, 1 failed, $skipped skipped"
  exit 1
fi
echo "    our host and our Scala worker: compiled from scala/src"
family="$JAVA -cp $HOST_CP okay.effects.family.family"
workers="scala"
if command -v javac > /dev/null && javac --release 25 -d "$out/java" $(find "$root/java/src" -name '*.java') \
    > "$out/java.log" 2>&1; then
  echo "    our Java worker: compiled from java/src"
  workers="$workers java"
else
  skip "our Java worker" "javac --release 25 failed or is absent: $out/java.log"
fi
if command -v cargo > /dev/null && (cd "$root/rust" && cargo build -q --examples --target-dir "$out/rust") \
    > "$out/rust.log" 2>&1; then
  echo "    our Rust worker: built from rust/"
  workers="$workers rust"
else
  skip "our Rust worker" "cargo build failed or is absent: $out/rust.log"
fi

worker() { # a worker of ours, as a command; the absolute java, since okay's host starts workers with no environment
  case $1 in
    scala) echo "$JAVA -cp $HOST_CP okay.effects.scala.wire.wireDemo worker" ;;
    java) echo "$JAVA -cp $out/java okay.effects.java.wire.Demo worker" ;;
    rust) echo "$out/rust/debug/examples/demo worker" ;;
  esac
}

# ---- our host, the family's far sides
okay=$siblings/okay
zig=$siblings/okay-zig
lean=$siblings/okay-lean
ts=$siblings/okay-ts

echo "== our host, okay-zig's worker ($(commit "$zig"))"
if [ ! -d "$zig" ]; then
  skip "okay-zig" "no $zig"
else
  # zig: on PATH, or where okay-zig's AGENTS.md says it is installed
  ZIG=$(command -v zig || ls "$HOME"/.zig/zig-*/zig 2>/dev/null | head -1)
  bin=$zig/zig-out/bin/zig-worker
  # built by okay-zig's own command (its AGENTS.md) when it is not there, or is older than its sources
  if [ ! -x "$bin" ] || [ -n "$(newer "$bin" "$zig/src" "$zig/conformance" "$zig/build.zig")" ]; then
    if [ -n "$ZIG" ]; then
      echo "    building: (cd $zig && zig build worker -Doptimize=ReleaseSafe)"
      (cd "$zig" && "$ZIG" build worker -Doptimize=ReleaseSafe) > "$out/zig.log" 2>&1 \
        || echo "    it failed: $out/zig.log"
    elif [ -x "$bin" ]; then
      echo "    zig-worker is older than its sources, and there is no zig to build it: checked as it is"
    fi
  fi
  if [ -x "$bin" ]; then
    checked okay-zig $family conformance okay-zig held,direct,streams "$bin"
  else
    skip "okay-zig" "no zig-out/bin/zig-worker and no zig to build it: zig build worker -Doptimize=ReleaseSafe"
  fi
fi

echo "== our host, okay-lean's worker ($(commit "$lean"))"
bin=$lean/.lake/build/bin/lean-worker
if [ ! -d "$lean" ]; then
  skip "okay-lean" "no $lean"
elif [ ! -x "$bin" ]; then
  # not built here: a Lean build checks the library's proofs first, minutes of every core
  skip "okay-lean" "not built: (cd $lean && lake build lean-worker)"
else
  if [ -n "$(newer "$bin" "$lean/Okay/Wire.lean" "$lean/Okay/Wire" "$lean/Tests/WorkerPrograms.lean")" ]; then
    echo "    lean-worker is older than its sources: checked as it is (lake build lean-worker)"
  fi
  checked okay-lean $family conformance okay-lean held "$bin"
fi

echo "== our host, okay-ts as a worker on pipes ($(commit "$ts"))"
if [ ! -f "$ts/src/index.ts" ]; then
  skip "okay-ts" "no $ts/src/index.ts"
elif ! command -v node > /dev/null; then
  skip "okay-ts" "no node"
else
  checked okay-ts $family conformance okay-ts direct node "$here/ts/worker.ts" "$ts/src/index.ts"
fi

echo "== our host, okay's reference Python shim ($(commit "$okay"))"
py=$okay/okay-py/src/main/resources/okay/py
if [ ! -f "$py/shim.py" ] || [ ! -f "$py/wire.txt" ]; then
  skip "okay's Python shim" "no $py/shim.py"
elif ! command -v python3 > /dev/null; then
  skip "okay's Python shim" "no python3"
else
  # the module the shim serves is okay's: the one in the header of its wire.txt, taken from there at each run
  mkdir -p "$out/python"
  awk '/^# The module the far side serves/ { on = 1; next } /^# ----/ { on = 0 }
    on && /^#   / { print substr($0, 5) }' "$py/wire.txt" > "$out/python/t.py"
  shim="env PYTHONPATH=$out/python python3 $py/shim.py"
  for format in json cbor json+deflate cbor+deflate; do
    checked "python shim, $format" $family reference "python shim, $format" $format $shim
  done
  checked "python shim" $family transcript "python shim" strict "$py/wire.txt" $shim
fi

echo "== our host, okay's Rust crate"
# nothing to start: okay has the crate (okay-py/src/main/resources/okay/rust-crate) and no worker program of it
skip "okay's Rust crate" "its conformance worker is a string in okay's TestRustLinks.scala, built only by that test"

# ---- okay's transcript, our workers
echo "== okay's transcript (wire.txt), our workers"
if [ ! -f "$py/wire.txt" ]; then
  skip "okay's transcript" "no $py/wire.txt"
else
  for far in $workers; do
    checked "our $far worker" $family transcript "our $far worker" ours "$py/wire.txt" $(worker $far)
  done
fi

# ---- okay's host, our workers
echo "== okay's host (its ForeignWorker), our workers ($(commit "$okay"))"
# okay is never built from here (its AGENTS.md: sbt only through its gate, the machine is shared): the host is
# the classes its build left. The modules are okay-py's dependencies in okay's build.sbt.
version=$(ls -d "$okay"/okay-py/target/scala-3.*/classes/okay/foreign 2>/dev/null | tail -1 \
  | sed 's|.*/scala-\([^/]*\)/.*|\1|')
OKAY_CP=$okay/okay-py/target/scala-$version/classes:$okay/okay-core/.jvm/target/scala-$version/classes
for module in . okay-std okay-async okay-data okay-platform okay-stream okay-lex okay-parse okay-optics okay-codec \
    okay-compress okay-arrow; do
  OKAY_CP=$OKAY_CP:$okay/$module/.jvm/target/scala-$version/classes
done
missing=""
for dir in $(echo "$OKAY_CP" | tr ':' ' '); do
  [ -d "$dir" ] || missing="$missing ${dir#"$okay"/}"
done
building=""
for pid in $(pgrep -f 'sbt-launch|xsbt.boot' 2>/dev/null); do
  case $(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p') in "$okay" | "$okay"/*) building=$pid ;; esac
done
if [ ! -d "$okay" ]; then
  skip "okay's host" "no $okay"
elif [ -z "$version" ] || [ -n "$missing" ]; then
  skip "okay's host" "okay is not built here: no${missing:- okay-py/target/scala-3.*/classes}"
elif [ -n "$building" ]; then
  skip "okay's host" "an sbt is running in okay (pid $building): its classes may be changing"
else
  marker=$okay/okay-py/target/scala-$version/classes/okay/foreign/ForeignWorker.class
  if [ -n "$(newer "$marker" "$okay/okay-py/src/main" "$okay/okay-codec/src/main")" ]; then
    echo "    okay's classes are older than its wire's sources: the host is the one last built, not the one of HEAD"
  fi
  OKAY_HOST_CP=$(scala-cli compile okay-host --server=false --scala-version "$version" --classpath "$OKAY_CP" \
    --print-class-path 2> "$out/okay-host.log" | tail -1)
  if [ -z "$OKAY_HOST_CP" ]; then
    echo "==> X wire/family/okay-host does not compile against okay's classes: $out/okay-host.log"
    failed=$((failed + 1))
  else
    for far in $workers; do
      checked "our $far worker" "$JAVA" -cp "$OKAY_HOST_CP" okay.effects.family.OkayHost $far $(worker $far)
    done
  fi
fi

echo "family: $passed passed, $failed failed, $skipped skipped"
[ $failed -eq 0 ]
