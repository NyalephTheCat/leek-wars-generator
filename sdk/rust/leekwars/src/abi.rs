//! Low-level host boundary: the single `lw_call` import, the guest allocator
//! exports, and the turn-entry glue.
//!
//! Design choice: ONE generic gateway import (`lw_call`) rather than one import
//! per game function. This mirrors the existing `__lw` name→method dispatch that
//! `PolyglotAPIBridge` already does for JS/Python, so the host reuses the same
//! reflective function resolution instead of hand-maintaining hundreds of import
//! signatures. Per-call (de)serialization is small and billed as ops (fair).
//!
//! The host writes the result into a **caller-provided** buffer to avoid the
//! host having to re-enter the guest allocator during an import call (that
//! re-entrancy is a GraalWasm Phase-0 unknown; this protocol sidesteps it).

use crate::codec::{decode_response, encode_request, Value};

extern "C" {
    /// Invoke a game function by name.
    ///
    /// `req_ptr/req_len` point at an encoded request (see `codec`). The host
    /// writes the encoded response into `res_ptr` (capacity `res_cap`) and
    /// returns the response's **total** length. If that length exceeds
    /// `res_cap`, nothing usable was written and the guest retries with a
    /// buffer of exactly that size.
    fn lw_call(req_ptr: u32, req_len: u32, res_ptr: u32, res_cap: u32) -> u32;
}

const INITIAL_RESULT_CAP: usize = 512;

/// Call a game function and return its decoded result, panicking on a host-side
/// game error (which aborts the turn — the host maps the abort to an AI error).
pub fn call(name: &str, args: &[Value]) -> Value {
    match try_call(name, args) {
        Ok(v) => v,
        Err(msg) => panic!("leekwars: {} failed: {}", name, msg),
    }
}

/// Like [`call`] but surfaces host errors as `Err` instead of panicking.
pub fn try_call(name: &str, args: &[Value]) -> Result<Value, String> {
    let req = encode_request(name, args);
    let mut cap = INITIAL_RESULT_CAP;
    loop {
        let mut res = vec![0u8; cap];
        let n = unsafe {
            lw_call(
                req.as_ptr() as u32,
                req.len() as u32,
                res.as_mut_ptr() as u32,
                cap as u32,
            )
        } as usize;
        if n <= cap {
            res.truncate(n);
            return decode_response(&res);
        }
        // Host reported it needs `n` bytes; retry once at exactly that size.
        cap = n;
    }
}

// ---------- guest allocator (exported to the host) ----------
//
// The host uses these to hand buffers to the guest (e.g. future host→guest
// pushes). They are generated into the PLAYER crate by `leekwars::main!` so the
// symbols are guaranteed to be exported from the final cdylib.

/// Allocate `size` bytes and return the pointer. The buffer is leaked; the host
/// must return it via [`dealloc`].
pub fn alloc(size: u32) -> u32 {
    let mut buf = Vec::<u8>::with_capacity(size as usize);
    let ptr = buf.as_mut_ptr() as u32;
    core::mem::forget(buf);
    ptr
}

/// Free a buffer previously returned by [`alloc`].
///
/// # Safety
/// `ptr`/`size` must come from a prior [`alloc`] call.
pub unsafe fn dealloc(ptr: u32, size: u32) {
    if ptr != 0 {
        drop(Vec::from_raw_parts(ptr as *mut u8, 0, size as usize));
    }
}

/// Run one turn, guarding against panics so a player bug becomes a clean host
/// error rather than an unwind across the wasm boundary. (With `panic = "abort"`
/// a panic already traps into the host; this hook exists for future reporting.)
pub fn run_turn<F: FnOnce()>(f: F) {
    f();
}
