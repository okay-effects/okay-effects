//! RAW DEFLATE (RFC 1951): no zlib header, no checksum. Written here because the kernel has no dependencies.
//! `inflate` reads everything the format has — stored blocks, fixed and dynamic Huffman codes — since the other
//! end's library chooses among them. `deflate` writes the simplest stream that still compresses: see there.

/// what a length symbol (257..) and a distance symbol stand for: a base, and so many more bits added to it
const LENGTH_BASE: [u32; 29] =
    [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
const LENGTH_EXTRA: [u32; 29] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
const DISTANCE_BASE: [u32; 30] = [
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
    8193, 12289, 16385, 24577,
];
const DISTANCE_EXTRA: [u32; 30] =
    [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];

/// the longest match, and how far back one may reach
const LONGEST: usize = 258;
const WINDOW: usize = 32768;

// -- WRITING

/// The bytes compressed: ONE BLOCK OF THE FIXED HUFFMAN CODES, with LZ77 matches. For each place, the last place
/// where the same three bytes began is remembered (a table by their hash, one place a slot, no chains); if what
/// is there repeats here for three bytes or more, a length and a distance are written, else the byte itself.
/// No dynamic codes, no lazy matching: repetitive data shrinks well, any other grows by an eighth at most.
pub fn deflate(data: &[u8]) -> Vec<u8> {
    let mut out = Writer::default();
    // the last block, of the fixed codes
    out.bits(0b011, 3);
    let mut seen = vec![usize::MAX; 1 << 15];
    // the place where the three bytes at `i` were last seen, and `i` remembered in its stead
    let mut last = |i: usize| match data.get(i..i + 3) {
        Some(&[a, b, c]) => {
            let slot = ((a as usize) << 10 ^ (b as usize) << 5 ^ c as usize) & 0x7fff;
            std::mem::replace(&mut seen[slot], i)
        }
        _ => usize::MAX,
    };
    let mut at = 0;
    while at < data.len() {
        let before = last(at);
        let most = LONGEST.min(data.len() - at);
        let length = match before != usize::MAX && at - before <= WINDOW {
            true => (0..most).take_while(|&i| data[before + i] == data[at + i]).count(),
            false => 0,
        };
        if length < 3 {
            out.symbol(data[at] as u32);
            at += 1;
            continue;
        }
        let l = LENGTH_BASE.iter().rposition(|&base| base as usize <= length).expect("a length of 3 or more");
        out.symbol(257 + l as u32);
        out.bits(length as u32 - LENGTH_BASE[l], LENGTH_EXTRA[l]);
        let distance = (at - before) as u32;
        let d = DISTANCE_BASE.iter().rposition(|&base| base <= distance).expect("a distance of 1 or more");
        // a distance's code is five bits, the highest first
        out.bits((d as u32).reverse_bits() >> 27, 5);
        out.bits(distance - DISTANCE_BASE[d], DISTANCE_EXTRA[d]);
        for i in at + 1..at + length {
            last(i);
        }
        at += length;
    }
    out.symbol(256);
    out.bytes()
}

/// bits gathered into bytes, the lowest first
#[derive(Default)]
struct Writer {
    out: Vec<u8>,
    held: u64,
    count: u32,
}

impl Writer {
    fn bits(&mut self, value: u32, n: u32) {
        self.held |= (value as u64) << self.count;
        self.count += n;
        while self.count >= 8 {
            self.out.push(self.held as u8);
            self.held >>= 8;
            self.count -= 8;
        }
    }

    /// a literal, the end of the block (256) or a length (257..), in its fixed code: the highest bit first
    fn symbol(&mut self, symbol: u32) {
        let (code, n) = match symbol {
            0..144 => (0x30 + symbol, 8),
            144..256 => (0x190 + symbol - 144, 9),
            256..280 => (symbol - 256, 7),
            _ => (0xc0 + symbol - 280, 8),
        };
        self.bits(code.reverse_bits() >> (32 - n), n);
    }

    fn bytes(mut self) -> Vec<u8> {
        if self.count > 0 {
            self.out.push(self.held as u8);
        }
        self.out
    }
}

// -- READING

fn refuse<T>(why: &str) -> Result<T, String> {
    Err(format!("DEFLATE: {why}"))
}

/// The bytes that were compressed. What follows the last block is not looked at.
pub fn inflate(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut input = Reader { data, held: 0, count: 0 };
    let mut out = Vec::new();
    loop {
        let last = input.bits(1)? == 1;
        match input.bits(2)? {
            0 => input.stored(&mut out)?,
            1 => {
                // the fixed codes: literals of 8 and 9 bits, the end and the short lengths of 7
                let mut lengths = [8; 288];
                lengths[144..256].fill(9);
                lengths[256..280].fill(7);
                input.block(&mut out, &Code::new(&lengths)?, &Code::new(&[5; 30])?)?
            }
            2 => {
                let (literals, distances) = input.codes()?;
                input.block(&mut out, &literals, &distances)?
            }
            _ => return refuse("a block of no known type"),
        }
        if last {
            return Ok(out);
        }
    }
}

/// bits taken from bytes, the lowest first
struct Reader<'a> {
    data: &'a [u8],
    held: u32,
    count: u32,
}

impl Reader<'_> {
    fn bits(&mut self, n: u32) -> Result<u32, String> {
        while self.count < n {
            let Some((&byte, rest)) = self.data.split_first() else { return refuse("the message is cut short") };
            self.data = rest;
            self.held |= (byte as u32) << self.count;
            self.count += 8;
        }
        let value = self.held & ((1 << n) - 1);
        self.held >>= n;
        self.count -= n;
        Ok(value)
    }

    /// a block of bytes as they are: from the next whole byte, their count, its complement, the bytes
    fn stored(&mut self, out: &mut Vec<u8>) -> Result<(), String> {
        (self.held, self.count) = (0, 0);
        let (length, check) = (self.bits(16)?, self.bits(16)?);
        if length != !check & 0xffff {
            return refuse("a stored block whose length does not check");
        }
        let Some((bytes, rest)) = self.data.split_at_checked(length as usize) else {
            return refuse("the message is cut short");
        };
        out.extend_from_slice(bytes);
        self.data = rest;
        Ok(())
    }

    /// the codes of a dynamic block: the lengths of both, themselves written in a code of 19 symbols
    fn codes(&mut self) -> Result<(Code, Code), String> {
        const ORDER: [usize; 19] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
        let (literals, distances, count) =
            (self.bits(5)? as usize + 257, self.bits(5)? as usize + 1, self.bits(4)? + 4);
        if literals > 286 || distances > 30 {
            return refuse("more codes than there are symbols");
        }
        let mut of_lengths = [0; 19];
        for i in 0..count as usize {
            of_lengths[ORDER[i]] = self.bits(3)? as u8;
        }
        let of_lengths = Code::new(&of_lengths)?;
        let mut lengths: Vec<u8> = Vec::new();
        while lengths.len() < literals + distances {
            // 16: the last length again, 3 to 6 times; 17 and 18: zeros, 3 to 10 and 11 to 138
            let (length, times) = match of_lengths.symbol(self)? {
                16 => match lengths.last() {
                    Some(&last) => (last, 3 + self.bits(2)?),
                    None => return refuse("a length repeated before any"),
                },
                17 => (0, 3 + self.bits(3)?),
                18 => (0, 11 + self.bits(7)?),
                length => (length as u8, 1),
            };
            lengths.extend((0..times).map(|_| length));
        }
        if lengths.len() > literals + distances || lengths[256] == 0 {
            return refuse("code lengths that do not fit their block");
        }
        Ok((Code::new(&lengths[..literals])?, Code::new(&lengths[literals..])?))
    }

    /// a block of literals and matches, to its end
    fn block(&mut self, out: &mut Vec<u8>, literals: &Code, distances: &Code) -> Result<(), String> {
        loop {
            let symbol = literals.symbol(self)? as usize;
            match symbol {
                0..256 => out.push(symbol as u8),
                256 => return Ok(()),
                257..286 => {
                    let length = LENGTH_BASE[symbol - 257] + self.bits(LENGTH_EXTRA[symbol - 257])?;
                    let d = distances.symbol(self)? as usize;
                    if d >= 30 {
                        return refuse("a distance that is no symbol");
                    }
                    let distance = (DISTANCE_BASE[d] + self.bits(DISTANCE_EXTRA[d])?) as usize;
                    if distance > out.len() {
                        return refuse("a distance to before the start");
                    }
                    // a byte at a time: a match may run into what it writes
                    (0..length).for_each(|_| out.push(out[out.len() - distance]));
                }
                _ => return refuse("a length that is no symbol"),
            }
        }
    }
}

/// A canonical Huffman code: how many codes there are of each length, and the symbols in the order of their codes.
struct Code {
    counts: [u16; 16],
    symbols: Vec<u16>,
}

impl Code {
    /// The code in which the symbol `i` has `lengths[i]` bits; 0: the symbol is not used.
    fn new(lengths: &[u8]) -> Result<Code, String> {
        let mut counts = [0u16; 16];
        lengths.iter().for_each(|&length| counts[length as usize] += 1);
        counts[0] = 0;
        // every length has twice the room of the one before, less what the shorter codes took
        let mut room = 1i32;
        for count in &counts[1..] {
            room = room * 2 - *count as i32;
            if room < 0 {
                return refuse("more codes of a length than the length has");
            }
        }
        let mut symbols: Vec<u16> = (0..lengths.len() as u16).filter(|&symbol| lengths[symbol as usize] > 0).collect();
        symbols.sort_by_key(|&symbol| lengths[symbol as usize]);
        Ok(Code { counts, symbols })
    }

    /// The next symbol: bits are taken one by one until they are a code of their length.
    fn symbol(&self, input: &mut Reader) -> Result<u16, String> {
        // the code so far; the first code of this length; the place among the symbols of the first of this length
        let (mut code, mut first, mut index) = (0i32, 0i32, 0i32);
        for count in &self.counts[1..] {
            code |= input.bits(1)? as i32;
            let count = *count as i32;
            if code - first < count {
                return Ok(self.symbols[(index + code - first) as usize]);
            }
            index += count;
            first = (first + count) << 1;
            code <<= 1;
        }
        refuse("bits that are no code")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bytes(hex: &str) -> Vec<u8> {
        (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap()).collect()
    }

    /// bytes with no pattern in them: a generator of three shifts
    fn random(n: usize) -> Vec<u8> {
        let mut x = 0x2545f4914f6cdd1du64;
        let mut next = || {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            (x >> 24) as u8
        };
        (0..n).map(|_| next()).collect()
    }

    const TEXT: &str = "A message is one JSON object. At first each is one line of UTF-8 text ended by a newline \
        (empty lines are skipped). After a configure the same objects travel as frames: four bytes of length, then \
        the message, encoded by the format and then, with deflate, compressed.";

    // what java.util.zip.Deflater (nowrap) made of each text, at its default level — and at level 0, stored
    #[test]
    fn what_the_other_side_deflated_is_inflated_fixed_dynamic_and_stored() {
        let fixed = "ab56ca4c51b232d451cacf56b232ad0500";
        assert_eq!(inflate(&bytes(fixed)).unwrap(), br#"{"id":1,"ok":5}"#);
        let repeated = "edc6b10900300800b0577c4ed05504dfef179d92295b19d793b12222222222222222f22b0f";
        assert_eq!(inflate(&bytes(repeated)).unwrap(), "the wire ".repeat(500).as_bytes());
        let dynamic = "358fb15203310c447f65cb307349cdd0a5a1480105f0018abdbe339ced1b4b21e4ef516e2e8d0aed5bedea8842551989\
            ac6895387dbcbfa19dbf19ec80a321e5ae064a981ec49c7db484afcfd7fd338c7f2ed7c888f30d82caeb0aec5816bbadb0423aa13f\
            7959189ffc6a32764743ab298f17d76c725d0ab7608575f9e50c51a4ee7b7d416a97ee09e6d73c7b661d6d1aeec6babab72f06af12\
            da56e6be4fad1731488d2b3be09a6d42649ac59c0ead2cddad8c877f";
        assert_eq!(inflate(&bytes(dynamic)).unwrap(), TEXT.as_bytes());
        let stored = "011000efff73746f7265642c206173206974206973";
        assert_eq!(inflate(&bytes(stored)).unwrap(), b"stored, as it is");
        // two blocks: a stored one that is not the last, then a fixed one
        assert_eq!(inflate(&bytes("000200fdff68690300")).unwrap(), b"hi");
    }

    #[test]
    fn there_and_back_empty_short_long_repetitive_and_random() {
        assert_eq!(deflate(b""), [0x03, 0x00]);
        let texts = [
            Vec::new(),
            b"a".to_vec(),
            b"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_vec(),
            TEXT.as_bytes().to_vec(),
            "the wire ".repeat(500).into_bytes(),
            vec![0; 100_000],
            (0..=255).cycle().take(70_000).collect(),
            random(100_000),
            // a repeat further back than a match may reach, and one just within
            [random(40_000), random(40_000)[..500].to_vec(), random(40_000)[39_000..].to_vec()].concat(),
        ];
        for text in &texts {
            assert_eq!(&inflate(&deflate(text)).unwrap(), text);
        }
    }

    #[test]
    fn what_repeats_shrinks_what_does_not_grows_little() {
        assert!(deflate("the wire ".repeat(500).as_bytes()).len() < 4500 / 10);
        assert!(deflate(&vec![0; 100_000]).len() < 1000);
        assert!(deflate(TEXT.as_bytes()).len() < TEXT.len());
        assert!(deflate(&random(100_000)).len() < 100_000 * 9 / 8);
    }

    #[test]
    fn what_is_not_deflate_is_refused_by_name() {
        let packed = deflate("the wire ".repeat(500).as_bytes());
        let refused: [&[u8]; 6] = [
            &packed[..packed.len() / 2],
            b"not deflate at all",
            &[],
            // a block of the type 3; a stored block whose length does not check; a distance to before the start
            &[0x07],
            &[0x01, 0x10, 0x00, 0x00, 0x00],
            &[0x03, 0x02, 0x00],
        ];
        for bytes in refused {
            let why = inflate(bytes).unwrap_err();
            assert!(why.starts_with("DEFLATE: "), "{why}");
        }
    }
}
