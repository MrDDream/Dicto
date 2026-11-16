// Site de Dicto : la langue (anglais par défaut, français si on le choisit), le thème (automatique selon
// l'appareil, clair ou sombre), la démo des trois modes, la galerie et les liens de la dernière version.
(() => {
  const REPO = "MrDDream/Dicto";
  const root = document.documentElement;

  // --- Langue ------------------------------------------------------------------------------
  function stored(key) {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }
  function remember(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch {}
  }
  function setLang(lang, save) {
    root.dataset.lang = lang;
    root.lang = lang;
    document.title = lang === "fr" ? "Dicto · la dictée, chez toi" : "Dicto · dictation that stays with you";
    document.querySelectorAll(".lang button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.set === lang)));
    if (save) remember("dicto-lang", lang);
    renderMode();
  }
  document.querySelectorAll(".lang button").forEach((b) => b.addEventListener("click", () => setLang(b.dataset.set, true)));

  // --- Démo des modes ----------------------------------------------------------------------
  const MODES = {
    toggle: { en: ["On / off", "Press to start, press again to finish."], fr: ["Marche/arrêt", "Appuie pour commencer, appuie encore pour terminer."] },
    continuous: { en: ["Continuous", "Each sentence is written as soon as you pause."], fr: ["Continu", "Chaque phrase est transcrite dès que tu marques une pause."] },
    hold: { en: ["Hold", "Keep the button down while you speak."], fr: ["Maintenu", "Garde le bouton enfoncé pendant que tu parles."] },
  };
  let mode = "continuous";
  function renderMode() {
    const lang = root.dataset.lang === "fr" ? "fr" : "en";
    document.querySelectorAll(".modes button").forEach((b) => {
      b.textContent = MODES[b.dataset.mode][lang][0];
      b.setAttribute("aria-pressed", String(b.dataset.mode === mode));
    });
    const name = document.getElementById("mode-name");
    const hint = document.getElementById("mode-hint");
    if (name) name.textContent = MODES[mode][lang][0];
    if (hint) hint.textContent = MODES[mode][lang][1];
  }
  document.querySelectorAll(".modes button").forEach((b) =>
    b.addEventListener("click", () => {
      mode = b.dataset.mode;
      renderMode();
    }),
  );

  // Ondes de la démo.
  const bars = document.querySelector(".bars");
  if (bars) {
    [10, 18, 30, 22, 38, 26, 14, 32, 44, 28, 20, 36, 24, 12, 30, 40, 18, 26, 34, 16, 28, 42, 22, 14, 32, 20, 38, 24].forEach((h, i) => {
      const s = document.createElement("span");
      s.style.height = `${h}px`;
      s.style.animationDelay = `${(i % 7) * -0.15}s`;
      bars.appendChild(s);
    });
  }

  // --- Galerie : téléphone ou ordinateur ------------------------------------------------------
  const tabs = document.querySelectorAll(".tabs [role=tab]");
  tabs.forEach((t) =>
    t.addEventListener("click", () => {
      tabs.forEach((o) => {
        const on = o === t;
        o.setAttribute("aria-selected", String(on));
        o.tabIndex = on ? 0 : -1;
        document.getElementById(o.getAttribute("aria-controls")).hidden = !on;
      });
    }),
  );

  // --- Dernière version ---------------------------------------------------------------------
  // Les noms des fichiers sont ceux de .github/workflows/release.yml ; sans réponse de GitHub, les liens
  // mènent à la page de la dernière version.
  fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { Accept: "application/vnd.github+json" } })
    .then((r) => (r.ok ? r.json() : null))
    .then((release) => {
      if (!release || !release.tag_name) return;
      const tag = release.tag_name;
      const v = tag.replace(/^v/, "");
      document.querySelectorAll("[data-version]").forEach((el) => (el.textContent = tag));
      const names = {
        apk: `Dicto-${tag}-arm64-v8a.apk`,
        "apk-universal": `Dicto-${tag}.apk`,
        exe: `Dicto-${v}-setup.exe`,
        deb: `dicto_${v}_amd64.deb`,
        rpm: `dicto-${v}.x86_64.rpm`,
        appimage: `Dicto-${v}-x86_64.AppImage`,
        dmg: `Dicto-${v}-macos-arm64.dmg`,
      };
      const assets = new Map((release.assets || []).map((a) => [a.name, a.browser_download_url]));
      document.querySelectorAll("[data-asset]").forEach((a) => {
        const url = assets.get(names[a.dataset.asset]);
        if (url) a.href = url;
      });
    })
    .catch(() => {});

  // --- Thème ----------------------------------------------------------------------------------
  function setTheme(theme, save) {
    root.dataset.theme = theme;
    document.querySelectorAll(".theme button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.theme === theme)));
    if (save) remember("dicto-theme", theme);
  }
  document.querySelectorAll(".theme button").forEach((b) => b.addEventListener("click", () => setTheme(b.dataset.theme, true)));

  setTheme(["auto", "light", "dark"].includes(stored("dicto-theme")) ? stored("dicto-theme") : "auto", false);
  setLang(stored("dicto-lang") === "fr" ? "fr" : "en", false);
})();
