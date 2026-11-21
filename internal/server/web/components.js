import { action, el } from "./ui.js";

const paths = {
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zm0-11v6m0-10v1",
  upload: "M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5",
  sun: "M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  back: "m14 5-7 7 7 7",
  plus: "M12 5v14M5 12h14",
  overview:"M3 3h7v8H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 15h7v6H3z",
  share:"M8 12l7-5m-7 6 7 4M8 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0zm13-7a3 3 0 1 1-6 0 3 3 0 0 1 6 0zm0 14a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
  history:"M3 12a9 9 0 1 0 3-6l-3 3m0-5v5h5m4-2v5l4 2",
  exit:"M10 4H4v16h6m-1-8h12m-4-4 4 4-4 4",
  calendar: "M4 8h16M8 3v4m8-4v4M4 5h16v15H4zM8 12h3m2 0h3m-8 4h3",
  people:
    "M16 20v-2a5 5 0 0 0-10 0v2m13-2a6 6 0 0 0-2-4M14 8a3 3 0 1 1-6 0 3 3 0 0 1 6 0zm2-2a3 3 0 0 1 0 5",
  model: "m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z",
  sync: "M4 11a8 8 0 0 1 14-5l2 3m0-5v5h-5M20 13a8 8 0 0 1-14 5l-2-3m0 5v-5h5",
  key: "M14 14a5 5 0 1 0-4-4L3 17v4h4v-3h3l4-4zm2-7h.1",
  account: "M16 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0zM4 21a8 8 0 0 1 16 0",
  server: "M3 4h18v7H3zm0 10h18v7H3zM7 7h1m-1 10h1",
  settings: "M4 7h7m5 0h4M4 17h3m5 0h8M14 4v6m-5 4v6",
};
export function icon(name) {
  const ns = "http://www.w3.org/2000/svg",
    svg = document.createElementNS(ns, "svg"),
    p = document.createElementNS(ns, "path");
  for (const [key, value] of Object.entries({
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "1.65",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
  }))
    svg.setAttribute(key, value);
  p.setAttribute("d", paths[name]);
  svg.append(p);
  return svg;
}
export function card(name, title, summary, fn) {
  const b = action("", fn, "setting-card"),
    tile = el("span", undefined, "setting-symbol " + name),
    copy = el("span", undefined, "setting-copy");
  tile.append(icon(name));
  copy.append(el("strong", title), el("small", summary));
  b.append(tile, copy, el("span", "›", "card-arrow"));
  return b;
}
