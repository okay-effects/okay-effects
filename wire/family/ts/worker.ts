// okay-ts as a far side on pipes: its own library (`serveStdio`), serving the programs of okay's conformance
// module as okay's own test of okay-ts writes them (okay: okay-py/src/test/.../TestOkayTsServed.scala).
// Run: node worker.ts <okay-ts>/src/index.ts — the library is the sibling's, found by wire/family/check.sh.
const { Do, eff, effect, op, pure, serveStdio } = await import(process.argv[2]);

const Conf = effect("conf", { choose: op(), price_of: op(), discount: op() });

await serveStdio({
  pairs: () => Do.bind("x", () => Conf.choose([1, 2])).bind("y", () => Conf.choose([10, 20])).map(({ x, y }) => x + y),
  total: (sku: string, qty: number) => Conf.price_of(sku).flatMap((p: number) => Conf.discount(p * qty)),
  quote: (sku: string, qty: number) => eff(function* () {
    const price = yield* Conf.price_of(sku);
    return yield* Conf.discount(price * qty);
  }),
  boom: () => pure(0).map(() => { throw new Error("typescript says no"); }),
}, { name: (o: { name: string }) => o.name });
