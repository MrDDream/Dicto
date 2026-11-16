fn main() {
    // Bibliothèques de sherpa-onnx livrées avec l'app : où le programme installé les trouve.
    // - Linux (.deb, .rpm, AppImage) : /usr/lib/Dicto, à côté de /usr/bin ;
    // - macOS : Contents/Frameworks du paquet .app ;
    // - Windows : à côté de l'exécutable, cherché par défaut.
    match std::env::var("CARGO_CFG_TARGET_OS").as_deref() {
        Ok("linux") => println!("cargo:rustc-link-arg-bins=-Wl,-rpath,$ORIGIN/../lib/Dicto"),
        Ok("macos") => println!("cargo:rustc-link-arg-bins=-Wl,-rpath,@executable_path/../Frameworks"),
        _ => {}
    }
    tauri_build::build()
}
