// Langue de l'interface : le français est le texte source, l'anglais vient de i18n-en.ts.
// « auto » suit la langue du système (celle du navigateur intégré).
import EN from "./i18n-en";

export type UiLanguage = "auto" | "fr" | "en";
export type Lang = "fr" | "en";

function systemLang(): Lang {
  const langs = navigator.languages?.length ? navigator.languages : [navigator.language];
  return (langs[0] ?? "fr").toLowerCase().startsWith("fr") ? "fr" : "en";
}

class I18n {
  pref = $state<UiLanguage>("auto");
  system: Lang = systemLang();

  get lang(): Lang {
    return this.pref === "auto" ? this.system : this.pref;
  }

  /** Locale pour les dates et les nombres. */
  get locale(): string {
    return this.lang === "fr" ? "fr-FR" : "en-GB";
  }
}

export const i18n = new I18n();

/** « Texte français » dans la langue de l'interface ; %1, %2… reçoivent les arguments. */
export function t(fr: string, ...args: (string | number)[]): string {
  let s = i18n.lang === "en" ? (EN[fr] ?? fr) : fr;
  args.forEach((a, i) => (s = s.replaceAll(`%${i + 1}`, String(a))));
  return s;
}

// Les clés avec %1… servent aussi de modèles pour les textes venus du cœur Rust.
let patterns: { re: RegExp; en: string }[] | null = null;
function compiled() {
  if (patterns) return patterns;
  patterns = Object.entries(EN)
    .filter(([fr]) => /%\d/.test(fr))
    .map(([fr, en]) => {
      const source = fr
        .split(/(%\d)/)
        .map((part) => (/^%\d$/.test(part) ? "([\\s\\S]*?)" : part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")))
        .join("");
      return { re: new RegExp(`^${source}$`), en };
    });
  return patterns;
}

/** Texte venu du cœur (statut du moteur, messages, erreurs) : traduit s'il est connu, ligne par ligne. */
export function tr(text: string | null | undefined): string {
  if (!text) return "";
  if (i18n.lang === "fr") return text;
  return text
    .split("\n")
    .map((line) => {
      if (EN[line]) return EN[line];
      for (const { re, en } of compiled()) {
        const m = re.exec(line);
        if (m) return m.slice(1).reduce((s, v, i) => s.replaceAll(`%${i + 1}`, tr(v)), en);
      }
      return line;
    })
    .join("\n");
}
