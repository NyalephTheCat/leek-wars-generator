//! Minimal Leek Wars AI in Rust — the Phase-0 ABI-validation artifact.
//!
//! It exercises every part of the boundary: a no-arg call (`getEntity`), scalar
//! args (`setWeapon`, `moveTowardCell`), a list result (`getWeapons`), and debug
//! output. Compiling this proves the guest half of the ABI: the module imports
//! `lw_call` and exports `turn` / `lw_alloc` / `lw_dealloc` / `memory`.

use leekwars::prelude::*;

fn play() {
    debug("rust ai: turn start");

    // Equip the first available weapon (falls back to the pistol).
    let weapon = weapons().first().copied().unwrap_or(WEAPON_PISTOL);
    set_weapon(weapon);

    if let Some(enemy) = nearest_enemy() {
        // Close in while we still have movement points.
        while mp() > 0 && cell_distance(me().cell(), enemy.cell()) > 1 {
            if move_toward_cell(enemy.cell()) == 0 {
                break;
            }
        }
        // Fire until we run out of turn points or the shot stops succeeding.
        while tp() > 0 {
            let r = use_weapon(enemy);
            if r != USE_SUCCESS && r != USE_CRITICAL {
                break;
            }
        }
    }
}

leekwars::main!(play);
