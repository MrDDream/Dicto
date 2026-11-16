import { mount } from "svelte";
import "./app.css";
import Pill from "./Pill.svelte";

// Fenêtre transparente : seul le contour arrondi de la pastille est visible.
document.documentElement.style.background = "transparent";
document.body.style.background = "transparent";

export default mount(Pill, { target: document.getElementById("pill")! });
