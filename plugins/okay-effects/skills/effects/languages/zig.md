# okay-zig

Repo: <https://github.com/okay-effects/okay-zig> (README.md, AGENTS.md). Zig 0.17.0. Two layers sharing one set of
handler structs: CONTEXTS (direct style, plain calls, guards as error unions) and THE MACHINE (programs as data,
multi-shot `k`).

```sh
zig fetch --save=okay "git+https://github.com/okay-effects/okay-zig#v0.1.0"
```
```zig
// build.zig
const okay = b.dependency("okay", .{ .target = target, .optimize = optimize });
exe.root_module.addImport("okay", okay.module("okay"));
```

An effect is a type with `pub const Op = union(enum)` and `pub fn Answer(comptime tag) type`; a handler is a struct
with `pub const Effect = ...` and one method per operation. The shape of the standard `State` (`src/std.zig`):

```zig
pub fn State(comptime S: type) type {
    return struct {
        const Self = @This();
        pub const Op = union(enum) { get: void, put: S };
        pub fn Answer(comptime tag: std.meta.Tag(Op)) type {
            return switch (tag) { .get => S, .put => void };
        }
        pub fn get(b: *Build, comptime R: anytype) Eff(S, R) { return b.perform(R, Self, .get, {}); }
        pub fn put(b: *Build, comptime R: anytype, s: S) Eff(void, R) { return b.perform(R, Self, .put, s); }
        pub const Cell = struct {               // a handler: methods = operations
            pub const Effect = Self;
            s: *S,
            pub fn get(self: Cell) S { return self.s.*; }
            pub fn put(self: Cell, v: S) void { self.s.* = v; }
        };
    };
}
```

Context layer: program, handlers, run (from `test/cx_test.zig`):

```zig
const std = @import("std");
const ok = @import("okay");
const S = ok.effects;
const Counter = S.State(u32);
const Name = S.Reader([]const u8);

fn greet(cx: anytype) usize {
    ok.needs(@TypeOf(cx), .{ Name, Counter });          // the row, checked at compile time
    const who = cx.op(Name, .ask, {});
    const n = cx.op(Counter, .get, {});
    cx.op(Counter, .put, n + 1);
    return who.len * 100 + n;
}

var cell: u32 = 7;
const cx = ok.context(.{ Counter.Cell{ .s = &cell }, Name.Given{ .env = "Ann" } });
_ = greet(cx);   // 307, cell == 8
```

Control: `.observe(Seen{...})` (a struct with `pub const Effect` and `pub fn seen(self, op) void`) and
`.guard(Budget{ .max = 10 })` (a struct with `pub fn decide(self, op) error{OverBudget}!void`; the program then
writes `try cx.op(...)`).

Machine layer, multi-shot (from `test/std_test.zig`): `k.answer(b, .tag, value)` may be called any number of times.

```zig
var a = std.heap.ArenaAllocator.init(allocator); defer a.deinit();
var b = ok.Build{ .arena = a.allocator() };
const R = ok.Row(.{S.Choose});
const p = b.then(S.Choose.flip(&b, R), {}, struct {
    fn f(_: void, bb: *ok.Build, x: bool) ok.Eff(u8, R) {
        return bb.map(S.Choose.flip(bb, R), x, struct {
            fn g(first: bool, second: bool) u8 {
                return @as(u8, if (first) 2 else 0) + @as(u8, if (second) 1 else 0);
            }
        }.g);
    }
}.f);
_ = b.run(S.Choose.all(&b, p));   // {3, 2, 1, 0}
```

In-place handler: `b.handleTail(E, p, handlerStruct)`; general: `b.handle(E, p, H{})` with `ret(self, b, a)` and
`op(self, b, o, k: ok.Resume(E, Out))` (see `Choose.all` in `src/std.zig`). Contexts are one-shot (plain calls).
