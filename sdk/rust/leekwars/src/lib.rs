//! # leekwars — Rust SDK for Leek Wars combat AIs
//!
//! Write an AI, compile it to `wasm32-unknown-unknown`, and the Leek Wars
//! generator runs it as a polyglot AI (see the project plan for the host side).
//!
//! ```ignore
//! use leekwars::prelude::*;
//!
//! fn play() {
//!     set_weapon(WEAPON_PISTOL);
//!     if let Some(enemy) = nearest_enemy() {
//!         while cell_distance(me().cell(), enemy.cell()) > 1 && mp() > 0 {
//!             move_toward_cell(enemy.cell());
//!         }
//!         use_weapon(enemy);
//!     }
//! }
//!
//! leekwars::main!(play);
//! ```
//!
//! The [`main!`] macro wires the WASM entry contract: it exports `turn` (called
//! every turn), the guest allocator (`lw_alloc` / `lw_dealloc`), and — if you
//! pass a second function — `before_fight`. Module linear memory persists across
//! turns, so `static`/global AI state carries over the whole fight.

pub mod abi;
pub mod api;
pub mod codec;
pub mod constants;

pub use codec::Value;

/// Everything a typical AI needs, in one glob import.
pub mod prelude {
    pub use crate::api::*;
    pub use crate::constants::*;
    pub use crate::codec::Value;
}

/// Generate the WASM entry points for an AI.
///
/// - `main!(turn_fn)` exports `turn` (+ the allocator).
/// - `main!(turn_fn, before_fight_fn)` also exports `before_fight`, run once
///   before the fight starts.
///
/// The allocator exports are emitted here (in the PLAYER crate) so they are
/// guaranteed to appear in the final cdylib rather than being dropped as unused
/// dependency symbols.
#[macro_export]
macro_rules! main {
    ($turn:expr) => {
        #[no_mangle]
        pub extern "C" fn turn() {
            $crate::abi::run_turn($turn);
        }
        $crate::main!(@runtime);
    };
    ($turn:expr, $before:expr) => {
        #[no_mangle]
        pub extern "C" fn turn() {
            $crate::abi::run_turn($turn);
        }
        #[no_mangle]
        pub extern "C" fn before_fight() {
            $crate::abi::run_turn($before);
        }
        $crate::main!(@runtime);
    };
    (@runtime) => {
        #[no_mangle]
        pub extern "C" fn lw_alloc(size: u32) -> u32 {
            $crate::abi::alloc(size)
        }
        #[no_mangle]
        pub extern "C" fn lw_dealloc(ptr: u32, size: u32) {
            unsafe { $crate::abi::dealloc(ptr, size) }
        }
    };
}

#[cfg(test)]
mod tests {
    use crate::codec::{decode_response, encode_request, Value};

    // Host-independent round-trip: encode a request, hand-assemble the matching
    // response, decode it. Guards the wire format the Java WasmMarshaller mirrors.
    #[test]
    fn request_roundtrip_shapes() {
        let req = encode_request(
            "useChip",
            &[Value::Int(37), Value::Int(210), Value::Str("hi".into())],
        );
        // name_len(4) + "useChip"(7) + argc(4) + int(1+8)+int(1+8)+str(1+4+2)
        assert_eq!(req.len(), 4 + 7 + 4 + 9 + 9 + 7);
    }

    #[test]
    fn decode_ok_and_err() {
        // status=0, INT 42
        let mut ok = vec![0u8, 0x02];
        ok.extend_from_slice(&42i64.to_le_bytes());
        assert_eq!(decode_response(&ok), Ok(Value::Int(42)));

        // status=1, STRING "boom"
        let mut err = vec![1u8, 0x04];
        err.extend_from_slice(&4u32.to_le_bytes());
        err.extend_from_slice(b"boom");
        assert_eq!(decode_response(&err), Err("boom".to_string()));
    }

    #[test]
    fn decode_truncated_is_graceful() {
        assert_eq!(decode_response(&[]), Ok(Value::Null));
        assert_eq!(decode_response(&[0u8, 0x02, 0x01]), Ok(Value::Int(0)));
    }

    #[test]
    fn nested_list_and_map_roundtrip() {
        // Build a response by encoding a value through a request and reading it
        // back with the same reader the response path uses.
        let payload = Value::List(vec![
            Value::Int(1),
            Value::Map(vec![(Value::Str("k".into()), Value::Float(2.5))]),
            Value::Null,
        ]);
        let req = encode_request("x", std::slice::from_ref(&payload));
        // Reassemble as a response: status byte + the single arg's bytes.
        // Skip name_len(4)+"x"(1)+argc(4) header to reach the value bytes.
        let mut resp = vec![0u8];
        resp.extend_from_slice(&req[9..]);
        assert_eq!(decode_response(&resp), Ok(payload));
    }
}
