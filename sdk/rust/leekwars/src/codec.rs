//! Binary value codec shared by the guest (this crate) and the host
//! (`WasmMarshaller` on the Java side).
//!
//! The type set mirrors what LeekScript / the polyglot `TypeMarshaller` already
//! exchange: null, int, double, string, array (list), map. Keeping the set
//! identical means a ported AI sees the same value shapes it would in JS/Python.
//!
//! Wire format (little-endian, deterministic — no map re-ordering, canonical
//! NaN is the host's responsibility):
//!
//! ```text
//! value   := tag:u8 payload
//!   0x00 NULL     (no payload)
//!   0x01 BOOL     u8 (0/1)
//!   0x02 INT      i64
//!   0x03 FLOAT    f64
//!   0x04 STRING   len:u32 utf8[len]
//!   0x05 LIST     count:u32 value*count
//!   0x06 MAP      count:u32 (key:value, val:value)*count
//!
//! request  := name_len:u32 name_utf8 argc:u32 value*argc
//! response := status:u8 (0=ok,1=err) value        // err -> value is a STRING message
//! ```

const TAG_NULL: u8 = 0x00;
const TAG_BOOL: u8 = 0x01;
const TAG_INT: u8 = 0x02;
const TAG_FLOAT: u8 = 0x03;
const TAG_STRING: u8 = 0x04;
const TAG_LIST: u8 = 0x05;
const TAG_MAP: u8 = 0x06;

/// A value crossing the host boundary. Mirrors the LeekScript value set.
#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    List(Vec<Value>),
    Map(Vec<(Value, Value)>),
}

impl Value {
    pub fn as_int(&self) -> i64 {
        match self {
            Value::Int(i) => *i,
            Value::Float(f) => *f as i64,
            Value::Bool(b) => *b as i64,
            _ => 0,
        }
    }
    pub fn as_float(&self) -> f64 {
        match self {
            Value::Float(f) => *f,
            Value::Int(i) => *i as f64,
            _ => 0.0,
        }
    }
    pub fn as_bool(&self) -> bool {
        match self {
            Value::Bool(b) => *b,
            Value::Int(i) => *i != 0,
            Value::Null => false,
            _ => true,
        }
    }
    pub fn as_str(&self) -> &str {
        match self {
            Value::Str(s) => s,
            _ => "",
        }
    }
    pub fn is_null(&self) -> bool {
        matches!(self, Value::Null)
    }
}

// ---------- encoding ----------

fn write_value(out: &mut Vec<u8>, v: &Value) {
    match v {
        Value::Null => out.push(TAG_NULL),
        Value::Bool(b) => {
            out.push(TAG_BOOL);
            out.push(*b as u8);
        }
        Value::Int(i) => {
            out.push(TAG_INT);
            out.extend_from_slice(&i.to_le_bytes());
        }
        Value::Float(f) => {
            out.push(TAG_FLOAT);
            out.extend_from_slice(&f.to_le_bytes());
        }
        Value::Str(s) => {
            out.push(TAG_STRING);
            out.extend_from_slice(&(s.len() as u32).to_le_bytes());
            out.extend_from_slice(s.as_bytes());
        }
        Value::List(items) => {
            out.push(TAG_LIST);
            out.extend_from_slice(&(items.len() as u32).to_le_bytes());
            for it in items {
                write_value(out, it);
            }
        }
        Value::Map(entries) => {
            out.push(TAG_MAP);
            out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
            for (k, val) in entries {
                write_value(out, k);
                write_value(out, val);
            }
        }
    }
}

/// Encode a `lw_call` request: function name + positional args.
pub fn encode_request(name: &str, args: &[Value]) -> Vec<u8> {
    let mut out = Vec::with_capacity(16 + name.len());
    out.extend_from_slice(&(name.len() as u32).to_le_bytes());
    out.extend_from_slice(name.as_bytes());
    out.extend_from_slice(&(args.len() as u32).to_le_bytes());
    for a in args {
        write_value(&mut out, a);
    }
    out
}

// ---------- decoding ----------

/// A forward-only reader over a response buffer. Underflow yields `Null`
/// rather than panicking (the host controls these bytes; a short read is a
/// protocol bug that degrades gracefully to a no-op value).
struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        if end > self.buf.len() {
            return None;
        }
        let s = &self.buf[self.pos..end];
        self.pos = end;
        Some(s)
    }
    fn u8(&mut self) -> Option<u8> {
        self.take(1).map(|s| s[0])
    }
    fn u32(&mut self) -> Option<u32> {
        self.take(4).map(|s| u32::from_le_bytes(s.try_into().unwrap()))
    }
    fn i64(&mut self) -> Option<i64> {
        self.take(8).map(|s| i64::from_le_bytes(s.try_into().unwrap()))
    }
    fn f64(&mut self) -> Option<f64> {
        self.take(8).map(|s| f64::from_le_bytes(s.try_into().unwrap()))
    }
    fn value(&mut self) -> Value {
        match self.u8() {
            Some(TAG_NULL) => Value::Null,
            Some(TAG_BOOL) => Value::Bool(self.u8().unwrap_or(0) != 0),
            Some(TAG_INT) => Value::Int(self.i64().unwrap_or(0)),
            Some(TAG_FLOAT) => Value::Float(self.f64().unwrap_or(0.0)),
            Some(TAG_STRING) => {
                let len = self.u32().unwrap_or(0) as usize;
                let bytes = self.take(len).unwrap_or(&[]);
                Value::Str(String::from_utf8_lossy(bytes).into_owned())
            }
            Some(TAG_LIST) => {
                let count = self.u32().unwrap_or(0);
                let mut items = Vec::with_capacity(count.min(1024) as usize);
                for _ in 0..count {
                    items.push(self.value());
                }
                Value::List(items)
            }
            Some(TAG_MAP) => {
                let count = self.u32().unwrap_or(0);
                let mut entries = Vec::with_capacity(count.min(1024) as usize);
                for _ in 0..count {
                    let k = self.value();
                    let v = self.value();
                    entries.push((k, v));
                }
                Value::Map(entries)
            }
            _ => Value::Null,
        }
    }
}

/// Decode a `lw_call` response. Returns `Err(message)` when the host reports a
/// game/runtime error (status byte 1); the SDK surfaces this as a Rust panic in
/// the ergonomic wrappers so a failing combat call aborts the turn cleanly.
pub fn decode_response(buf: &[u8]) -> Result<Value, String> {
    let mut r = Reader::new(buf);
    match r.u8() {
        Some(0) => Ok(r.value()),
        Some(_) => Err(match r.value() {
            Value::Str(s) => s,
            other => format!("{:?}", other),
        }),
        None => Ok(Value::Null),
    }
}
