<script lang="ts">
  import type { Output, PillPosition, Settings } from "../lib/api";
  import ShortcutField from "../lib/ShortcutField.svelte";
  import { app } from "../lib/store.svelte";
  import { t } from "../lib/i18n.svelte";

  const OUTPUTS: { id: Output; title: string; hint: string }[] = [
    { id: "type", title: "Taper dans la fenêtre", hint: "Comme un clavier" },
    { id: "paste", title: "Presse-papiers", hint: "Collé, puis le presse-papiers est remis" },
    { id: "review", title: "Relire d'abord", hint: "Le texte reste dans Dicto" },
  ];

  const SHORTCUTS: { key: keyof Settings & `shortcut${string}`; title: string; hint: string }[] = [
    { key: "shortcutToggle", title: "Marche/arrêt", hint: "Un appui pour commencer, un second pour terminer" },
    { key: "shortcutContinuous", title: "Continu", hint: "Allume ou coupe l'écoute permanente, une phrase à chaque pause" },
    { key: "shortcutHold", title: "Maintenu", hint: "Tu parles tant que les touches sont enfoncées" },
  ];

  const isLinux = navigator.userAgent.includes("Linux");
  const isMac = navigator.platform.startsWith("Mac");
</script>

{#if app.settings}
  {@const s = app.settings}
  <div class="screen">
    <div class="main">
      <div>
        <h1>{t("Raccourcis")}</h1>
        <p class="lead">{t("Fonctionnent partout, même quand Dicto est réduit. Échap annule une dictée en cours.")}</p>
      </div>

      <section class="card" aria-labelledby="modes">
        <h2 id="modes" class="pad">{t("Un raccourci par mode")}</h2>
        {#each SHORTCUTS as sc (sc.key)}
          <div class="row">
            <div class="label">
              <div class="title">{t(sc.title)}</div>
              <div class="hint">{t(sc.hint)}</div>
            </div>
            <ShortcutField value={s[sc.key]} label={t(sc.title)} onchange={(v) => app.update({ [sc.key]: v })} />
          </div>
        {/each}
      </section>

      <section class="card out" aria-labelledby="sortie">
        <h2 id="sortie">{t("Où va le texte")}</h2>
        <div role="radiogroup" aria-label={t("Sortie du texte")} class="choices">
          {#each OUTPUTS as o (o.id)}
            <button role="radio" aria-checked={s.output === o.id} class:on={s.output === o.id} onclick={() => app.update({ output: o.id })}>
              <span class="title">{t(o.title)}</span>
              <span class="hint">{t(o.hint)}</span>
            </button>
          {/each}
        </div>
        {#if isLinux}
          <p class="note">
            {t("Sous Linux, les raccourcis globaux et la frappe fonctionnent avec X11. Sous Wayland, ils ne marchent que dans certaines apps pour l'instant ; « Presse-papiers » est le plus sûr.")}
          </p>
        {:else if isMac}
          <p class="note">
            {t("Sous macOS, autorise Dicto dans Réglages Système › Confidentialité et sécurité › Accessibilité pour qu'il puisse écrire dans les autres apps.")}
          </p>
        {/if}
      </section>
    </div>

    <aside aria-labelledby="pastille">
      <h2 id="pastille">{t("Pastille d'enregistrement")}</h2>
      <div class="preview" aria-hidden="true">
        <div class="window"></div>
        <div class="mini-pill">
          <span class="mic"
            ><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#FFFCF8" stroke-width="2.6" stroke-linecap="round"
              ><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0" /></svg
            ></span
          >
          <span class="bars"><span style:height="8px"></span><span style:height="16px"></span><span style:height="12px"></span><span style:height="18px"></span><span style:height="6px"></span></span>
          <span class="time">00:03</span>
          <kbd>{t("Échap")}</kbd>
        </div>
      </div>
      <fieldset>
        <legend>{t("Position")}</legend>
        {#each [{ id: "bottom", label: "En bas, au centre de l'écran" }, { id: "hidden", label: "Seulement l'icône de la zone de notification" }] as p (p.id)}
          <label class="radio">
            <input
              type="radio"
              name="pill"
              checked={s.pill === p.id}
              onchange={() => app.update({ pill: p.id as PillPosition })}
            />{t(p.label)}
          </label>
        {/each}
      </fieldset>
    </aside>
  </div>
{/if}

<style>
  .screen {
    display: flex;
    flex-wrap: wrap;
    gap: 28px;
    align-content: flex-start;
  }

  .main {
    flex: 999 1 460px;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .pad {
    padding: 18px 22px 6px;
  }

  .out {
    padding: 18px 22px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .choices {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 10px;
  }

  .choices button {
    text-align: left;
    padding: 14px;
    border-radius: 16px;
    border: 1px solid var(--border);
    background: var(--bg);
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .choices button.on {
    border: 2px solid var(--accent);
    background: var(--accent-soft);
  }

  .title {
    font-size: 14px;
    font-weight: 700;
  }

  .hint {
    font-size: 12px;
    color: var(--muted);
  }

  .note {
    margin: 0;
    padding: 12px 14px;
    border-radius: 14px;
    background: var(--bg);
    font-size: 13px;
    line-height: 1.5;
    color: var(--ink-2);
  }

  aside {
    flex: 1 1 300px;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 14px;
    padding-top: 70px;
  }

  .preview {
    position: relative;
    height: 200px;
    border-radius: 24px;
    background: var(--border);
    overflow: hidden;
  }

  .window {
    position: absolute;
    inset: 18px 18px auto;
    height: 104px;
    border-radius: 14px;
    background: var(--bg);
  }

  .mini-pill {
    position: absolute;
    left: 50%;
    bottom: 20px;
    transform: translateX(-50%);
    display: flex;
    align-items: center;
    gap: 10px;
    height: 46px;
    padding: 0 14px 0 8px;
    border-radius: 23px;
    background: var(--overlay);
    box-shadow: var(--shadow-pop);
  }

  .mic {
    width: 30px;
    height: 30px;
    border-radius: 15px;
    background: var(--rec);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .bars {
    display: flex;
    align-items: center;
    gap: 3px;
  }

  .bars span {
    width: 3px;
    border-radius: 2px;
    background: var(--wave);
  }

  .time {
    font-size: 13px;
    font-weight: 700;
    color: var(--overlay-ink);
  }

  .mini-pill kbd {
    font-size: 11px;
    padding: 2px 6px;
    background: var(--overlay-line);
    color: var(--overlay-muted);
    box-shadow: none;
  }

  fieldset {
    margin: 0;
    padding: 0;
    border: none;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  legend {
    font-size: 14px;
    font-weight: 700;
    margin-bottom: 8px;
  }

  .radio {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 14px;
    min-height: 36px;
  }

  .radio input {
    width: 18px;
    height: 18px;
    accent-color: var(--accent);
  }
</style>
