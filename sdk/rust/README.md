# Leek Wars — Rust AI SDK

Write a Leek Wars combat AI in Rust, compiled to WebAssembly. This is the Rust
counterpart of the injected JS/Python object preludes (`objects.js` / `objects.py`):
the [`leekwars`](leekwars/) crate exposes the game API as ergonomic types over a
single host-call gateway.

## Layout

- `leekwars/` — the player-facing SDK crate (rlib).
  - `src/codec.rs` — the binary value codec (mirrored on the host by
    `com.leekwars.generator.polyglot.WasmMarshaller`).
  - `src/abi.rs` — the `lw_call` gateway import + guest allocator + turn glue.
  - `src/api.rs` — the object API (`me`, `Entity`, `Cell`, weapons, chips, …).
  - `src/constants.rs` — game constants (generated; starter subset for now).
- `examples/dummy/` — a minimal AI; also the Phase‑0 ABI‑validation artifact.

## Writing an AI

```rust
use leekwars::prelude::*;

fn play() {
    set_weapon(WEAPON_PISTOL);
    if let Some(enemy) = nearest_enemy() {
        while mp() > 0 && cell_distance(me().cell(), enemy.cell()) > 1 {
            move_toward_cell(enemy.cell());
        }
        use_weapon(enemy);
    }
}

leekwars::main!(play);          // exports `turn` (+ allocator). Optional 2nd arg = before_fight.
```

Module linear memory persists across turns, so `static` / global state carries
over the whole fight.

## Build

```sh
rustup target add wasm32-unknown-unknown
cargo build --release --target wasm32-unknown-unknown   # -> target/.../<crate>.wasm
```

The generator's compilation service builds this for the player at save time and
caches the `.wasm` (see the project plan, Phase 3). Producing the module directly
is only needed for local testing.

## The ABI in one paragraph

The module imports exactly one host function, `env.lw_call(req_ptr, req_len,
res_ptr, res_cap) -> u32`, and exports `memory`, `lw_alloc`, `lw_dealloc`, `turn`
(and `before_fight` if defined). A call serializes `(name, args)` into `req`; the
host resolves the function by name (reusing the same reflective dispatch as
`PolyglotAPIBridge`), runs it, and writes the encoded result into the guest-owned
`res` buffer — returning the true length so the guest can retry once if its buffer
was too small. No host→guest allocator re-entrancy is required. Values are
`null | int(i64) | double | string | list | map`, matching LeekScript's set.

## Tests

```sh
cargo test -p leekwars      # codec round-trips (host target)
```

Cross-language byte compatibility with the Java host codec is guarded by
`test.TestWasmMarshaller` on the generator side against the same golden bytes.
