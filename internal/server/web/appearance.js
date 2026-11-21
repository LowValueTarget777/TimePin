import { $, el, field, input, modal, select } from "./ui.js";

const themes = [["frost", "雾光 · 毛玻璃"], ["light", "浅色"], ["dark", "深色"], ["system", "跟随系统"]];
const modes = [["solid", "纯色"], ["frost", "雾光渐变"], ["photo", "自选图片"]];
const presets = [["冰蓝", "#DCE7F4"], ["薄荷", "#B8D8D0"], ["丁香", "#D8C9EB"],
  ["杏色", "#F2D8C4"], ["深海", "#294658"], ["墨黑", "#192333"]];
const initial = { theme: "frost", mode: "solid", color: presets[0][1], photo: "" };
const hex = /^#[0-9a-f]{6}$/i;
const key = (id) => "timepin.appearance.v1." + id;
const has = (choices, value) => choices.some(([id]) => id === value);
let activeUser, current = { ...initial };

function read(id) {
  try {
    const saved = JSON.parse(localStorage.getItem(key(id)));
    if (saved && has(themes, saved.theme) && has(modes, saved.mode) && hex.test(saved.color)) {
      const photo = typeof saved.photo === "string" && saved.photo.length <= 1500000 &&
        /^data:image\/jpeg;base64,[A-Za-z0-9+/]+=*$/.test(saved.photo) ? saved.photo : "";
      return { theme: saved.theme, mode: saved.mode === "photo" && !photo ? "solid" : saved.mode,
        color: saved.color.toUpperCase(), photo };
    }
  } catch { /* Start with the default if browser storage is unavailable. */ }
  return { ...initial };
}

function paint(node, value) {
  node.dataset.theme = value.theme;
  node.dataset.backdrop = value.mode;
  node.style.setProperty("--back-color", value.color);
  if (value.theme === "frost" && value.mode === "photo" && value.photo)
    node.style.setProperty("--back-photo", 'url("' + value.photo + '")');
  else node.style.removeProperty("--back-photo");
}

function apply(value) {
  current = value;
  paint(document.documentElement, value);
  $("dark-palette").media = value.theme === "system" ? "(prefers-color-scheme: dark)" :
    value.theme === "dark" ? "all" : "not all";
  const dark = value.theme === "dark" || value.theme === "system" && matchMedia("(prefers-color-scheme: dark)").matches;
  document.querySelector('meta[name="theme-color"]').content = value.theme === "frost" ?
    (value.mode === "solid" ? value.color : initial.color) : dark ? "#121615" : "#fafbf7";
  window.dispatchEvent(new Event("timepin-appearance"));
}

export function useAppearance(id) {
  if (activeUser === id) return;
  activeUser = id;
  apply(read(id));
}
export function getAppearance() {
  return { theme: current.theme, mode: current.mode, color: current.color, hasPhoto: !!current.photo };
}
export function updateAppearance({ theme = current.theme, mode = current.mode, color = current.color } = {}) {
  return saveAppearance({ theme, mode, color, photo: current.photo });
}
function saveAppearance(value) {
  if (!activeUser) throw Error("请先登录日历账号");
  if (!has(themes, value.theme) || !has(modes, value.mode) || !hex.test(value.color)) throw Error("主题、背景或颜色无效");
  if (value.mode === "photo" && !value.photo) throw Error("请先选择背景图片");
  const saved = { ...value, color: value.color.toUpperCase() };
  try { localStorage.setItem(key(activeUser), JSON.stringify(saved)); }
  catch { throw Error("浏览器无法保存外观，请检查存储空间和浏览器权限"); }
  apply(saved);
  return getAppearance();
}
window.addEventListener("storage", (e) => {
  if (activeUser && (e.key === null || e.key === key(activeUser))) apply(read(activeUser));
});
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
  if (current.theme === "system") apply(current);
});
export function appearanceName() {
  const name = themes.find(([id]) => id === current.theme)[1];
  if (current.theme !== "frost") return name;
  const colorName = presets.find(([, color]) => color === current.color)?.[0] || current.color;
  return name + " · " + (current.mode === "solid" ? colorName : modes.find(([id]) => id === current.mode)[1]);
}

async function localPhoto(file) {
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type) || file.size > 16 * 1024 * 1024)
    throw Error("请选择 16 MiB 以内的 JPG、PNG 或 WebP 图片");
  const bitmap = await createImageBitmap(file);
  try {
    const ratio = Math.min(1, 1280 / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * ratio));
    canvas.height = Math.max(1, Math.round(bitmap.height * ratio));
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = initial.color;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const data = canvas.toDataURL("image/jpeg", 0.78);
    if (data.length > 1500000) throw Error("图片过大，请选择较小的背景图");
    return data;
  } finally { bitmap.close(); }
}

export function editAppearance(onSave) {
  const id = activeUser, draft = { ...current };
  const body = modal("主题与背景"), editor = el("form", undefined, "appearance-editor");
  const preview = el("div", undefined, "theme-preview");
  preview.setAttribute("aria-label", "主题预览");
  const pane = el("div", undefined, "preview-pane");
  const now = new Date();
  pane.append(el("strong", new Intl.DateTimeFormat("zh-CN", {month:"long", day:"numeric"}).format(now)),
    el("span", new Intl.DateTimeFormat("zh-CN", {weekday:"long"}).format(now)));
  preview.append(pane);
  const theme = select(themes, draft.theme), mode = select(modes, draft.mode);
  const backdrop = field("毛玻璃背景", mode);
  const colors = el("div", undefined, "theme-colors");
  const swatches = el("div", undefined, "color-options"), code = input("text", draft.color);
  code.maxLength = 7; code.spellcheck = false; code.autocomplete = "off";
  const native = input("color", draft.color);
  const chips = presets.map(([name, color]) => {
    const b = el("button", undefined, "color-swatch");
    b.type = "button"; b.title = name; b.setAttribute("aria-label", name);
    b.style.setProperty("--swatch", color);
    b.style.setProperty("--on-swatch", ["深海", "墨黑"].includes(name) ? "white" : "#1d3549");
    b.onclick = () => setColor(color);
    swatches.append(b);
    return b;
  });
  const sliders = ["红色", "绿色", "蓝色"].map((name, i) => {
    const slider = input("range"); slider.min = "0"; slider.max = "255";
    slider.oninput = () => setColor("#" + sliders.map(s => Number(s.value).toString(16).padStart(2, "0")).join(""));
    const label = field(name, slider); label.className = "channel channel-" + i;
    colors.append(label);
    return slider;
  });
  colors.prepend(swatches);
  const precise = el("div", undefined, "color-value");
  precise.append(field("取色", native), field("十六进制颜色", code));
  colors.append(precise);
  const upload = input("file"); upload.accept = "image/jpeg,image/png,image/webp";
  const photo = field("从设备选择图片", upload);
  const error = el("p", undefined, "error"); error.setAttribute("role", "alert");
  const save = el("button", "保存外观", "primary");
  const cancel = el("button", "取消"); cancel.type = "button";
  cancel.onclick = () => $("dialog").close();
  const footer = el("div", undefined, "appearance-actions"); footer.append(cancel, save);
  let reading = false, sequence = 0;
  function render() {
    const frost = draft.theme === "frost";
    backdrop.hidden = !frost;
    colors.hidden = !frost || draft.mode !== "solid";
    photo.hidden = !frost || draft.mode !== "photo";
    save.disabled = (!photo.hidden && (reading || !draft.photo)) || (!colors.hidden && !hex.test(code.value));
    error.hidden = !error.textContent;
    paint(preview, draft);
  }
  function setColor(value, typing = false) {
    if (hex.test(value)) {
      draft.color = value.toUpperCase();
      native.value = value;
      if (!typing) code.value = draft.color;
      sliders.forEach((s, i) => s.value = parseInt(value.slice(1 + 2 * i, 3 + 2 * i), 16));
    }
    code.setAttribute("aria-invalid", String(!hex.test(code.value)));
    chips.forEach((b, i) => b.setAttribute("aria-pressed", String(hex.test(code.value) && draft.color === presets[i][1])));
    render();
  }
  code.oninput = () => setColor(code.value, true);
  native.oninput = () => setColor(native.value);
  theme.onchange = () => { draft.theme = theme.value; render(); };
  mode.onchange = () => { draft.mode = mode.value; render(); };
  upload.onchange = async () => {
    const file = upload.files[0];
    if (!file) return;
    const attempt = ++sequence;
    reading = true; error.textContent = ""; render();
    try {
      const photo = await localPhoto(file);
      if (attempt === sequence) draft.photo = photo;
    } catch (e) {
      if (attempt === sequence) error.textContent = e.message || "无法读取图片";
    } finally {
      if (attempt === sequence) { reading = false; render(); }
    }
  };
  editor.onsubmit = (e) => {
    e.preventDefault();
    if (save.disabled || id !== activeUser) return;
    const mode = draft.mode === "photo" && !draft.photo && draft.theme !== "frost" ? "solid" : draft.mode;
    try { saveAppearance({ ...draft, mode }); }
    catch (e) { error.textContent = e.message; render(); return; }
    $("dialog").close(); onSave();
  };
  editor.append(preview, field("主题样式", theme), backdrop, colors, photo, error, footer);
  body.append(editor);
  setColor(draft.color);
}
