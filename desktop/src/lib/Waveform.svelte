<script lang="ts">
  // Onde du niveau sonore : les derniers niveaux, une barre toutes les 80 ms.
  import { onDestroy, untrack } from "svelte";
  import { app } from "./store.svelte";

  let { bars = 24, height = 30, idle = "var(--overlay-line)" }: { bars?: number; height?: number; idle?: string } =
    $props();

  // Le nombre de barres est fixé à la création.
  let levels = $state<number[]>(Array(untrack(() => bars)).fill(0));
  const timer = setInterval(() => {
    levels = [...levels.slice(1), app.snapshot.active ? app.level : 0];
  }, 80);
  onDestroy(() => clearInterval(timer));
</script>

<div class="wave" style:height="{height}px" aria-hidden="true">
  {#each levels as l, i (i)}
    <span style:height="{4 + l * (height - 4)}px" style:background={l > 0.05 ? "var(--wave)" : idle}></span>
  {/each}
</div>

<style>
  .wave {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  span {
    width: 4px;
    border-radius: 2px;
    transition: height 0.08s linear;
  }
</style>
