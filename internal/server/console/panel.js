import {
  $,
  action,
  auditList,
  confirmChange,
  el,
  empty,
  field,
  form,
  icon,
  input,
  link,
  message,
  node,
  panel,
  read,
  select,
  tag,
  timeLabel,
  write,
} from "./console-ui.js";
import { api, run } from "./ui.js";
import { renderDirectory } from "./directory.js";

import { renderReleases } from "./releases.js";

const pages = {
  overview: ["实例总览", "overview", "查看成员、共享访问和最近的管理操作。"],
  accounts: ["成员账号", "people", "添加成员、修改登录名或密码，管理登录和共用 AI 授权。"],
  resources: ["日历与团组", "calendar", "维护日历归属、团组成员和直接授权。"],
  access: ["共享与访问", "share", "检查有效的只读分享、邀请和接口令牌。"],
  settings: [
    "实例设置",
    "settings",
    "配置注册方式、共用模型并查看数据库状态。",
  ],
  releases: ["版本管理", "upload", "发布安卓安装包，管理成员可下载的版本。"],
  history: ["操作记录", "history", "按时间查看最近 1,000 条成功的后台变更。"],
};
let generation = 0;
const nav = $("console-nav");
for (const [key, [title, symbol]] of Object.entries(pages)) {
  const a = link("", key);
  a.dataset.page = key;
  a.append(icon(symbol), el("span", title));
  nav.append(a);
}
$("exit").append(icon("exit"));
$("exit").onclick = () =>
  run(async () => {
    await api("admin/logout", {});
    location.replace("/admin");
  });
$("refresh").prepend(icon("sync"));
$("refresh").onclick = () => run(render, $("refresh"));
$("dialog-close").onclick = () => $("dialog").close();
window.addEventListener("hashchange", () => {
  if (!$("admin-page").hidden) {
    $("dialog").close();
    run(render);
  }
});
async function enter(user) {
  $("console-login").hidden = true;
  $("admin-page").hidden = false;
  $("operator-name").textContent = user.name;
  await render();
}
$("console-form").onsubmit = (e) => {
  e.preventDefault();
  const f = e.currentTarget;
  run(async () => {
    const user = await api("admin/login", {
      name: f.elements.name.value.trim(),
      password: f.elements.password.value,
    });
    f.reset();
    await enter(user);
  }, f.querySelector("button"));
};
run(async () => {
  const r = await fetch("/api/v1/admin/state", { cache: "no-store" });
  if (r.status === 401) {
    $("console-login").hidden = false;
    return;
  }
  if (!r.ok) throw Error("读取后台登录状态失败，请刷新重试");
  await enter(await r.json());
});
function metric(label, n, symbol, detail, route, color) {
  const a = link("", route, "metric " + color);
  a.append(
    node(
      "div",
      "metric-label",
      el("span", label),
      node("span", "symbol", icon(symbol)),
    ),
    el("strong", String(n)),
    el("small", detail),
  );
  return a;
}
function summaryRow(label, value, route) {
  const a = link("", route, "summary-row");
  a.append(
    el("span", label),
    node("span", "summary-value", value, el("span", "›")),
  );
  return a;
}
function overview(root, data) {
  const n = data.counts,
    total = n.shares + n.invites + n.tokens;
  root.append(
    node(
      "div",
      "metrics",
      metric(
        "成员账号",
        n.accounts,
        "people",
        n.accounts - n.disabled + " 个启用",
        "accounts",
        "mint",
      ),
      metric(
        "日历",
        n.calendars,
        "calendar",
        "个人与团组持有",
        "resources/calendars",
        "blue",
      ),
      metric(
        "团组",
        n.groups,
        "share",
        "管理成员与归属",
        "resources/groups",
        "amber",
      ),
      metric(
        "有效访问凭据",
        total,
        "key",
        "分享、邀请与令牌",
        "access/shares",
        "violet",
      ),
    ),
  );
  const access = el("div", undefined, "access-summary"),
    bar = el("div", undefined, "distribution");
  bar.setAttribute("aria-hidden", "true");
  for (const [type, label, amount] of [
    ["shares", "只读链接", n.shares],
    ["invites", "有效邀请", n.invites],
    ["tokens", "API 令牌", n.tokens],
  ]) {
    if (amount) {
      const part = el("span", undefined, type);
      part.style.flexGrow = String(amount);
      bar.append(part);
    }
    const a = link("", "access/" + type, "summary-row");
    a.append(
      node("span", "legend", el("i", undefined, "dot-" + type), label),
      node("span", "summary-value", String(amount), el("span", "›")),
    );
    access.append(a);
  }
  access.prepend(bar);
  const policy = node(
    "div",
    "policy",
    summaryRow(
      "新成员注册",
      tag(data.registration ? "开放注册" : "邀请注册", "green"),
      "settings",
    ),
    summaryRow(
      "实例共用模型",
      tag(
        data.model.enabled ? "已开启" : "已关闭",
        data.model.enabled ? "green" : "",
      ),
      "settings",
    ),
    summaryRow("获授权的 AI 成员", String(n.modelMembers), "accounts"),
    summaryRow("有效登录会话", String(n.sessions), "accounts"),
  );
  root.append(
    node(
      "div",
      "overview-grid",
      panel("共享访问", access),
      panel("实例状态", policy),
    ),
    panel("最近操作", auditList(data.recent), link("全部记录 ›", "history")),
  );
}
async function modelForm(container, data) {
  const m = data.model,
    presets = await (await fetch("/admin/providers.json")).json(),
    page = container;
  const picker = select([
    ...presets.map((p, i) => [String(i), p.label]),
    ["custom", "＋ 自定义兼容接口"],
  ]);
  const index = presets.findIndex((p) => p.url === m.baseUrl);
  picker.value = index < 0 ? "custom" : String(index);
  const base = input("url", m.baseUrl),
    name = input("text", m.model),
    key = input("password"),
    enabled = select(
      [
        ["off", "关闭"],
        ["on", "开启"],
      ],
      m.enabled ? "on" : "off",
    );
  const clearKey = input("checkbox");
  clearKey.checked = false;
  key.autocomplete = "new-password";
  clearKey.onchange = () => {
    key.disabled = clearKey.checked;
    if (clearKey.checked) key.value = "";
  };
  key.placeholder = m.hasKey
    ? "已配置，留空保留原密钥"
    : "API Key（本地模型可留空）";
  picker.onchange = () => {
    const p = presets[Number(picker.value)];
    base.value = p?.url || "";
    name.value = p?.name || "";
    key.value = "";
  };
  page.append(
    form(
      "保存服务器设置",
      async () => {
        await write("instance.model", {
          enabled: enabled.value === "on",
          baseUrl: base.value.trim(),
          model: name.value.trim(),
          ...(clearKey.checked
            ? { key: "" }
            : key.value
              ? { key: key.value }
              : {}),
        });
        message("模型配置已保存");
        await render();
      },
      field("共用 AI", enabled),
      field("服务商", picker),
      field("兼容接口地址", base),
      field("模型编号", name),
      field("服务端密钥", key),
      ...(m.hasKey ? [field("清除已保存的密钥", clearKey)] : []),
    ),
  );
}
async function backupPanel(root) {
  const list = el("div"),
    more = action("更早备份", load),
    create = action("创建备份", () =>
      confirmChange(
        "创建备份",
        "在服务器数据目录的 backups 文件夹写入一致的 SQLite 副本，并校验完整性。",
        async () => {
          await write("instance.backupCreate");
          message("备份已通过校验");
          offset = 0;
          list.replaceChildren();
          await load();
        },
      ),
    );
  let offset = 0;
  async function load() {
    const r = await read("instance.backups", { offset });
    if (!r.backups.length && !offset)
      list.append(empty("尚无通过后台创建的备份。"));
    for (const b of r.backups) {
      const file = el("code", b.file);
      list.append(
        node(
          "div",
          "backup-row",
          el(
            "strong",
            timeLabel(b.at) + " · " + (b.bytes / 1048576).toFixed(2) + " MiB",
          ),
          file,
        ),
      );
    }
    offset += r.backups.length;
    more.hidden = !r.more;
  }
  root.append(
    panel("数据库备份", node("div", "backup-list", list, more), create),
  );
  more.hidden = true;
  await load();
}
async function settings(root, data) {
  const registration = select(
    [
      ["invite", "邀请注册"],
      ["open", "开放注册"],
    ],
    data.registration ? "open" : "invite",
  );
  const reg = form(
    "保存注册方式",
    async () => {
      await write("instance.registration", {
        enabled: registration.value === "open",
      });
      message("注册方式已保存");
      await render();
    },
    field("新成员注册", registration),
  );
  const model = el("div"),
    s = data.storage;
  const values = node("dl", "system-list");
  for (const [k, v] of [
    ["存储引擎", "SQLite " + s.sqlite],
    ["数据库版本", s.schema],
    ["数据库页占用", (s.bytes / 1048576).toFixed(2) + " MiB"],
  ])
    values.append(el("dt", k), el("dd", String(v)));
  root.append(
    node(
      "div",
      "settings-columns",
      node(
        "div",
        "settings-stack",
        panel("注册策略", reg),
        panel("存储状态", values),
      ),
      panel("实例共用模型", model),
    ),
  );
  await Promise.all([modelForm(model, data), backupPanel(root)]);
}
async function render() {
  const serial = ++generation,
    parts = location.hash.slice(1).split("/");
  const key = Object.hasOwn(pages, parts[0]) ? parts[0] : "overview",
    [title, , summary] = pages[key];
  $("page-title").textContent = title;
  $("page-summary").textContent = summary;
  for (const a of nav.children) {
    if (a.dataset.page === key) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  const root = el("div", undefined, "console-view");
  $("page-content").replaceChildren(root);
  if (key === "accounts" || key === "access" || key === "resources") {
    const tabs =
      key === "resources"
        ? ["calendars", "groups"]
        : key === "access"
          ? ["shares", "invites", "tokens"]
          : null;
    let section = tabs
      ? tabs.includes(parts[1])
        ? parts[1]
        : tabs[0]
      : "accounts";
    const ownerKind = key === "resources" && parts[1] === "owners" && ["user", "group"].includes(parts[2]) ? parts[2] : null;
    const targetPart = ownerKind ? parts[3] : parts[2];
    const target = key === "resources" && targetPart ? decodeURIComponent(targetPart) : null;
    if (ownerKind) section = "calendars";
    else if (target) section = section === "groups" ? "members" : "grants";
    else if (section === "calendars") section = "calendarOwners";
    await renderDirectory(root, {
      section,
      target,
      ownerKind,
      tabs: target ? null : tabs,
      route: key,
    });
    return;
  }
  if (key === "releases") return renderReleases(root);
  if (key === "history") {
    const rows = el("div"),
      more = action("更早记录", load);
    let before;
    async function load() {
      const r = await read("instance.history", before ? { before } : {});
      if (serial !== generation) return;
      rows.append(auditList(r.entries));
      before = r.entries.at(-1)?.seq;
      more.hidden = !r.more;
    }
    root.append(panel("后台变更", rows), more);
    more.hidden = true;
    await load();
    return;
  }
  const data = await read("instance.overview");
  if (serial !== generation) return;
  if (key === "settings") await settings(root, data);
  else overview(root, data);
}
