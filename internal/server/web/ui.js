export const $ = (id) => document.getElementById(id);
export function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
export function action(label, fn, cls) {
  const b = el("button", label, cls);
  b.type = "button";
  b.onclick = () => run(fn, b);
  return b;
}
let timeout;
export function message(text) {
  const n = $("notice");
  n.textContent = text;
  n.hidden = false;
  clearTimeout(timeout);
  timeout = setTimeout(() => (n.hidden = true), 4500);
}
export async function run(fn, button) {
  if (button) button.disabled = true;
  const old = $("dialog").querySelector(".error");
  if (old) old.remove();
  try {
    return await fn();
  } catch (e) {
    if ($("dialog").open) {
      const n = el("p", e.message, "error");
      n.setAttribute("role", "alert");
      $("dialog-content").prepend(n);
    } else message(e.message);
  } finally {
    if (button) button.disabled = false;
  }
}
export async function api(path, data, { signal, readStream } = {}) {
  const options =
    data === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
        };
  if (readStream) options.headers = { ...options.headers, Accept: "text/event-stream" };
  const r = await fetch("/api/v1/" + path, { ...options, signal, cache: "no-store" });
  if (r.ok && readStream && r.headers.get("content-type")?.startsWith("text/event-stream"))
    return readStream(r);
  let result;
  try {
    result = await r.json();
  } catch {
    throw Error("服务返回了无法读取的响应");
  }
  if (r.status === 401 && path.startsWith("admin/") && path !== "admin/login") location.replace("/admin");
  if (r.status === 401 && path !== "password" && document.getElementById("workspace") && !document.body.classList.contains("public"))
    location.replace("/login.html" + location.search + location.hash);
  if (!r.ok) throw Object.assign(Error(result.error || "请求失败 " + r.status), { status: r.status });
  return result;
}
export async function command(payload) {
  const r = await api("commands", { v: 1, ...payload });
  if (r.code !== "ok")
    throw Error(
      {
        conflict: "事件已经改变，请重新打开后核对。",
        forbidden: "当前角色没有编辑权限。",
        operation_reused: "该操作编号已使用。",
      }[r.code] ||
        r.detail ||
        r.code,
    );
  return r;
}
export function uid() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) =>
    n.toString(16).padStart(2, "0"),
  ).join("");
}
const pending = new Map();
export const manage = (op, args) => manageAt("manage", op, args);
export async function manageAt(path, op, args = {}) {
  if (op.endsWith(".detail")) return api(path, { op, ...args });
  const key = JSON.stringify([path, op, args]);
  if (!pending.has(key)) pending.set(key, uid());
  const result = await api(path, {
    op,
    operationId: pending.get(key),
    ...args,
  });
  pending.delete(key);
  return result;
}
export const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
export function day(d) {
  return (
    d.getFullYear() +
    "-" +
    String(d.getMonth() + 1).padStart(2, "0") +
    "-" +
    String(d.getDate()).padStart(2, "0")
  );
}
export function local(s) {
  return new Date(s + "T12:00:00");
}
export function plus(s, n) {
  const d = local(s);
  d.setDate(d.getDate() + n);
  return day(d);
}
export function monthStart(s) {
  return s.slice(0, 7) + "-01";
}
export function monthName(s) {
  const d = local(s);
  return d.getFullYear() + "年 " + (d.getMonth() + 1) + "月";
}
export function monthGrid(s, sundayFirst = false) {
  const first = monthStart(s);
  const offset = (local(first).getDay() + (sundayFirst ? 0 : 6)) % 7;
  const start = plus(first, -offset);
  const lastDay = new Date(
    local(first).getFullYear(),
    local(first).getMonth() + 1,
    0,
  ).getDate();
  const length = sundayFirst ? Math.ceil((offset + lastDay) / 7) * 7 : 42;
  return Array.from({ length }, (_, i) => plus(start, i));
}
export function select(items, selected) {
  const n = el("select");
  for (const [value, label] of items) {
    const o = el("option", label);
    o.value = value;
    n.append(o);
  }
  if (selected !== undefined) n.value = selected;
  return n;
}
export function input(type, value = "") {
  const n = el("input");
  n.type = type;
  n.value = value;
  return n;
}
export function field(title, control) {
  const l = el("label", title);
  control.setAttribute("aria-label", title);
  l.append(control);
  return l;
}
export function row(...nodes) {
  const n = el("div", undefined, "row");
  n.append(...nodes);
  return n;
}
export function pair(...nodes) {
  const n = el("div", undefined, "pair");
  n.append(...nodes);
  return n;
}
export function block(title, ...nodes) {
  const n = el("section", undefined, "block");
  n.append(el("h3", title), ...nodes);
  return n;
}
export function reveal(node) {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  node.animate([{ opacity: 0.6, transform: "translateY(3px)" }, { opacity: 1, transform: "none" }],
    { duration: 155, easing: "ease-out" });
}
export function modal(title, drawer = false) {
  const replacing = $("dialog").open && $("dialog-title").textContent !== title;
  $("dialog").classList.toggle("assistant-drawer", drawer);
  $("dialog-title").textContent = title;
  const body = $("dialog-content");
  body.replaceChildren();
  if (!$("dialog").open) $("dialog").showModal();
  else if (replacing) reveal(body);
  return body;
}
export function form(buttonText, fn, ...nodes) {
  const f = el("form");
  const b = el("button", buttonText, "primary");
  b.type = "submit";
  f.append(...nodes, b);
  f.onsubmit = (e) => {
    e.preventDefault();
    run(fn, b);
  };
  return f;
}
export const roles = {
  manager: "管理",
  editor: "全表编辑",
  contributor: "投稿",
  reader: "只读",
  admin: "团组管理员",
  member: "成员",
};
export function linkBox(parent, kind, token) {
  const url = new URL(location.href);
  url.hash = kind + "=" + token;
  const n = input("text", url.href);
  n.readOnly = true;
  n.setAttribute("aria-label", "分享地址");
  n.className = "token";
  parent.append(
    n,
    action("复制链接", async () => {
      n.select();
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(n.value);
      } else {
        document.execCommand("copy");
      }
      message("链接已复制");
    }),
  );
}
export function tint(n, color) {
  if (/^#[0-9a-f]{6}$/i.test(color)) {
    n.style.setProperty("--swatch", color);
    const [r, g, b] = color
      .slice(1)
      .match(/../g)
      .map((hex) => {
        const c = parseInt(hex, 16) / 255;
        return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
    n.style.setProperty(
      "--on-swatch",
      0.2126 * r + 0.7152 * g + 0.0722 * b > 0.179 ? "#000" : "#fff",
    );
  }
  return n;
}
