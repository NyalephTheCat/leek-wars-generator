# Spike — Rust polyglot AI (WASM) : ABI + état des lieux

> Compagnon de `POLYGLOT_PORTING_GUIDE.md` / `POLYGLOT_CUSTOM_ISOLATE_SPIKE.md`.
> Ce doc consigne les faits **vérifiés** et les inconnues **restantes** du support Rust,
> pour qu'une autre session reprenne sans le contexte d'origine. Runtime retenu :
> **GraalWasm** (langage Truffle `wasm`), avec **wasmtime** en repli désigné.

## Ce qui est LIVRÉ et VÉRIFIÉ (côté guest / Rust — indépendant du runtime hôte)

SDK joueur `sdk/rust/leekwars/` + IA d'exemple `sdk/rust/examples/dummy/`.

- **L'ABI hôte↔guest est figée et prouvée.** L'IA `dummy` compile en
  `wasm32-unknown-unknown` (54 Ko) et le module expose EXACTEMENT le contrat visé
  (vérifié en lisant les sections import/export du `.wasm`) :
  - **Imports (1)** : `env.lw_call` — passerelle générique unique. Choix délibéré :
    une seule import qui dispatch par NOM de fonction (comme le sac `__lw` de
    `PolyglotAPIBridge`), au lieu d'une import par fonction de jeu. L'hôte réutilise
    ainsi sa résolution réflective existante ; pas de centaines de signatures à tenir.
  - **Exports** : `memory`, `lw_alloc`, `lw_dealloc`, `turn` (+ `before_fight` si l'IA
    le définit). `lw_alloc`/`lw_dealloc` sont émis par la macro `leekwars::main!` DANS
    le crate joueur (sinon les symboles d'une dépendance rlib seraient élagués).
- **Le codec binaire est testé** (`cargo test -p leekwars`, 4 tests verts) : jeu de
  types calqué sur `TypeMarshaller` (null / int / double / string / list / map),
  little-endian, déterministe. Voir le format exact dans `leekwars/src/codec.rs`.
- **Protocole de résultat sans ré-entrance allocateur** : l'hôte écrit le résultat
  dans un buffer FOURNI PAR LE GUEST (`lw_call(req_ptr, req_len, res_ptr, res_cap)
  -> u32` = longueur réelle ; si > `res_cap`, le guest réessaie à la bonne taille).
  Évite que l'hôte ait à rappeler `lw_alloc` pendant une import (ré-entrance =
  inconnue GraalWasm, cf plus bas) — le protocole la contourne d'entrée.

## Faits GraalVM à VALIDER (côté hôte — bloqués ici, cf « Environnement »)

Le Phase-0 du plan. Aucun n'est infirmé ; ils exigent un GraalWasm qui tourne.

1. **Imports par fonctions hôte.** Peut-on satisfaire l'import `env.lw_call` d'un module
   GraalWasm par une fonction hôte via l'API polyglot, et lire/écrire la `memory`
   exportée du module depuis l'hôte ? C'est le pivot de tout le bridge. Replis :
   module `env` linker en wasm, ou objet d'imports via l'interop JS.
2. **Granularité d'instrumentation.** L'instrument `StatementCounter` tague-t-il
   l'exécution GraalWasm à une granularité utile (≈ instruction) ? Sinon il faut un
   compteur d'ops spécifique wasm (surfacé pareil via `getOperations()`).
3. **Coexistence de moteurs.** Un moteur wasm **in-process, non-ISOLATED** cohabite-t-il
   dans la même JVM que l'isolate ISOLATED js+python ? (La limite « une lib isolate par
   JVM » vise les libs natives d'isolate ; un moteur Truffle in-process ne devrait pas
   la déclencher.) In-process est préféré car **le wasm borne sa RAM nativement**
   (mémoire linéaire) — pas besoin de `sandbox.MaxHeapMemory`/image isolate custom.
4. **Déterminisme.** Threads/SIMD désactivés au build du moteur, NaN canoniques → replays
   bit-reproductibles.

## Découverte Phase-0 à répercuter sur l'hôte : la RAM n'est PAS auto-bornée

Le module `dummy` déclare sa mémoire linéaire avec **max = ∞** (min 17 pages). Donc
**c'est l'HÔTE qui doit imposer le cap RAM par-poireau** à l'instanciation (borne de
croissance de la mémoire linéaire dérivée de `getMaxRAM()` de l'entité, avec un
`RAM_FACTOR` propre au wasm à calibrer). Ne PAS bake un `--max-memory` fixe au build :
la RAM est une stat PAR ENTITÉ, connue au combat, pas à la compilation.

## Environnement de dev (pourquoi l'hôte Java n'est pas vérifiable ici)

- **Java 21** installé ; le projet exige **Java 25** (`build.gradle` VERSION_25).
- Sous-module **`leekscript` absent** (privé, SSH) → la classe de base `AI` manque.
- **Image isolate GraalVM absente** (`libs/` vide) → moteurs ISOLATED indisponibles.
- Rust 1.94 + cargo présents ; cible `wasm32-unknown-unknown` ajoutée ; pas de `wasm-opt`.

→ Le générateur Java **ne compile pas** dans ce conteneur. Toute la Phase-0 hôte
(GraalWasm qui tourne) et la compilation du bridge Java doivent se faire sur un env
Java 25 + submodule + dépendance GraalWasm.

## Prochaines étapes (ordre)

1. Env Java 25 + submodule + `org.graalvm.polyglot:wasm:25.1.3` : lever les 4 inconnues (harnais jetable).
2. `WasmMarshaller` Java : miroir hôte du codec (cf `codec.rs`) — la moitié « wire » est sans dépendance.
3. `WasmAPIBridge` (satisfait `lw_call`, réutilise la résolution de `PolyglotAPIBridge`) + `WasmEntityAI` (instanciation, cap mémoire, boucle de tour calquée sur `PolyglotEntityAI`).
4. `RustCompiler` : `cargo build --target wasm32-unknown-unknown --offline`, caché, SANDBOXÉ (cf risque RCE build-time), reproductible.
5. Détection `.rs` (`PolyglotEntityAI.detectLanguage`) + enregistrement `"wasm"` (`Fight.getPolyglotSandbox`) + dispatch (`EntityAI.build`).
6. Calibration ops/RAM + tests `TestWasm*`.
