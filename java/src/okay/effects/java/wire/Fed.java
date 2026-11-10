package okay.effects.java.wire;

import java.util.Optional;
import okay.effects.java.Comp;
import okay.effects.java.Eff;
import okay.effects.java.Effects;
import okay.effects.java.Op;

/** THE STREAM A CALLER FEEDS into a function of the far side: the function takes a value at a time. */
public sealed interface Fed {
  record Next() implements Fed, Op<Fed, Optional<Json>, Next> {}

  /** the caller's next value, waited for; none at the stream's end */
  static Comp<Optional<Json>, Eff<Fed, ?>> next() { return Effects.perform(new Next()); }
}
