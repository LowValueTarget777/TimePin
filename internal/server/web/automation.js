import { serverReply } from "./stream.js";
import {
  choices,
  selectModel,
  personalChat,
  modelSettings,
  serverModelStatus,
} from "./models.js";
import {
  $,
  action,
  api,
  block,
  command,
  el,
  field,
  form,
  input,
  manage,
  message as notify,
  modal,
  plus,
  roles,
  select,
  uid,
  zone,
} from "./ui.js";

function jsonView(value) {
  const pre = el("pre", JSON.stringify(value, null, 2), "json-result");
  pre.tabIndex = 0;
  return pre;
}
const labels = {
  created: "新增",
  updated: "更新",
  unchanged: "跳过",
  conflict: "冲突",
  forbidden: "权限不足",
  removed: "移除新增事件",
  restored: "恢复原日程",
  changed: "保留后续改动",
};
function receipt(value, proposal) {
  const box = el("div", undefined, "result");
  const items = value.undo || value.rows;
  if (items) {
    const counts = new Map();
    for (const r of items)
      counts.set(r.outcome, (counts.get(r.outcome) || 0) + 1);
    box.append(
      el(
        "p",
        items.length
          ? Array.from(counts, ([k, n]) => (labels[k] || k) + " " + n).join(
              " · ",
            )
          : "此批次没有需要撤销的修改",
      ),
    );
    const list = el("details");
    const rows = el("ol");
    for (const item of items) {
      const input = proposal?.batch?.rows?.[item.index];
      const label = input?.event?.title || input?.externalId || item.eventId;
      rows.append(el("li", [label, labels[item.outcome] || item.outcome].filter(Boolean).join(" · ")));
    }
    list.open = !!proposal;
    list.append(el("summary", "逐项回执"), rows);
    const raw = el("details");
    raw.append(el("summary", "查看原始回执"), jsonView(items));
    list.append(raw);
    box.append(list);
  } else {
    if (Array.isArray(value.calendars)) {
      for (const b of value.calendars) box.append(el("p", b.name + " · " + (b.result?.occurrences?.length || 0) + " 项"));
    }
    const record = value.record;
    if (record?.deleted) box.append(el("strong", "日程已删除"));
    else if (record?.event) {
      const { title, span } = record.event;
      box.append(el("strong", title));
      if (span.kind === "date") {
        const last = plus(span.end, -1);
        box.append(el("p", (last === span.start ? last : span.start + " 至 " + last) + " · 全天"));
      } else {
        const when = new Intl.DateTimeFormat("zh-CN", {
          timeZone: span.zone, dateStyle: "short", timeStyle: "short", hourCycle: "h23",
        });
        box.append(el("p", when.formatRange(new Date(span.start), new Date(span.end)) + " · " + span.zone));
      }
    }
    const detail = el("details");
    detail.append(el("summary", "查看操作回执"), jsonView(value));
    box.append(detail);
  }
  if (value.batchId) box.append(el("p", value.batchId, "subtle"));
  return box;
}

export async function undoImports(calendarId, refresh) {
  const body = modal("导入批次"),
    list = el("div"),
    result = el("div");
  body.append(list, result);
  let before;
  async function load() {
    const q = new URLSearchParams({ calendarId });
    if (before) q.set("before", before);
    const items = await api("batches?" + q);
    more.hidden = items.length < 50;
    if (!items.length && !before)
      list.append(el("p", "这本日历还没有导入批次。"));
    for (const item of items) {
      before = item.seq;
      list.append(
        action(
          "批次 " + item.seq + " · " + item.id,
          async () => {
            const batchId = item.id;
            const preview = await command({
              op: "import.undoPreview",
              calendarId,
              batchId,
            });
            const operationId = uid();
            result.replaceChildren(el("h3", "撤销预览"), receipt(preview));
            if (
              preview.undo.some(
                (x) => x.outcome === "removed" || x.outcome === "restored",
              )
            )
              result.append(
                action(
                  "确认撤销此批次",
                  async () => {
                    const r = await command({
                      op: "import.undo",
                      operationId,
                      calendarId,
                      batchId,
                    });
                    result.replaceChildren(el("h3", "撤销结果"), receipt(r));
                    await refresh();
                  },
                  "danger",
                ),
              );
          },
          "batch-choice",
        ),
      );
    }
  }
  const more = action("更早的批次", load);
  body.append(more);
  await load();
}

export async function accessSettings(state) {
  const body = modal("接口令牌");
  const keys = el("div", undefined, "token-settings");
  body.append(keys);
  const grants = await api("manage", { op: "token.list" });
  for (const key of grants)
    keys.append(
      el("p", [key.name, key.calendarId === "*" ? "所有日历" :
        state.calendars.find((c) => c.id === key.calendarId)?.name || key.calendarId, roles[key.role]].join(" · ")),
      action("撤销 " + key.name, async () => {
        await manage("token.revoke", { target: key.id });
        await accessSettings(state);
      }),
    );
  const name = input("text", ""),
    calendar = select([...state.calendars.map((c) => [c.id, c.name]), ["*", "所有日历"]]),
    role = select([]);
  const order = ["reader", "contributor", "editor", "manager"];
  function options() {
    const max = calendar.value === "*" ? "manager" : state.calendars.find((x) => x.id === calendar.value)?.role;
    scopeHint.hidden = calendar.value !== "*";
    role.replaceChildren(
      ...order
        .slice(0, order.indexOf(max) + 1)
        .map((k) => new Option(roles[k], k)),
    );
  }
  const scopeHint = el("small", "包含现在和以后加入的日历，每本日历仍按你的实际权限访问。", "subtle");
  options();
  calendar.onchange = options;
  name.required = true;
  name.maxLength = 80;
  let secret = uid() + uid();
  keys.append(
    form(
      "创建令牌",
      async () => {
        await manage("token.create", {
          target: calendar.value,
          name: name.value.trim(),
          role: role.value,
          token: secret,
        });
        const created = secret;
        await accessSettings(state);
        const key = input("text", created);
        key.readOnly = true;
        body.prepend(field("新令牌，请保存到客户端", key));
        key.select();
      },
      field("用途名称", name),
      field("授权日历", calendar),
      scopeHint,
      field("最高角色", role),
    ),
  );
}

function sendControls(userId, form, textarea, submit, menuListeners) {
  const key = "timepin.chat.enter." + userId;
  let mode = "newline";
  try { if (localStorage.getItem(key) === "send") mode = "send"; } catch {}
  const hint = el("small", undefined, "send-hint");
  hint.id = "chat-send-hint";
  textarea.setAttribute("aria-describedby", hint.id);
  const picker = el("button", undefined, "send-mode");
  picker.type = "button";
  picker.setAttribute("aria-label", "回车键行为");
  picker.setAttribute("aria-haspopup", "menu");
  picker.setAttribute("aria-controls", "chat-send-options");
  const menu = el("div", undefined, "send-options");
  menu.id = "chat-send-options";
  menu.setAttribute("role", "menu");
  menu.setAttribute("aria-label", "回车键行为");
  function toggle(open, focus = false) {
    menu.hidden = !open;
    picker.setAttribute("aria-expanded", String(open));
    if (open) menu.querySelector('[aria-checked="true"]').focus();
    else if (focus) picker.focus();
  }
  function updateHint() {
    picker.title = "回车键行为 · " + (mode === "send" ? "回车发送" : "回车换行");
    textarea.enterKeyHint = mode === "send" ? "send" : "enter";
    hint.textContent = mode === "send" ? "Enter 发送 · Shift + Enter 换行" : "Enter 换行 · Ctrl / ⌘ + Enter 发送";
    for (const option of menu.children) option.setAttribute("aria-checked", String(option.value === mode));
  }
  for (const [value, title] of [["newline", "回车换行"], ["send", "回车发送"]]) {
    const option = el("button", title);
    option.type = "button";
    option.value = value;
    option.setAttribute("role", "menuitemradio");
    option.tabIndex = -1;
    option.onclick = () => {
      mode = value;
      try { localStorage.setItem(key, value); } catch { notify("本次对话已更新回车设置"); }
      updateHint();
      toggle(false, true);
    };
    menu.append(option);
  }
  updateHint();
  toggle(false);
  picker.onclick = () => toggle(menu.hidden);
  picker.onkeydown = event => {
    if (["ArrowUp", "ArrowDown"].includes(event.key)) { event.preventDefault(); toggle(true); }
  };
  menu.onkeydown = event => {
    const options = [...menu.children], index = options.indexOf(document.activeElement);
    if (event.key === "Escape") {
      event.preventDefault(); event.stopPropagation(); toggle(false, true);
    } else if (["ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next = event.key === "Home" ? 0 : event.key === "End" ? options.length - 1 :
        (index + options.length + (event.key === "ArrowDown" ? 1 : -1)) % options.length;
      options[next].focus();
    }
  };
  let composing = false;
  textarea.addEventListener("compositionstart", () => { composing = true; });
  textarea.addEventListener("compositionend", () => { composing = false; });
  textarea.onkeydown = (event) => {
    if (event.key !== "Enter" || composing || event.isComposing || event.keyCode === 229 ||
        event.shiftKey || event.altKey) return;
    if (mode !== "send" && !event.ctrlKey && !event.metaKey) return;
    event.preventDefault();
    if (!event.repeat && !submit.disabled && textarea.value.trim()) form.requestSubmit(submit);
  };
  const buttons = el("div", undefined, "send-buttons");
  buttons.append(submit, picker, menu);
  buttons.addEventListener("focusout", e => {
    if (!buttons.contains(e.relatedTarget)) toggle(false);
  });
  $("dialog").addEventListener("pointerdown", e => {
    if (!buttons.contains(e.target)) toggle(false);
  }, { signal: menuListeners.signal });
  form.append(hint, buttons);
}

let conversation;
function chatContext(state) {
  return JSON.stringify([state.user.id, state.calendars.map(b => [b.id,b.name,b.role]), state.ai, choices(state.user.id)]);
}

function calendarChecks(books, prior) {
  const section = el("fieldset", undefined, "chat-scope");
  section.append(el("legend", "对话日历"));
  const all = input("checkbox"), list = el("div", undefined, "scope-books");
  const master = el("label");
  master.append(all, el("strong","全部日历"));
  section.append(master,list);
  const picks = books.map(b => {
    const label=el("label"), check=input("checkbox",b.id);
    check.checked=prior == null || prior.includes(b.id);
    label.append(check,el("span",b.name)); list.append(label);
    return check;
  });
  const selected=()=>picks.filter(c=>c.checked).map(c=>c.value);
  function update(){
    const n=selected().length;
    all.checked=n>0 && n===picks.length; all.indeterminate=n>0 && n<picks.length;
    all.disabled=!picks.length;
  }
  all.onchange=()=>{ for(const c of picks)c.checked=all.checked; };
  section.addEventListener("change",update);
  update();
  return {section,selected};
}

export function openChat(state, refresh) {
  const body = modal("日历助手", true);
  if (conversation && conversation.key === chatContext(state)) {
    body.append(conversation.panel); conversation.resume(); return;
  }
  const prior = conversation?.userId === state.user.id ? conversation.scope() : null;
  const priorMode = conversation?.userId === state.user.id ? conversation.mode.value : "calendar";
  conversation?.dispose();
  const panel = el("div", undefined, "chat-panel");
  body.append(panel);
  const menuListeners = new AbortController();
  const {section:calendar,selected} = calendarChecks(state.calendars, prior);
  const mode = select([["calendar","所选日历"],["account","账号管理"]],priorMode);
  const createCalendar = action("创建日历", () => {
    if (controller) return;
    const draft = message.value;
    mode.value = "account";
    mode.onchange();
    message.value = draft || "帮我创建一本日历，名称是";
    message.focus();
  }, "chat-starter");
  calendar.hidden = priorMode === "account";
  const log = el("div", undefined, "chat-log");
  log.setAttribute("aria-label", "对话记录");
  const message = el("textarea");
  message.required = true;
  message.maxLength = 8000;
  message.placeholder = calendar.hidden ? "例如：新建一本名为「排班」的日历" : "例如：把下周五晚上的值班改到周六";
  const history = [];
  let retry, controller, lastEntry;
  const starter = action("查看未来七天的日程", () => {
    message.value = "帮我查看所选日历未来七天的日程";
    message.focus();
  }, "chat-starter");
  log.append(starter);
  calendar.onchange = () => {
    conversation.key = chatContext(state);
    history.length = 0;
    log.replaceChildren(starter);
    retry = null;
    availability();
  };
  let cfg = choices(state.user.id);
  const source = select([]);
  function updateSources() {
    cfg = choices(state.user.id);
    source.replaceChildren(
      new Option("服务器模型 · " + serverModelStatus(state.ai), "server"),
      ...cfg.profiles.map((p) => new Option("个人 · " + p.label, p.id)),
    );
    source.value = cfg.selected;
  }
  updateSources();
  const blocked = () => (mode.value === "calendar" && !selected().length) || (source.value === "server" && !state.ai?.enabled);
  const hint = el("p", "可联系管理员开通服务器模型，也可在模型设置中选择个人模型。", "availability");
  function availability() {
    hint.textContent = mode.value === "calendar" && !selected().length ? "请先勾选至少一本对话日历。" : "可联系管理员开通服务器模型，也可在模型设置中选择个人模型。";
    hint.hidden = !blocked();
    submit.disabled = blocked() || !!controller;
    starter.hidden = blocked() || mode.value === "account";
    createCalendar.hidden = mode.value === "account";
    createCalendar.disabled = !!controller;
  }
  source.onchange = () => {
    selectModel(state.user.id, source.value);
    calendar.onchange();
    availability();
  };
  const controls = el("div", undefined, "chat-controls");
  controls.classList.add("scoped-controls");
  mode.onchange = () => {
    calendar.hidden = mode.value === "account";
    message.value = "";
    message.placeholder = calendar.hidden ? "例如：新建一本名为「排班」的日历" : "例如：把下周五晚上的值班改到周六";
    calendar.onchange();
  };
  controls.append(field("操作范围",mode), calendar, field("模型来源", source), createCalendar);
  const configure = action("模型设置", () => {
    const previous = source.value;
    let edited = false;
    modelSettings(state, () => { edited = true; }, () => {
      modal("日历助手", true).append(panel);
      conversation.key = chatContext(state);
      updateSources();
      if (edited || previous !== source.value) calendar.onchange();
      availability();
      configure.focus();
    });
  }, "link chat-configure");
  panel.append(controls, configure, hint, log);
  const stop = action("停止生成", () => controller?.abort(), "chat-stop");
  stop.hidden = true;
  const send = form(
    "发送",
    async () => {
      const prompt = message.value.trim();
      if (!prompt || blocked() || controller) return;
      const isRetry = retry?.message === prompt;
      lastEntry?.querySelector(".retry-chat")?.remove();
      if (!retry || retry.message !== prompt)
        retry = {
          operationId: uid(),
          ...(mode.value === "account" ? { scope:"account", context:{groups:state.groups.map(({id,name,role}) => ({id,name,role}))} } :
            selected().length === 1 ? { calendarId: selected()[0] } : { calendarIds: selected() }),
          message: prompt,
          zone,
          history: history.slice(-8),
        };
      if (isRetry) lastEntry?.remove();
      starter.remove();
      const reply = el("p", undefined, "chat-reply");
      const status = el("small", "正在连接模型…", "chat-status");
      status.setAttribute("role", "status");
      const entry = block(prompt, status, reply);
      lastEntry = entry;
      log.append(entry);
      log.scrollTop = log.scrollHeight;
      controller = new AbortController();
      const signal = controller.signal;
      let frame = 0, pendingText = "";
      function paint() {
        cancelAnimationFrame(frame);
        frame = 0;
        if (!pendingText) return;
        const follow = log.scrollHeight - log.scrollTop - log.clientHeight < 50;
        if (!reply.firstChild) {
          status.textContent = "正在回复";
          reply.append(document.createTextNode(""));
        }
        reply.firstChild.appendData(pendingText);
        pendingText = "";
        if (follow) log.scrollTop = log.scrollHeight;
      }
      function update(kind, value) {
        if (kind === "status") {
          pendingText = "";
          status.textContent = value;
          reply.replaceChildren();
        } else {
          pendingText += value;
          if (!frame) frame = requestAnimationFrame(paint);
        }
      }
      stop.hidden = false;
      mode.disabled = source.disabled = calendar.disabled = message.readOnly = true;
      configure.disabled = true;
      submit.textContent = "发送中…";
      starter.disabled = true;
      createCalendar.disabled = true;
      try {
        const account = mode.value === "account";
        // The model chooses the action. Only the confirmation request needs the invitation secret.
        const invitePattern = /(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])/g;
        const accountMessages = account ? [...retry.history.filter(m => m.role === "user").map(m => m.content), retry.message] : [];
        const invitations = accountMessages.map(m => m.match(invitePattern)).filter(Boolean).at(-1) || [];
        const hideInvite = text => text.replace(invitePattern,"[本次邀请]");
        const input = account ? {scope:"account",message:hideInvite(retry.message),context:retry.context,
          history:retry.history.map(m => ({...m,content:hideInvite(m.content)}))} : retry;
        const r =
          source.value === "server"
            ? await api(account ? "ai/actions" : "ai/chat", input, { signal, readStream: r => serverReply(r, update) })
            : await personalChat(
                state.user,
                cfg.profiles.find((p) => p.id === source.value),
                input, update, signal, state.calendars.filter(b => selected().includes(b.id)),
              );
        if (account && r.proposal) {
          const proposal = r.proposal;
          if (!["calendar.create","group.create","invite.accept"].includes(proposal.op)) throw Error("无效的账号提案");
          if (proposal.op === "invite.accept") {
            if (invitations.length !== 1) throw Error("请在同一条消息中粘贴一份邀请链接或邀请码");
            proposal.token = invitations[0];
            r.text = "用当前账号接受这份邀请？";
          } else if (proposal.op === "calendar.create") {
            const team = state.groups.find(g => g.id === proposal.team);
            r.text = "在" + (team ? "团组「" + team.name + "」" : "当前账号") + "创建日历「" + proposal.name + "」？";
          } else r.text = "创建团组「" + proposal.name + "」？";
          proposal.operationId = retry.operationId;
          const confirmation = {userId:state.user.id,source:source.value === "server" ? "server" : "personal",command:proposal};
          r.status = "confirmation";
          r.confirm = async () => ({text:"已执行账号操作",result:await api("ai/account/confirm",confirmation)});
        }
        paint();
        retry = null;
        message.value = "";
        status.textContent = "回复完成";
        status.classList.add("chat-sr-only");
        reply.textContent = r.text;
        if (r.result) entry.append(receipt(r.result, r.proposal));
        if (log.scrollHeight - log.scrollTop - log.clientHeight < 80) log.scrollTop = log.scrollHeight;
        const previous = { role: "assistant", content: r.text.slice(0, 2000) };
        const record = r.result?.record;
        if (record)
          previous.content +=
            " " +
            JSON.stringify({
              id: record.id,
              version: record.version,
              title: record.event?.title,
            });
        history.push({ role: "user", content: prompt }, previous);
        if (r.status === "confirmation") {
          const details = el("details");
          details.append(el("summary", "拟提交内容"), jsonView(r.proposal));
          entry.append(details);
          const confirm = action(
            "确认执行",
            async () => {
              const final = r.confirm
                ? await r.confirm()
                : await api("ai/confirm", {
                    operationId: r.operationId,
                  });
              previous.content =
                final.text + " " + (final.result?.batchId || "");
              confirm.remove();
              entry.append(el("p", final.text), receipt(final.result));
              await refresh();
              if (conversation.key !== chatContext(state) && panel.isConnected && $("dialog").open)
                openChat(state, refresh);
            },
            "primary",
          );
          entry.append(confirm);
        } else if (r.op) await refresh();
      } catch (error) {
        paint();
        if (!retry) throw error;
        status.textContent = signal.aborted ? "已停止接收回复" : error.message;
        const again = action("重试", () => {
          if (retry?.message !== prompt || controller) return;
          message.value = prompt;
          send.requestSubmit(submit);
        }, "link");
        again.classList.add("retry-chat");
        entry.append(again);
        if (signal.aborted && source.value === "server") await refresh();
      } finally {
        controller = null;
        stop.hidden = true;
        mode.disabled = source.disabled = calendar.disabled = message.readOnly = false;
        configure.disabled = false;
        submit.textContent = "发送";
        starter.disabled = false;
        createCalendar.disabled = false;
      }
    },
    field("消息", message),
  );
  const submit = send.querySelector("button[type=submit]");
  sendControls(state.user.id, send, message, submit, menuListeners);
  send.querySelector(".send-buttons").before(stop);
  panel.append(send);
  conversation = {
    key: chatContext(state), panel, calendar, mode, userId: state.user.id,
    scope() { const ids=selected(); return ids.length===calendar.querySelectorAll(".scope-books input").length ? null : ids; },
    dispose() { menuListeners.abort(); controller?.abort(); },
    resume() {
      availability();
    },
  };
  availability();
}
