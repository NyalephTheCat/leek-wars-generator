//! Ergonomic, object-style game API — the Rust counterpart of `objects.js` /
//! `objects.py`. Every method is a thin typed wrapper over the [`abi::call`]
//! gateway.
//!
//! NOTE ON COVERAGE: this is the STARTER surface (movement, weapons, chips,
//! targeting, debug) that proves the ABI end-to-end. The full catalogue must be
//! generated from the Java side (`FightFunctions.getFunctions()` +
//! `LeekFunctions.getStandardFunctions()`), the same source `PolyglotAPIBridge`
//! resolves, so the Rust names match the host's reflective dispatch exactly.
//! Function names below use the canonical LeekScript names on purpose.

use crate::abi::call;
use crate::codec::Value;

/// A board cell, identified by its number.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cell(pub i32);

/// A combat entity (leek, bulb, turret…), identified by its id.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Entity(pub i32);

impl Entity {
    pub fn id(self) -> i32 {
        self.0
    }
    /// Current life.
    pub fn life(self) -> i32 {
        call("getLife", &[Value::Int(self.0 as i64)]).as_int() as i32
    }
    /// Maximum life.
    pub fn total_life(self) -> i32 {
        call("getTotalLife", &[Value::Int(self.0 as i64)]).as_int() as i32
    }
    /// The cell this entity stands on.
    pub fn cell(self) -> Cell {
        Cell(call("getCell", &[Value::Int(self.0 as i64)]).as_int() as i32)
    }
    pub fn is_alive(self) -> bool {
        self.life() > 0
    }
}

/// The entity currently being played (`me`). Its id is resolved fresh each call
/// so summoned-bulb turns see the bulb, matching the polyglot `me` semantics.
pub fn me() -> Entity {
    Entity(call("getEntity", &[]).as_int() as i32)
}

/// Movement points remaining this turn.
pub fn mp() -> i32 {
    call("getMP", &[]).as_int() as i32
}
/// Turn points remaining this turn.
pub fn tp() -> i32 {
    call("getTP", &[]).as_int() as i32
}

/// Nearest living enemy, or `None` if none remain.
pub fn nearest_enemy() -> Option<Entity> {
    let v = call("getNearestEnemy", &[]);
    match v.as_int() {
        id if id >= 0 && !v.is_null() => Some(Entity(id as i32)),
        _ => None,
    }
}

/// Line of sight between two cells.
pub fn line_of_sight(from: Cell, to: Cell) -> bool {
    call(
        "lineOfSight",
        &[Value::Int(from.0 as i64), Value::Int(to.0 as i64)],
    )
    .as_bool()
}

/// Walking distance (path length) between two cells.
pub fn cell_distance(from: Cell, to: Cell) -> i32 {
    call(
        "getCellDistance",
        &[Value::Int(from.0 as i64), Value::Int(to.0 as i64)],
    )
    .as_int() as i32
}

/// Move one step toward `cell`; returns MP actually spent.
pub fn move_toward_cell(cell: Cell) -> i32 {
    call("moveTowardCell", &[Value::Int(cell.0 as i64)]).as_int() as i32
}

/// Equip a weapon by its id.
pub fn set_weapon(weapon: i32) {
    call("setWeapon", &[Value::Int(weapon as i64)]);
}

/// Ids of the weapons carried by `me`.
pub fn weapons() -> Vec<i32> {
    match call("getWeapons", &[]) {
        Value::List(items) => items.iter().map(|v| v.as_int() as i32).collect(),
        _ => Vec::new(),
    }
}

/// Fire the equipped weapon at `target`; returns the use-result code.
pub fn use_weapon(target: Entity) -> i32 {
    call("useWeapon", &[Value::Int(target.0 as i64)]).as_int() as i32
}

/// Use a chip on `target`; returns the use-result code.
pub fn use_chip(chip: i32, target: Entity) -> i32 {
    call(
        "useChip",
        &[Value::Int(chip as i64), Value::Int(target.0 as i64)],
    )
    .as_int() as i32
}

/// Send a value to the fight debug log (visible to the player).
pub fn debug(message: &str) {
    call("debug", &[Value::Str(message.to_string())]);
}

/// Public chat bubble in the fight.
pub fn say(message: &str) {
    call("say", &[Value::Str(message.to_string())]);
}

/// Operations consumed so far this turn (the guest-visible op counter). Search
/// AIs self-limit on this — see the op-counter pitfall in the porting guide.
pub fn operations() -> i64 {
    call("getOperations", &[]).as_int()
}
