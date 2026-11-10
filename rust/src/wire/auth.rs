//! THE AUTHENTICATION of a wire, optional: each side proves it holds the secret, by an HMAC-SHA256 over both sides'
//! nonces, and neither sends it. SHA-256 (FIPS 180-4) and HMAC (RFC 2104) are written here: the kernel has no
//! dependencies.

use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::time::{SystemTime, UNIX_EPOCH};

/// The proof of `who` — the far side is `server`, the host `client` — over the two nonces.
pub fn mac(secret: &str, who: &str, far_nonce: &str, host_nonce: &str) -> String {
    hex(&hmac(secret.as_bytes(), format!("okay-wire {who}|{far_nonce}|{host_nonce}").as_bytes()))
}

/// 16 bytes nobody can foretell, in hex. The standard library opens no door to the system's random bytes but
/// one: each `RandomState` is keyed from them. So: two of those hashed, the time and the process, digested.
pub fn nonce() -> String {
    let random = || RandomState::new().build_hasher().finish();
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_nanos());
    let seed = format!("{} {} {nanos} {}", random(), random(), std::process::id());
    hex(&sha256(seed.as_bytes())[..16])
}

/// Equal, in a time that does not say how much of a guess was right.
pub fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0, |differ, (x, y)| differ | (x ^ y)) == 0
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// HMAC-SHA256 of the message under the key.
pub fn hmac(key: &[u8], message: &[u8]) -> [u8; 32] {
    // a key longer than a block is its digest; a shorter one is filled with zeros
    let mut block = [0u8; 64];
    match key.len() > 64 {
        true => block[..32].copy_from_slice(&sha256(key)),
        false => block[..key.len()].copy_from_slice(key),
    }
    let inner: Vec<u8> = block.iter().map(|byte| byte ^ 0x36).chain(message.iter().copied()).collect();
    let outer: Vec<u8> = block.iter().map(|byte| byte ^ 0x5c).chain(sha256(&inner)).collect();
    sha256(&outer)
}

/// the first 32 bits of the fractional parts of the cube roots of the first 64 primes
const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
    0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
    0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
    0xc67178f2,
];

pub fn sha256(message: &[u8]) -> [u8; 32] {
    let mut state: [u32; 8] =
        [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    // the message, a bit set, zeros up to 8 bytes short of a whole block, the message's length in bits
    let mut padded = message.to_vec();
    padded.push(0x80);
    while padded.len() % 64 != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&(message.len() as u64 * 8).to_be_bytes());
    for block in padded.chunks_exact(64) {
        let mut w = [0u32; 64];
        for (word, bytes) in w.iter_mut().zip(block.chunks_exact(4)) {
            *word = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = state;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let choice = (e & f) ^ (!e & g);
            let t1 = h.wrapping_add(s1).wrapping_add(choice).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let majority = (a & b) ^ (a & c) ^ (b & c);
            (h, g, f, e, d, c, b, a) =
                (g, f, e, d.wrapping_add(t1), c, b, a, t1.wrapping_add(s0).wrapping_add(majority));
        }
        for (word, add) in state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *word = word.wrapping_add(add);
        }
    }
    let mut digest = [0u8; 32];
    for (bytes, word) in digest.chunks_exact_mut(4).zip(state) {
        bytes.copy_from_slice(&word.to_be_bytes());
    }
    digest
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_is_fips_180_4s() {
        let digest = |text: &str| hex(&sha256(text.as_bytes()));
        assert_eq!(digest(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assert_eq!(digest("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(
            digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
        // a padding that needs a block of its own (56 to 64 bytes), and a million bytes
        assert_eq!(digest(&"a".repeat(60)), "11ee391211c6256460b6ed375957fadd8061cafbb31daf967db875aebd5aaad4");
        assert_eq!(digest(&"a".repeat(1_000_000)), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }

    #[test]
    fn hmac_is_rfc_4231s() {
        // test case 2; test case 1; test case 6, a key longer than a block
        let proof = |key: &[u8], text: &str| hex(&hmac(key, text.as_bytes()));
        assert_eq!(
            proof(b"Jefe", "what do ya want for nothing?"),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
        assert_eq!(proof(&[0x0b; 20], "Hi There"), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
        assert_eq!(
            proof(&[0xaa; 131], "Test Using Larger Than Block-Size Key - Hash Key First"),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        );
    }

    #[test]
    fn a_proof_names_its_side_a_nonce_is_new_each_time() {
        assert_eq!(mac("s", "client", "aa", "bb"), hex(&hmac(b"s", b"okay-wire client|aa|bb")));
        assert_ne!(mac("s", "client", "aa", "bb"), mac("s", "server", "aa", "bb"));
        assert_eq!(nonce().len(), 32);
        assert_ne!(nonce(), nonce());
        assert!(same("abc", "abc") && !same("abc", "abd") && !same("abc", "abcd"));
    }
}
