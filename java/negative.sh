#!/bin/sh
# What the compiler promises: every program in negative/ is refused — and refused for its reason: javac's errors are
# at the lines marked `// refused`, at every one of them and at no other. Needs the classes built in out/.
cd "$(dirname "$0")" || exit 1
failed=0
for file in negative/*.java; do
  javac --release 25 -cp out -d /tmp/okay-effects-negative "$file" 2>&1 |
    grep "^$file:[0-9]*: error" | cut -d: -f2 | sort -u > /tmp/okay-effects-negative-errors.txt
  grep -n '// refused' "$file" | cut -d: -f1 | sort -u > /tmp/okay-effects-negative-marked.txt
  if [ -s /tmp/okay-effects-negative-errors.txt ] &&
    cmp -s /tmp/okay-effects-negative-errors.txt /tmp/okay-effects-negative-marked.txt; then
    echo "  + $file: refused at $(tr '\n' ' ' < /tmp/okay-effects-negative-errors.txt)"
  else
    echo "==> X $file: errors at lines [$(tr '\n' ' ' < /tmp/okay-effects-negative-errors.txt)]," \
      "wanted at [$(tr '\n' ' ' < /tmp/okay-effects-negative-marked.txt)]"
    failed=$((failed + 1))
  fi
done
if [ $failed -eq 0 ]; then echo "java negative: all refused"; else echo "java negative: $failed FAILED"; exit 1; fi
