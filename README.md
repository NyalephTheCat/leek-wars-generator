# Leek Wars Generator

[![CI](https://github.com/leek-wars/leek-wars-generator/actions/workflows/build.yml/badge.svg)](https://github.com/leek-wars/leek-wars-generator/actions/workflows/build.yml)

Leek Wars fight generator using [leekscript](https://github.com/leek-wars/leekscript) language.

## Requirements
- Java 25 (OpenJDK or Amazon Corretto)
- Gradle 9.x

## Build
```
gradle jar
```

## Test
```
gradle test
```

## AI analysis task
```
java -jar generator.jar --analyze test/ai/basic.leek
```
![Fight generation task](https://github.com/leek-wars/leek-wars-generator-v1/blob/master/doc/compilation_task.svg)

## Fight generation task
```
java -jar generator.jar test/scenario/scenario1.json
```

![Fight generation task](https://github.com/leek-wars/leek-wars-generator-v1/blob/master/doc/fight_task.svg)

## Tester une IA en local

Deux façons, selon le langage de l'IA.

### Docker (rien à installer côté Java)

Le `Dockerfile` récupère l'isolate **prébuilt** (release publique) et compile le
générateur — **seul le Java est compilé, jamais GraalVM** (le build lourd de l'image
isolate n'est pas rejoué) :

```sh
git clone https://github.com/leek-wars/leek-wars-generator && cd leek-wars-generator
git submodule update --init --recursive
docker build -t leek-wars-generator .

# IA LeekScript, JavaScript OU Python : monte le dossier courant sur /ai
docker run --rm -v "$PWD":/ai leek-wars-generator --analyze /ai/mon_ia.js
docker run --rm -v "$PWD":/ai leek-wars-generator /ai/scenario.json
```

> La CI produit aussi une image `ghcr.io/leek-wars/leek-wars-generator`. Selon la
> politique de l'organisation, elle peut n'être accessible qu'après `docker login ghcr.io` ;
> le `docker build` ci-dessus, lui, marche pour tout le monde sans authentification.

### En Java, sans Docker

- **IA LeekScript** : rien de plus que le jar. `gradle jar` puis :

  ```sh
  java -jar generator.jar --analyze test/ai/basic.leek
  java -jar generator.jar test/scenario/scenario1.json
  ```

- **IA JavaScript / Python (polyglot)** : il faut en plus l'**image isolate** (lib native
  GraalVM, linux-amd64). Elle n'est pas commitée (127 Mo) — récupère-la depuis la release
  publique dans `libs/` avant `gradle jar` :

  ```sh
  mkdir -p libs
  curl -fsSL -o libs/js-isolate-resources-linux-amd64.jar \
    https://github.com/leek-wars/leek-wars-graal-isolate/releases/download/v25.1.3-combined-2/js-isolate-resources-linux-amd64.jar
  gradle jar
  java -jar generator.jar mon_scenario.json      # les IA .js / .py sont détectées par extension
  ```

  L'image isolate se rebuild aussi soi-même (build lourd) : voir
  [leek-wars-graal-isolate](https://github.com/leek-wars/leek-wars-graal-isolate).

### Format de scénario

Un combat local est décrit par un JSON (fermiers, équipes, entités avec leur IA/arme/puces,
carte, seed…). Voir `test/scenario/scenario1.json` pour un exemple 2v2 complet ; le champ
`ai` de chaque entité pointe un fichier `.leek`, `.js` ou `.py`.

## Profiler une IA (flamegraph)

Le générateur sait produire un **flamegraph des IA exécutées, pondéré par le coût en
opérations** — pas par le temps. C'est le compteur d'opérations qui est la ressource
facturée au joueur, et il est déterministe : le même combat rejoué depuis sa seed donne le
même profil, contrairement à un profil temporel qui mesurerait surtout le JIT.

```sh
java -jar generator.jar --profile test/scenario/scenario-profile.json      # -> profile/
java -jar generator.jar --profile=/tmp/prof test/scenario/scenario-profile.json
```

Chaque combat écrit `<dossier>/fight-<id>/` :

| Fichier | Contenu |
|---|---|
| `entity-<id>-<nom>.folded` | un profil par IA, au format *folded stacks* |
| `merged.folded` | les mêmes, fusionnés (une tour par entité) |
| `turns.csv` | `entity_id,entity_name,turn,ops,wall_ns` — le coût tour par tour |
| `summary.txt` | totaux, nombre de contextes d'appel, drapeau de troncature |

Une ligne *folded* est une pile d'appels et son coût **propre** en opérations :

```
Patrick#0;strategy.leek:play;strategy.leek:bestEnemy;strategy.leek:distanceScore 28920
```

À rendre avec n'importe quel outil du format :

```sh
flamegraph.pl --countname ops profile/fight-0/merged.folded > flame.svg
# ou déposer merged.folded sur https://speedscope.app
```

Le profil couvre **tout le combat**, pas un tour : le compteur d'opérations est remis à zéro
à chaque tour, l'arbre des contextes d'appel, lui, est conservé. Le travail d'une invocation
apparaît comme sa propre tour, dans l'arbre de son invocateur — c'est là que ses opérations
sont réellement facturées.

Le mode profil est un **outil de développement** :

- il instrumente le code généré (entrée/sortie de fonction), donc il n'utilise pas le cache
  disque des classes compilées et ne le pollue pas ;
- il ne consomme **aucune opération ni RAM joueur** : il lit le compteur, il ne l'alimente
  pas. Un combat profilé facture exactement comme un combat normal — un test le vérifie ;
- pour les IA JavaScript / Python, il relâche la policy sandbox GraalVM de `ISOLATED` à
  `TRUSTED` + `spawnIsolate(true)` (seul moyen d'attacher un `ExecutionListener`, interdit
  sous `ISOLATED`). L'isolate, l'image native des langages et les caps mémoire par contexte
  sont conservés, mais **à ne jamais activer en production**.

## Credits
Developed by Dawyde & Pilow © 2012-2026
