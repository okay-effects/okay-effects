#!/bin/sh
# Everything this repository promises, checked: each kernel's own tests (the properties of the model, the family's
# TCK digest, the programs that must not compile, the wire's codecs), every worker against the wire's transcripts,
# and every host with every worker — nine pairs, one answer.
cd "$(dirname "$0")" || exit 1
failed=0
ok() { # a line for a check that passed, or one more that did not
  if [ "$1" -eq 0 ]; then echo "  + $2"; else echo "==> X $2"; failed=$((failed + 1)); fi
}

echo "== scala"
(cd scala && scala-cli test . --server=false > /tmp/okay-effects-scala.log 2>&1)
status=$?
count=$(awk '/ total / { n += $(NF - 2) } END { print n }' /tmp/okay-effects-scala.log)
ok $status "scala: $count tests (/tmp/okay-effects-scala.log)"
SCALA_CP=$(cd scala && scala-cli compile . --print-class-path --server=false 2>/dev/null | tail -1)

echo "== java"
javac --release 25 -Xlint:all -Werror -d java/out $(find java/src java/test -name '*.java')
ok $? "java: compiled without a warning"
for main in Properties wire.WireTests; do
  java -cp java/out okay.effects.java.$main > /tmp/okay-effects-java.log 2>&1
  ok $? "java $main: $(grep -c '^  + ' /tmp/okay-effects-java.log) tests"
done
java/negative.sh > /tmp/okay-effects-java-negative.log 2>&1
ok $? "java: $(grep -c '^  + ' /tmp/okay-effects-java-negative.log) programs refused by javac"

echo "== rust"
(cd rust && cargo test > /tmp/okay-effects-rust.log 2>&1 && cargo build -q --examples)
ok $? "rust: $(grep -c '\.\.\. ok$' /tmp/okay-effects-rust.log) tests, those that must not compile among them"

demo() { # the demonstration of a language, as a command
  case $1 in
    scala) echo "java -cp $SCALA_CP okay.effects.scala.wire.wireDemo $2" ;;
    java) echo "java -cp java/out okay.effects.java.wire.Demo $2" ;;
    rust) echo "rust/target/debug/examples/demo $2" ;;
  esac
}

echo "== the wire's transcripts"
for far in scala java rust; do
  java -cp java/out okay.effects.java.wire.Transcripts $(demo $far worker) > /tmp/okay-effects-far.log 2>&1
  ok $? "$far worker: $(grep -c '^  + ' /tmp/okay-effects-far.log) transcripts"
done

echo "== every host with every worker"
for host in scala java rust; do
  for far in scala java rust; do
    $(demo $host host) $(demo $far worker) 2>/dev/null | diff wire/expected.txt - > /dev/null
    ok $? "$host host, $far worker"
  done
done

if [ $failed -eq 0 ]; then echo "all passed"; else echo "$failed FAILED"; exit 1; fi
