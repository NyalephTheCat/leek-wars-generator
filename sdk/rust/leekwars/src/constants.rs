//! Game constants (`WEAPON_*`, `CHIP_*`, `EFFECT_*`, `COLOR_*`, …).
//!
//! GENERATED FILE (starter subset). The full set must be emitted from the Java
//! side — `FightConstants.values()` + `LeekConstants.values()`, the exact source
//! `PolyglotAPIBridge.resolveConstants()` reads — so values stay in lockstep with
//! the engine. Baked in as `const` (compile-time, deterministic, zero per-access
//! op cost) rather than fetched at runtime.
//!
//! The handful below exist only to exercise the SDK in the Phase-0 example.

// Weapons (ids as used by setWeapon / getWeapons).
pub const WEAPON_PISTOL: i32 = 1;
pub const WEAPON_MACHINE_GUN: i32 = 2;
pub const WEAPON_MAGNUM: i32 = 15;

// Chips.
pub const CHIP_BANDAGE: i32 = 1;
pub const CHIP_SPARK: i32 = 37;

// Use-result codes returned by useWeapon / useChip.
pub const USE_CRITICAL: i32 = 1;
pub const USE_SUCCESS: i32 = 2;
pub const USE_FAILED: i32 = 0;
pub const USE_INVALID_TARGET: i32 = -1;
pub const USE_NOT_ENOUGH_TP: i32 = -2;
pub const USE_INVALID_POSITION: i32 = -3;
pub const USE_TOO_MANY_USES: i32 = -4;
