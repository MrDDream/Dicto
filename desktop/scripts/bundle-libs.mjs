// Prépare les bibliothèques natives (sherpa-onnx, onnxruntime) pour les paquets :
// les copie de target/release vers src-tauri/libs et écrit la configuration Tauri qui les
// embarque (src-tauri/libs.conf.json, passée à `tauri build --config`).
// À lancer après un premier `cargo build --release`.
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const tauriDir = resolve(import.meta.dirname, "../src-tauri");
const release = resolve(tauriDir, "target/release");
const libs = resolve(tauriDir, "libs");

const pattern = {
  win32: /\.dll$/i,
  linux: /\.so(\.\d+)*$/,
  darwin: /\.dylib$/,
}[process.platform];
if (!pattern) throw new Error(`Plateforme non prise en charge : ${process.platform}`);

rmSync(libs, { recursive: true, force: true });
mkdirSync(libs);
const files = existsSync(release) ? readdirSync(release).filter((f) => pattern.test(f)) : [];
for (const f of files) copyFileSync(resolve(release, f), resolve(libs, f));
console.log(`Bibliothèques embarquées : ${files.join(", ") || "aucune"}`);

const bundle =
  process.platform === "darwin"
    ? { macOS: { frameworks: files.map((f) => `libs/${f}`) } }
    : // Windows : à côté de l'exécutable ; Linux : /usr/lib/Dicto (voir build.rs).
      { resources: Object.fromEntries(files.map((f) => [`libs/${f}`, f])) };

writeFileSync(resolve(tauriDir, "libs.conf.json"), JSON.stringify({ bundle }, null, 2));
