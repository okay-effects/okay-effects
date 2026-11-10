#!/bin/sh
# Everything this repository promises, checked: each kernel's own tests (the properties of the model and the family's
# TCK digest), every worker against the wire's transcript, and every host with every worker — nine pairs, one answer.
cd "$(dirname "$0")" || exit 1
failed=0
ok() { # a line for a check that passed, or the count of those that did not
  if [ "$1" -eq 0 ]; then echo "  + $2"; else echo "==> X $2"; failed=$((failed + 1)); fi
}

echo "== scala"
(cd scala && scala-cli test . --server=false > /tmp/okay-effects-scala.log 2>&1)
status=$?
count=$(awk '/ total / { n += $(NF - 2) } END { print n }' /tmp/okay-effects-scala.log)
ok $status "scala: $count tests (/tmp/okay-effects-scala.log)"
SCALA_CP=$(cd scala && scala-cli compile . --print-class-path --server=false 2>/dev/null | tail -1)

echo "== java"
(cd java && javac --release 25 -Xlint:all -Werror -d out $(find src test -name '*.java') &&
  java -cp out okay.algebraic.Properties > /tmp/okay-effects-java.log 2>&1)
ok $? "java: $(grep -c '^  + ' /tmp/okay-effects-java.log) tests (/tmp/okay-effects-java.log)"

echo "== rust"
(cd rust && cargo test > /tmp/okay-effects-rust.log 2>&1 && cargo build -q --examples)
ok $? "rust: $(grep -c '\.\.\. ok$' /tmp/okay-effects-rust.log) tests (/tmp/okay-effects-rust.log)"

demo() { # the demonstration of a language, as a command
  case $1 in
    scala) echo "java -cp $SCALA_CP okay.effects.scala.wire.wireDemo $2" ;;
    java) echo "java -cp java/out okay.algebraic.wire.Demo $2" ;;
    rust) echo "rust/target/debug/examples/demo $2" ;;
  esac
}

compact() { sed -e 's/": /":/g' -e 's/, /,/g'; }

echo "== the wire's transcript"
for far in scala java rust; do
  grep '^> ' wire/transcript.txt | cut -c3- | $(demo $far worker) 2>/dev/null | tail -n +2 > /tmp/okay-effects-far.txt
  grep '^< ' wire/transcript.txt | cut -c3- | compact | diff - /tmp/okay-effects-far.txt
  ok $? "$far worker"
done

echo "== every host with every worker"
for host in scala java rust; do
  for far in scala java rust; do
    $(demo $host host) $(demo $far worker) 2>/dev/null | diff wire/expected.txt -
    ok $? "$host host, $far worker"
  done
done

if [ $failed -eq 0 ]; then echo "all passed"; else echo "$failed FAILED"; exit 1; fi
