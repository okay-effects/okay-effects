//! ONE END OF THE WIRE: messages out, messages in. JSON lines at first; after a `configure` to anything else,
//! FRAMES — a 4-byte big-endian length, then the message: JSON text or CBOR, raw DEFLATE over it when asked.

use std::io::{BufRead, BufReader, Read, Result, Write};

use crate::json::Json;
use crate::wire::{cbor, deflate};

pub struct Link {
    input: Box<dyn BufRead>,
    output: Box<dyn Write>,
    cbor: bool,
    deflate: bool,
}

impl Link {
    pub fn new(input: impl Read + 'static, output: impl Write + 'static) -> Link {
        Link { input: Box::new(BufReader::new(input)), output: Box::new(output), cbor: false, deflate: false }
    }

    pub fn configure(&mut self, cbor: bool, deflate: bool) {
        (self.cbor, self.deflate) = (cbor, deflate);
    }

    pub fn send(&mut self, message: &Json) -> Result<()> {
        if !self.cbor && !self.deflate {
            writeln!(self.output, "{message}")?;
        } else {
            let plain = if self.cbor { cbor::encode(message) } else { message.to_string().into_bytes() };
            let bytes = if self.deflate { deflate::deflate(&plain) } else { plain };
            self.output.write_all(&(bytes.len() as u32).to_be_bytes())?;
            self.output.write_all(&bytes)?;
        }
        self.output.flush()
    }

    /// The next message; none when the other end has closed; the reason, for one that cannot be read.
    pub fn receive(&mut self) -> std::result::Result<Option<Json>, String> {
        if !self.cbor && !self.deflate {
            return self.line().map(|line| Json::parse(&line)).transpose();
        }
        let Some(bytes) = self.frame() else { return Ok(None) };
        let plain = if self.deflate { deflate::inflate(&bytes)? } else { bytes };
        if self.cbor { cbor::decode(&plain).map(Some) } else { Json::parse(&String::from_utf8_lossy(&plain)).map(Some) }
    }

    /// the next line that is not empty
    fn line(&mut self) -> Option<String> {
        loop {
            let mut bytes = Vec::new();
            let read = self.input.read_until(b'\n', &mut bytes).unwrap_or(0);
            let text = String::from_utf8_lossy(&bytes).trim().to_string();
            if !text.is_empty() {
                return Some(text);
            }
            if read == 0 {
                return None;
            }
        }
    }

    fn frame(&mut self) -> Option<Vec<u8>> {
        let mut length = [0; 4];
        self.input.read_exact(&mut length).ok()?;
        let length = u32::from_be_bytes(length) as u64;
        let mut bytes = Vec::new();
        // read as it comes: a length that lies takes no room; a frame cut short is the end of the wire
        let read = self.input.by_ref().take(length).read_to_end(&mut bytes).ok()?;
        (read as u64 == length).then_some(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    /// what a link wrote, to be read by another
    #[derive(Clone, Default)]
    struct Written(Rc<RefCell<Vec<u8>>>);

    impl Write for Written {
        fn write(&mut self, bytes: &[u8]) -> Result<usize> {
            self.0.borrow_mut().extend_from_slice(bytes);
            Ok(bytes.len())
        }

        fn flush(&mut self) -> Result<()> {
            Ok(())
        }
    }

    #[test]
    fn lines_until_configured_then_a_length_and_the_message_in_the_format_chosen() {
        let text =
            r#"{"id":1,"ok":[{"t":"int","v":"9007199254740993"},{"t":"f","v":3.0},-0.5,null,"é \"q\" \n 日本 😀"]}"#;
        let message = Json::parse(text).unwrap();
        for (cbor, deflate) in [(false, false), (false, true), (true, false), (true, true)] {
            let written = Written::default();
            let mut sender = Link::new(std::io::empty(), written.clone());
            sender.send(&message).unwrap();
            sender.configure(cbor, deflate);
            sender.send(&message).unwrap();
            sender.send(&Json::Null).unwrap();
            let mut receiver = Link::new(std::io::Cursor::new(written.0.take()), std::io::sink());
            assert_eq!(receiver.receive(), Ok(Some(message.clone())));
            receiver.configure(cbor, deflate);
            assert_eq!(receiver.receive(), Ok(Some(message.clone())));
            assert_eq!(receiver.receive(), Ok(Some(Json::Null)));
            assert_eq!(receiver.receive(), Ok(None));
        }
    }

    #[test]
    fn the_frame_of_the_readme_and_frames_that_are_not() {
        let written = Written::default();
        let mut link = Link::new(std::io::empty(), written.clone());
        link.configure(true, false);
        link.send(&Json::parse(r#"{"id":1,"op":"call","fn":"t:add","args":[2,3]}"#).unwrap()).unwrap();
        let frame: String = written.0.borrow().iter().map(|byte| format!("{byte:02x} ")).collect();
        let readme = "00 00 00 1e  a4 62 69 64 01 62 6f 70 64 63 61 6c 6c 62 66 6e 65 74 3a 61 64 64 64 61 72 67 73 \
            82 02 03";
        assert_eq!(frame.trim(), readme.replace("  ", " "));

        // a frame that is not CBOR is refused and the next is read; one cut short is the end
        let bytes = [&[0, 0, 0, 1, 0x41, 0, 0, 0, 1, 0x05, 0, 0, 0, 9, 0x05][..]].concat();
        let mut link = Link::new(std::io::Cursor::new(bytes), std::io::sink());
        link.configure(true, false);
        assert!(link.receive().unwrap_err().starts_with("CBOR: "));
        assert_eq!(link.receive(), Ok(Some(Json::Int(5))));
        assert_eq!(link.receive(), Ok(None));
    }

    #[test]
    fn a_line_that_is_not_json_is_refused_empty_lines_are_skipped() {
        let mut link = Link::new(std::io::Cursor::new("\n\n{\"id\":1}\r\n \nnot JSON\n{\"id\":2}"), std::io::sink());
        assert_eq!(link.receive(), Ok(Some(Json::obj([("id", Json::Int(1))]))));
        assert!(link.receive().is_err());
        assert_eq!(link.receive(), Ok(Some(Json::obj([("id", Json::Int(2))]))));
        assert_eq!(link.receive(), Ok(None));
    }
}
