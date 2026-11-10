package okay.effects.java.wire;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** THE AUTHENTICATION of a wire, optional: each side proves it holds the secret, by an HMAC-SHA256 over both sides'
 * nonces, and neither sends it. */
public final class Auth {
  private Auth() {}

  /** the proof of {@code who} — the far side is {@code server}, the host {@code client} — over the two nonces */
  public static String mac(String secret, String who, String farNonce, String hostNonce) {
    return hmac(secret, "okay-wire " + who + "|" + farNonce + "|" + hostNonce);
  }

  /** HMAC-SHA256 of the text under the secret, in hex */
  public static String hmac(String secret, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(UTF_8)));
    } catch (GeneralSecurityException e) {
      // every Java has HmacSHA256, and an empty secret is the only key it refuses
      throw new IllegalArgumentException("HMAC-SHA256: " + e.getMessage());
    }
  }

  /** 16 random bytes, in hex */
  public static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** equal, in a time that does not say how much of a guess was right */
  public static boolean same(String a, String b) { return MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8)); }
}
