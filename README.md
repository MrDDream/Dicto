# Dicto

La dictée, chez toi. Application de reconnaissance vocale pour Android, Windows et Linux, avec des modèles locaux (whisper.cpp) ou des services en ligne (OpenAI, Mistral, Gemini, Grok, serveur personnel…).

Design : canvas Claude Design « Dicto — Speech to text ». Couleurs, polices et rayons sont repris dans [design/tokens.json](design/tokens.json).

## Organisation

| Dossier | Contenu |
| --- | --- |
| `core/dicto-core` | Cœur Rust partagé : audio, VAD, les 3 modes, moteurs STT, catalogue et téléchargement des modèles, SQLite, exports |
| `core/dicto-ffi` | Bindings UniFFI (Kotlin) utilisés par l'app Android |
| `core/catalog/models.json` | Modèles locaux proposés (URL, taille, SHA-256) |
| `android/` | App Kotlin / Jetpack Compose : dictée, modèles, historique, clavier vocal |
| `scripts/` | `env.sh` (JDK, SDK, NDK) et `build-android-libs.sh` |
| `desktop/` | App Tauri 2 + Svelte 5 *(à venir)* |

## Cœur Rust

```bash
cd core
cargo test
```

Moteurs locaux (features Cargo) :

- `whisper` : whisper.cpp (il faut `cmake` et libclang, voir `scripts/env.sh`) ;
- `sherpa` : sherpa-onnx (Parakeet, Canary, Nemotron, Omnilingual, mot d'activation). Ses bibliothèques officielles sont téléchargées à la compilation. Sur le bureau, avec whisper dans le même binaire, utiliser `sherpa-shared` (liaison dynamique : les deux runtimes C++ en statique entrent en conflit).

```bash
source scripts/env.sh
cargo build -p dicto-ffi --features whisper,sherpa-shared
cargo run --release -p dicto-core --features whisper,sherpa-shared --example transcribe -- fichier.wav canary-180m-flash-int8 ./modeles fr
```

Le catalogue des modèles ([core/catalog/models.json](core/catalog/models.json)) indique pour chacun ses plateformes, langues, licence et empreinte SHA-256. L'app Android ne propose que les modèles marqués `android`.

Générer les classes Kotlin :

```bash
cargo build -p dicto-ffi --features cli
./target/debug/uniffi-bindgen generate --library target/debug/libdicto_ffi.so --language kotlin --out-dir ../android/app/src/main/java
```

## Android

Les outils sont pris dans `~/android-tools` (JDK 21, SDK, NDK 29), modifiable par `ANDROID_TOOLS`, `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_NDK_HOME`.

```bash
scripts/build-android-libs.sh                      # cœur Rust + whisper.cpp → jniLibs, classes Kotlin
source scripts/env.sh && cd android && ./gradlew :app:assembleDebug
```

Les bibliothèques natives et les classes UniFFI sont générées (ignorées par git) : relance le script après chaque modification de `core/`.

Tests du pont Kotlin ↔ Rust sur la JVM (bibliothèque de la machine, whisper inclus) :

```bash
(cd core && cargo build -p dicto-ffi --features whisper)
cd android && ./gradlew :app:testDebugUnitTest \
    -Pdicto.whisperModel=/chemin/ggml-tiny-q5_1.bin -Pdicto.sampleWav=/chemin/jfk.wav
```

## Les trois modes

- **Push-to-talk** : un appui pour commencer, un second pour terminer.
- **Continu** : écoute permanente, chaque phrase est transcrite dès qu'on fait une pause.
- **Maintenu** : on parle tant que le bouton ou la touche est enfoncé.

La machine à états est dans [core/dicto-core/src/session.rs](core/dicto-core/src/session.rs). Toutes les plateformes lui envoient les mêmes déclencheurs (`Pressed`, `Released`, `Cancel`).
