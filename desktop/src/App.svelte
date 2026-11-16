<script lang="ts">
  import { onMount } from "svelte";
  import Icon, { type IconName } from "./lib/Icon.svelte";
  import Logo from "./lib/Logo.svelte";
  import { app } from "./lib/store.svelte";
  import Dictee from "./screens/Dictee.svelte";
  import Historique from "./screens/Historique.svelte";
  import Modeles from "./screens/Modeles.svelte";
  import Raccourcis from "./screens/Raccourcis.svelte";
  import Reglages from "./screens/Reglages.svelte";

  type Screen = "dictee" | "historique" | "modeles" | "raccourcis" | "reglages";
  const NAV: { id: Screen; label: string; icon: IconName }[] = [
    { id: "dictee", label: "Dictée", icon: "mic" },
    { id: "historique", label: "Historique", icon: "history" },
    { id: "modeles", label: "Modèles", icon: "models" },
    { id: "raccourcis", label: "Raccourcis", icon: "keyboard" },
    { id: "reglages", label: "Réglages", icon: "settings" },
  ];

  let screen = $state<Screen>("dictee");
  let ready = $state(false);

  onMount(async () => {
    await app.init();
    ready = true;
    // Premier lancement sans modèle : on commence par en choisir un.
    if (app.snapshot.engine.kind === "none" && !app.settings?.model) screen = "modeles";
  });

  const engine = $derived(app.snapshot.engine);
  const statusColor = $derived(
    engine.kind === "ready" ? "var(--success)" : engine.kind === "error" ? "var(--rec)" : "var(--disabled)",
  );
  const statusText = $derived(
    engine.kind === "ready"
      ? "Prêt, en local"
      : engine.kind === "loading"
        ? "Chargement du modèle…"
        : engine.kind === "error"
          ? "Modèle indisponible"
          : "Aucun modèle",
  );
</script>

<div class="shell">
  <nav aria-label="Navigation">
    <div class="brand">
      <Logo />
      <span>Dicto</span>
    </div>
    {#each NAV as item (item.id)}
      <button
        class="nav-item"
        class:current={screen === item.id}
        aria-current={screen === item.id ? "page" : undefined}
        onclick={() => (screen = item.id)}
      >
        <Icon name={item.icon} />{item.label}
      </button>
    {/each}
    <button class="engine" onclick={() => (screen = "modeles")} title={engine.detail ?? ""}>
      <span class="engine-state" style:color={engine.kind === "ready" ? "var(--success-ink)" : "var(--ink-2)"}>
        <span class="dot" style:background={statusColor}></span>{statusText}
      </span>
      <span class="engine-name">{engine.kind === "none" ? "Choisis un modèle" : engine.label}</span>
    </button>
  </nav>

  <main>
    {#if ready}
      {#if screen === "dictee"}
        <Dictee />
      {:else if screen === "historique"}
        <Historique />
      {:else if screen === "modeles"}
        <Modeles />
      {:else if screen === "raccourcis"}
        <Raccourcis />
      {:else}
        <Reglages />
      {/if}
    {/if}
  </main>

  {#if app.message}
    <div class="toast" role="status">
      <span>{app.message}</span>
      <button aria-label="Fermer le message" onclick={() => (app.message = null)}><Icon name="close" size={16} /></button>
    </div>
  {/if}
</div>

<style>
  .shell {
    display: flex;
    height: 100%;
    min-height: 0;
  }

  nav {
    flex: 0 0 236px;
    padding: 24px 14px;
    background: var(--sunken);
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 10px 22px;
    font-family: var(--display);
    font-size: 21px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  .nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 42px;
    padding: 0 12px;
    border: none;
    border-radius: 14px;
    background: transparent;
    color: var(--ink-nav);
    font-size: 14px;
    font-weight: 600;
    text-align: left;
  }

  .nav-item:hover {
    background: rgba(255, 252, 248, 0.5);
  }

  .nav-item.current {
    background: var(--surface);
    box-shadow: 0 1px 2px rgba(43, 35, 29, 0.08);
    color: var(--accent);
    font-weight: 700;
  }

  .engine {
    margin-top: auto;
    padding: 14px;
    border: none;
    border-radius: 16px;
    background: var(--surface);
    display: flex;
    flex-direction: column;
    gap: 4px;
    text-align: left;
  }

  .engine-state {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 700;
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 4px;
  }

  .engine-name {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  main {
    flex: 1;
    min-width: 0;
    overflow-y: auto;
    padding: 32px 40px;
  }

  .toast {
    position: fixed;
    left: 50%;
    bottom: 24px;
    transform: translateX(-50%);
    max-width: min(560px, calc(100% - 32px));
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 12px 12px 18px;
    border-radius: 18px;
    background: var(--overlay);
    color: var(--overlay-ink);
    font-size: 14px;
    line-height: 1.4;
    white-space: pre-line;
    box-shadow: 0 10px 24px rgba(43, 35, 29, 0.3);
  }

  .toast button {
    border: none;
    background: transparent;
    color: var(--overlay-muted);
    display: flex;
    padding: 6px;
    border-radius: 10px;
  }
</style>
