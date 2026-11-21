import {
  action,
  api,
  command,
  el,
  field,
  form,
  input,
  modal,
  run,
  select,
  uid,
} from "./ui.js";
import { icon } from "./components.js";

import { modelReply } from "./stream.js";

let presets;
const keys = new Map();
const keySlot = (user, profile) => JSON.stringify([user.id, profile.id, profile.url]);
const storage = (id) => "timepin.models." + id;
export function choices(id) {
  let saved;
  try {
    saved = JSON.parse(localStorage.getItem(storage(id)));
  } catch {}
  const profiles = Array.isArray(saved?.profiles)
    ? saved.profiles.filter((p) => p?.id && p.url && p.name && p.label)
    : [];
  const selected = profiles.some((p) => p.id === saved?.selected)
    ? saved.selected
    : "server";
  return { profiles, selected };
}
export async function providers() {
  if (!presets) {
    const response = await fetch("/providers.json");
    if (!response.ok) throw Error("模型预设载入失败");
    presets = await response.json();
  }
  return presets;
}
export function selectModel(id, value) {
  const cfg = choices(id);
  cfg.selected = value;
  localStorage.setItem(storage(id), JSON.stringify(cfg));
}
export function serverModelStatus(ai) {
  return ai?.enabled ? ai.model || "已启用" : "当前账号暂不可用";
}
export function modelSummary(state) {
  const { profiles, selected } = choices(state.user.id);
  if (selected === "server") return "服务器模型 · " + serverModelStatus(state.ai);
  return "个人模型 · " + profiles.find((p) => p.id === selected).label;
}
export function modelSettings(state, changed, back) {
  const { profiles, selected } = choices(state.user.id);
  const body = modal("模型来源");
  const list = el("fieldset", undefined, "source-list");
  list.append(el("legend", "日历助手使用的模型"));
  function add(id, label, detail, edit) {
    const line = el("div", undefined, "source-option");
    const option = input("radio", id);
    option.name = "assistant-source";
    option.checked = selected === id;
    const caption = el("label"), copy = el("span", undefined, "source-copy");
    copy.append(el("strong", label), el("small", detail));
    caption.append(option, copy);
    option.onchange = () => run(() => {
      selectModel(state.user.id, id);
      changed();
    });
    line.append(caption);
    if (edit) {
      const button = action("编辑", edit, "link");
      button.setAttribute("aria-label", "编辑 " + label);
      line.append(button);
    }
    list.append(line);
  }
  const reopen = () => modelSettings(state, changed, back);
  add("server", "服务器模型", "默认 · " + serverModelStatus(state.ai));
  for (const profile of profiles)
    add(profile.id, profile.label, profile.name, () =>
      editProfile(state.user, profile, changed, reopen));
  const create = action("添加个人模型", () =>
    editProfile(state.user, null, changed, reopen), "add-profile");
  create.prepend(icon("plus"));
  body.append(list, create);
  if (back) body.append(action("返回日历助手", back, "link back-to-chat"));
}
async function editProfile(user, previous, changed, back) {
  const defaults = previous ? [] : await providers();
  const body = modal(previous ? "编辑个人模型" : "添加个人模型");
  const picker = select([
    ...defaults.map((p, i) => [String(i), p.label]),
    ["new", "＋ 自定义兼容接口"],
  ]);
  const title = input("text"),
    base = input("url"),
    name = input("text"),
    key = input("password");
  key.autocomplete = "off";
  key.placeholder = "仅在当前标签页使用";
  for (const control of [title, base, name]) control.required = true;
  function fill() {
    const item = previous || defaults[Number(picker.value)] || {};
    title.value = item.label || "";
    base.value = item.url || "";
    name.value = item.name || "";
    key.value = previous ? keys.get(keySlot(user, previous)) || "" : "";
  }
  picker.onchange = fill;
  fill();
  body.append(action("‹ 模型来源", back, "link profile-back"));
  body.append(
    form(
      "保存并使用",
      () => {
        const endpoint = validateURL(base.value);
        const id = previous?.id || uid();
        if (
          previous?.id &&
          previous.url !== endpoint &&
          key.value &&
          key.value === keys.get(keySlot(user, previous))
        )
          throw Error("更换服务地址时，请清空或重新设置密钥");
        const model = {
          id,
          label: title.value.trim(),
          url: endpoint,
          name: name.value.trim(),
        };
        if (!model.label || !model.name) throw Error("请填写名称和模型编号");
        const cfg = choices(user.id);
        if (cfg.profiles.some((p) => p.id === id))
          cfg.profiles = cfg.profiles.map((p) => p.id === id ? model : p);
        else cfg.profiles.push(model);
        cfg.selected = id;
        localStorage.setItem(storage(user.id), JSON.stringify(cfg));
        if (previous) keys.delete(keySlot(user, previous));
        keys.set(keySlot(user, model), key.value);
        changed();
        back();
      },
      ...(previous ? [] : [field("服务商", picker)]),
      field("配置名称", title),
      field("兼容接口地址", base),
      field("模型编号", name),
      field("API Key", key),
    ),
  );
}
function validateURL(value) {
  const u = new URL(value);
  if (
    !["http:", "https:"].includes(u.protocol) ||
    u.username ||
    u.password ||
    u.search ||
    u.hash
  )
    throw Error("请填写 HTTP 或 HTTPS 模型接口地址");
  return u.href.replace(/\/+$/, "");
}
export async function personalChat(user, profile, inbox, update, signal, books) {
  const account = inbox.scope === "account";
  const multi = !!inbox.calendarIds;
  const tools = account ? (await api("ai/actions?scope=account")).tools : multi ? await api("ai/tools", { calendarIds: inbox.calendarIds }) :
    await api("ai/tools?calendarId=" + encodeURIComponent(inbox.calendarId));
  const names = new Set(tools.map((t) => t.function.name));
  const key = keys.get(keySlot(user, profile));
  const messages = [
    {
      role: "system",
      content: account ? "你是 TimePin 账号助手。可创建个人日历或团组日历（默认颜色 #278B77）、建团组、接受一次性邀请。按用户指令提出一项操作，确认后才生效。团组资料：" + JSON.stringify(inbox.context.groups) :
        "你是 TimePin 日历助手。当前日期 " +
        new Date().toLocaleDateString() +
        "，时区 " +
        inbox.zone + "。用户选中的日历：" + JSON.stringify(books.map(({id,name,role}) => ({id,name,role}))) +
        "。多选时 event_query 省略 calendarId 查询所有选中日历，其他操作指定目标；不确定目标时询问用户。先查询事件取得编号和版本。写入向用户提出操作。",
    },
    ...inbox.history,
    { role: "user", content: inbox.message },
  ];
  for (let i = 0; i < 4; i++) {
    signal.throwIfAborted();
    update("status", "正在生成回复");
    let response;
    try {
      response = await fetch(validateURL(profile.url) + "/chat/completions", {
        method: "POST",
        credentials: "omit",
        redirect: "error",
        referrerPolicy: "no-referrer",
        signal: AbortSignal.any([signal, AbortSignal.timeout(45000)]),
        headers: {
          "Content-Type": "application/json",
          ...(key ? { Authorization: "Bearer " + key } : {}),
        },
        body: JSON.stringify({
          model: profile.name,
          messages,
          tools,
          stream: true,
          ...(profile.url === "https://api.deepseek.com"
            ? { thinking: { type: "disabled" } }
            : {}),
        }),
      });
    } catch {
      throw Error("浏览器无法连接模型。请检查地址、HTTPS 和服务端的跨域许可。");
    }
    if (!response.ok)
      throw Error(
        "个人模型返回 HTTP " +
          response.status +
          "，请检查接口、模型和本标签页密钥",
      );
    const answer = await modelReply(response, update);
    signal.throwIfAborted();
    if (!answer || answer.role !== "assistant") throw Error("模型响应无效");
    const calls = answer.tool_calls || [];
    if (!calls.length) return { text: answer.content || "已完成" };
    if (multi && calls.length > 1 && calls.every(c => c.function.name === "event_query")) {
      const replies = [];
      let bytes=0;
      for (const c of calls) {
        const {calendarId, window} = JSON.parse(c.function.arguments);
        if (calendarId !== undefined && !inbox.calendarIds.includes(calendarId)) throw Error("目标不在所选日历范围内");
        const result = await api("ai/query", {calendarIds: calendarId ? [calendarId] : inbox.calendarIds, window});
        const content=JSON.stringify(result);
        bytes+=content.length;
        if (bytes>24000) throw Error("结果太多，请缩小日期范围");
        replies.push({role:"tool",tool_call_id:c.id,content});
      }
      messages.push(answer,...replies);
      continue;
    }
    if (calls.length !== 1)
      throw Error("请让模型将多项写入合并到一个导入批次，或逐项处理");
    const call = calls[0],
      name = call.function.name;
    if (!names.has(name)) throw Error("当前日历未授权此工具");
    const op = name.replace("_", "."),
      args = JSON.parse(call.function.arguments);
    if (account) return { text: "请核对账号操作", proposal: { ...args, op } };
    const target = multi ? args.calendarId : inbox.calendarId;
    const broad = multi && op === "event.query" && target === undefined;
    const book = books.find(b => b.id === target);
    if (multi && !broad && !op.startsWith("display.") && !book) {
      if (target === undefined) return {text:"要操作哪本日历？请告诉我日历名称。"};
      throw Error("目标不在所选日历范围内");
    }
    delete args.calendarId;
    const payload = { ...args, op, operationId: inbox.operationId };
    let execute;
    if (broad) execute = () => api("ai/query", { calendarIds: inbox.calendarIds, window: args.window });
    else if (op === "batches") {
      const query = new URLSearchParams({ calendarId: target });
      if (args.before !== undefined) query.set("before", args.before);
      execute = () => api("batches?" + query);
    }
    else if (op.startsWith("event.") || op.startsWith("import.")) {
      payload.calendarId = target;
      if (["event.query", "event.get", "import.undoPreview"].includes(op))
        delete payload.operationId;
      if (op === "event.create") payload.eventId = "e-" + inbox.operationId;
      execute = () => command(payload);
    } else {
      if (op.startsWith("calendar.")) payload.target = target;
      if (op.endsWith(".detail")) delete payload.operationId;
      execute = () => api("manage", payload);
    }
    if (
      [
        "event.query",
        "event.get",
        "batches",
        "calendar.detail",
        "display.detail",
        "import.preview",
        "import.undoPreview",
      ].includes(op)
    ) {
      update("status", "正在查询日历");
      const result = await execute();
      messages.push(answer, {
        role: "tool",
        tool_call_id: call.id,
        content: JSON.stringify(result),
      });
      continue;
    }
    let preview;
    if (op === "import.commit" || op === "import.undo") {
      update("status", "正在预览逐条变更");
      const check = { ...payload, op: op === "import.commit" ? "import.preview" : "import.undoPreview" };
      if (op === "import.undo") delete check.operationId;
      preview = await command(check);
      if (op === "import.commit") {
        payload.batch.rows.forEach((row, i) => {
          row.baseVersion ??= preview.rows[i].baseVersion;
        });
      }
    }
    return {
      text: op === "calendar.delete" ? "确认后将删除所选日历及其中全部日程，共享成员也会失去访问权限。" : (book ? "请核对「" + book.name + "」的操作" : "请核对拟提交的操作"),
      status: "confirmation",
      result: preview,
      proposal: payload,
      confirm: async () => {
        if ((await api("state")).user?.id !== user.id)
          throw Error("账号已切换，请重新发起对话");
        return { text: "已执行", result: await execute() };
      },
    };
  }
  throw Error("模型未在本轮内完成，请缩小查询范围");
}
