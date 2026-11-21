import {
  $,
  action,
  confirmChange,
  el,
  empty,
  field,
  form,
  freshCode,
  input,
  link,
  message,
  modal,
  node,
  read,
  roleName,
  select,
  showCode,
  tag,
  textInput,
  timeLabel,
  write,
} from "./console-ui.js";
import { api, run, uid } from "./ui.js";

const labels = {
  calendarOwners: "日历归属",
  accounts: "成员账号",
  calendars: "日历",
  groups: "团组",
  shares: "只读链接",
  invites: "有效邀请",
  tokens: "API 令牌",
  members: "成员名单",
  grants: "直接授权",
};
const options = (roles) => roles.map((k) => [k, roleName[k]]);
function selector(section, valueKey = "id") {
  const search = input("search"),
    choice = select([]),
    box = node("div", "picker", search, choice);
  search.placeholder = section === "groups" ? "搜索团组" : "搜索账号";
  search.setAttribute("aria-label", search.placeholder);
  choice.required = true;
  choice.setAttribute("aria-label", labels[section]);
  let serial = 0,
    timer;
  async function update() {
    const n = ++serial;
    const result = await read("instance.directory", {
      section,
      search: search.value,
    });
    if (n !== serial) return;
    choice.replaceChildren(
      new Option("请选择" + (section === "groups" ? "团组" : "账号"), ""),
      ...result.items.map((r) => new Option(r.name, r[valueKey])),
    );
  }
  search.oninput = () => {
    serial++;
    choice.replaceChildren();
    clearTimeout(timer);
    timer = setTimeout(() => run(update), 180);
  };
  run(update);
  return { box, choice };
}
function ownerSelector() {
  const type = select([
      ["user", "个人账号"],
      ["group", "团组"],
    ]),
    holder = el("div");
  let pick;
  function change() {
    pick = selector(
      type.value === "user" ? "accounts" : "groups",
      type.value === "user" ? "name" : "id",
    );
    holder.replaceChildren(pick.box);
  }
  change();
  type.onchange = change;
  return {
    nodes: [field("归属类型", type), holder],
    args: () => ({ subjectKind: type.value, subject: pick.choice.value }),
  };
}
function mutationForm(title, op, args, fields, refresh) {
  const body = modal(title);
  body.append(
    form(
      "保存",
      async () => {
        await write(op, args());
        $("dialog").close();
        message("已保存");
        await refresh();
      },
      ...fields,
    ),
  );
}
function createResource(section, refresh) {
  const name = textInput();
  if (section === "groups") {
    const owner = selector("accounts", "name");
    mutationForm(
      "新建团组",
      "instance.groupCreate",
      () => ({ name: name.value.trim(), subject: owner.choice.value }),
      [field("团组名称", name), field("首位团组管理员", owner.box)],
      refresh,
    );
  } else {
    const color = input("color", "#367c6b"),
      owner = ownerSelector();
    mutationForm(
      "新建日历",
      "instance.calendarCreate",
      () => ({ name: name.value.trim(), color: color.value, ...owner.args() }),
      [field("日历名称", name), field("默认颜色", color), ...owner.nodes],
      refresh,
    );
  }
}
function credentialForm(member, refresh) {
  const body = modal(member ? "重设密码 · " + member.name : "添加账号");
  const name = textInput();
  const password = input("password"), confirmation = input("password");
  for (const box of [password, confirmation]) {
    box.required = true;
    box.minLength = 10;
    box.autocomplete = "new-password";
  }
  let operationId = uid();
  const editor = form(member ? "重设密码" : "创建账号", async () => {
    if (password.value !== confirmation.value) throw Error("两次输入的密码不一致");
    await api("admin/manage", {
      op: member ? "instance.accountPassword" : "instance.accountCreate",
      operationId, ...(member ? {target: member.id} : {name: name.value.trim()}), password: password.value,
    });
    editor.reset();
    $("dialog").close();
    message(member ? "密码已重设" : "账号已创建");
    await refresh();
  }, ...(member ? [el("p", "保存后，该成员需要重新登录，已有接口令牌与恢复码同时失效。")] : [field("登录名", name)]),
    field("新密码（至少 10 个字符）", password), field("确认密码", confirmation));
  editor.addEventListener("input", () => { operationId = uid(); });
  body.append(editor);
}
const pendingCodes = new Map();
async function issueCode(user, refresh) {
  const key = user?.id || "signup";
  if (!pendingCodes.has(key)) pendingCodes.set(key, freshCode());
  const token = pendingCodes.get(key);
  const result = await write(
    user ? "instance.recovery" : "instance.invite",
    user ? { target: user.id, token } : { token },
  );
  pendingCodes.delete(key);
  showCode(
    user ? "账号恢复码" : "实例注册码",
    token,
    (user
      ? "交给「" +
        user.name +
        "」。在个人登录页选择「使用恢复码」，设置新密码。"
      : "将此码交给新成员，在个人注册页填写邀请码。") +
      " 有效至 " +
      timeLabel(result.expires) +
      "，限用一次。",
  );
  await refresh();
}
function userActions(r, refresh) {
  const title = r.name,
    body = modal(title),
    target = r.id;
  const confirm = (name, detail, op, args) =>
    action(name, () =>
      confirmChange(name, detail, async () => {
        await write(op, args);
        await refresh();
      }),
    );
  body.append(
    node(
      "div",
      "info-line",
      tag(r.enabled ? "已启用" : "已停用", r.enabled ? "green" : ""),
      el("span", r.sessions + " 个有效登录会话"),
    ),
  );
  const choices = node(
    "div",
    "operation-list",
    action("修改登录名", () => {
      const name = textInput(r.name);
      mutationForm("修改登录名", "instance.accountRename", () => ({target, name: name.value.trim()}),
        [field("登录名", name)], refresh);
    }),
    action("重设密码", () => credentialForm(r, refresh)),
    confirm(
      r.enabled ? "停用账号" : "启用账号",
      r.enabled
        ? "将退出该成员的登录会话，撤销接口令牌与恢复码。其日历与团组会保留。"
        : "成员可再次登录自己的日历。",
      "instance.account",
      { target, enabled: !r.enabled },
    ),
    confirm(
      r.ai ? "撤销 AI 授权" : "授权共用 AI",
      "更改「" + title + "」的共用模型使用权限。",
      "model.grant",
      { subject: title, enabled: !r.ai },
    ),
  );
  if (r.sessions)
    choices.append(
      confirm(
        "退出全部会话",
        "该成员的 Web 和安卓需要重新登录。接口令牌单独管理。",
        "instance.signout",
        { target },
      ),
    );
  if (r.enabled) choices.append(action("发放恢复码", () => confirmIssue()));
  if (r.recovery)
    choices.append(
      confirm(
        "撤销恢复码",
        "此账号尚未使用的恢复码将失效。",
        "instance.cancelRecovery",
        { target },
      ),
    );
  body.append(choices);
  function confirmIssue() {
    const view = modal("发放账号恢复码");
    view.append(
      el(
        "p",
        "请先核实「" +
          title +
          "」的身份。新码会取代旧码，使用后将退出原有会话并撤销接口令牌。",
      ),
      action("生成恢复码", () => issueCode(r, refresh), "primary"),
    );
  }
}
function editResource(section, r, refresh) {
  const name = textInput(r.name);
  if (section === "groups") {
    mutationForm(
      "修改团组名称",
      "instance.groupRename",
      () => ({ target: r.id, name: name.value.trim() }),
      [field("团组名称", name)],
      refresh,
    );
  } else {
    const color = input("color", r.color);
    mutationForm(
      "修改日历",
      "instance.calendarUpdate",
      () => ({ target: r.id, name: name.value.trim(), color: color.value }),
      [field("日历名称", name), field("默认颜色", color)],
      refresh,
    );
  }
}
function grantForm(section, target, existing, refresh) {
  const team = section === "members",
    roles = team
      ? ["member", "admin"]
      : ["reader", "contributor", "editor", "manager"];
  const role = select(
    options(existing ? [...roles, "remove"] : roles),
    existing?.role,
  );
  const holder = el("div"),
    kind = select(
      [
        ["user", "个人账号"],
        ["group", "团组"],
      ],
      existing?.kind || "user",
    );
  let pick;
  function change() {
    pick = selector(
      kind.value === "user" ? "accounts" : "groups",
      kind.value === "user" ? "name" : "id",
    );
    holder.replaceChildren(pick.box);
  }
  const fields = [];
  if (existing) {
    fields.push(el("p", existing.name));
  } else {
    if (!team) fields.push(field("授权对象类型", kind));
    change();
    kind.onchange = change;
    fields.push(holder);
  }
  fields.push(field("角色", role));
  mutationForm(
    team ? "维护团组成员" : "设置日历授权",
    team ? "instance.groupMember" : "instance.calendarGrant",
    () => ({
      target,
      subject: existing
        ? existing.kind === "group"
          ? existing.id
          : existing.name
        : pick.choice.value,
      role: role.value,
      ...(team ? {} : { subjectKind: existing?.kind || kind.value }),
    }),
    fields,
    refresh,
  );
}
function revoke(section, r, refresh) {
  const [op, title] = {
    shares: ["instance.unshare", "关闭只读链接"],
    invites: ["instance.uninvite", "撤销邀请"],
    tokens: ["instance.revokeToken", "撤销令牌"],
  }[section];
  confirmChange(
    title,
    "「" + r.name + "」的这项访问凭据会立即失效。",
    async () => {
      await write(op, {
        target: r.id,
        ...(section === "shares" ? { revision: r.revision } : {}),
      });
      await refresh();
    },
  );
}
function row(section, r, refresh, target) {
  const primary = (text, sub) =>
    node("div", "cell-title", el("strong", text), el("small", sub));
  switch (section) {
    case "calendarOwners": {
      const route = "resources/owners/" + r.kind + "/" + encodeURIComponent(r.id);
      return [link(r.name, route), tag(r.kind === "group" ? "团组" : "个人账号"), String(r.calendars), link("查看日历", route, "small-btn")];
    }
    case "accounts":
      return [
        primary(r.name, r.id),
        tag(r.enabled ? "已启用" : "已停用", r.enabled ? "green" : "neutral"),
        String(r.sessions),
        tag(r.ai ? "已授权" : "未授权", r.ai ? "purple" : "neutral"),
        action("管理", () => userActions(r, refresh)),
      ];
    case "groups":
    case "calendars": {
      const group = section === "groups";
      const go = link(
        group ? "管理成员" : "管理授权",
        "resources/" + section + "/" + encodeURIComponent(r.id),
      );
      go.className = "small-btn";
      return [
        primary(r.name, r.id),
        ...(group
          ? [String(r.members), String(r.calendars)]
          : [
              r.owner,
              tag(r.kind === "group" ? "团组" : "个人"),
              tag(
                r.public ? "已公开" : "未公开",
                r.public ? "green" : "neutral",
              ),
            ]),
        node(
          "div",
          "inline-actions",
          go,
          action("编辑", () => editResource(section, r, refresh)),
        ),
      ];
    }
    case "members":
    case "grants":
      return [
        primary(r.name, r.id),
        tag(roleName[r.role] || r.role),
        action("调整", () => grantForm(section, target, r, refresh)),
      ];
    case "shares":
      return [
        r.name,
        r.owner,
        tag("只读", "green"),
        action("关闭链接", () => revoke(section, r, refresh)),
      ];
    case "invites":
      return [
        r.name,
        tag({ instance: "实例", group: "团组", calendar: "日历" }[r.kind]),
        roleName[r.role],
        timeLabel(r.expires),
        action("撤销", () => revoke(section, r, refresh)),
      ];
    case "tokens":
      return [
        primary(r.name, r.calendar),
        r.owner,
        roleName[r.role],
        timeLabel(r.expires),
        action("撤销", () => revoke(section, r, refresh)),
      ];
  }
}
const headers = {
  calendarOwners: ["归属者", "类型", "日历数", "操作"],
  accounts: ["账号", "状态", "有效会话", "共用 AI", "操作"],
  calendars: ["日历", "归属", "类型", "只读分享", "操作"],
  groups: ["团组", "成员", "日历", "操作"],
  members: ["成员", "角色", "操作"],
  grants: ["对象", "角色", "操作"],
  shares: ["日历", "归属", "访问权限", "操作"],
  invites: ["邀请目标", "类型", "角色", "到期", "操作"],
  tokens: ["令牌 / 日历", "签发成员", "最高权限", "到期", "操作"],
};
export function renderDirectory(root, { section, target, ownerKind, tabs, route }) {
  let offset = 0,
    revision = 0,
    timer;
  const search = input("search");
  search.placeholder = section === "calendarOwners" ? "搜索日历名或归属者" : "按名称搜索";
  search.setAttribute("aria-label", "搜索" + labels[section]);
  search.maxLength = 128;
  const result = el("div"),
    pager = el("div", undefined, "pager");
  const head = node("div", "data-tools", search);
  if (tabs) {
    const tabset = node("div", "subnav");
    for (const t of tabs) {
      const a = link(labels[t], route + "/" + t);
      if (t === section || t === "calendars" && section === "calendarOwners") a.setAttribute("aria-current", "page");
      tabset.append(a);
    }
    root.append(tabset);
  }
  if (section === "members" || section === "grants") {
    head.prepend(
      link(
        "‹ 返回目录",
        "resources/" + (section === "members" ? "groups" : "calendars"),
      ),
    );
    head.append(
      action(
        section === "members" ? "添加成员" : "新增授权",
        () => grantForm(section, target, null, load),
        "primary",
      ),
    );
  } else if (["accounts", "invites"].includes(section)) {
    if (section === "accounts") head.append(action("添加账号", () => credentialForm(null, load), "primary"));
    head.append(action("发放注册码", () => issueCode(null, load), section === "invites" ? "primary" : ""));
  }
  else if (section === "calendarOwners")
    head.append(action("新建日历", () => createResource("calendars", load), "primary"));
  else if (ownerKind)
    head.prepend(link("‹ 返回日历归属", "resources/calendars"));
  else if (["groups", "calendars"].includes(section))
    head.append(
      action(
        "新建" + labels[section],
        () => createResource(section, load),
        "primary",
      ),
    );
  root.append(node("section", "panel directory-panel", head, result, pager));
  async function load() {
    const querySection = section === "calendarOwners" && search.value.trim() ? "calendars" : section;
    const rev = ++revision,
      response = await read("instance.directory", {
        section: querySection,
        offset,
        search: search.value,
        ...(target ? { target } : {}),
        ...(ownerKind ? { subjectKind: ownerKind } : {}),
      });
    if (rev !== revision || !root.isConnected) return;
    if (!response.items.length && offset) {
      offset = Math.max(0, offset - 25);
      return load();
    }
    result.replaceChildren();
    pager.replaceChildren();
    if (!response.items.length) {
      result.append(
        empty(search.value ? "没有匹配的记录" : "暂无" + labels[section]),
      );
      return;
    }
    const table = el("table"),
      thead = el("thead"),
      tr = el("tr"),
      tbody = el("tbody");
    if (ownerKind) result.append(el("h3", response.items[0].owner + "的日历"));
    for (const label of headers[querySection]) tr.append(el("th", label));
    thead.append(tr);
    for (const item of response.items) {
      const line = el("tr");
      for (const v of row(querySection, item, load, target)) {
        const td = el("td");
        td.append(v);
        line.append(td);
      }
      tbody.append(line);
    }
    table.append(thead, tbody);
    result.append(node("div", "table-scroll", table));
    const prev = action("上一页", () => {
        offset -= 25;
        return load();
      }),
      next = action("下一页", () => {
        offset += 25;
        return load();
      });
    prev.disabled = !offset;
    next.disabled = !response.more;
    pager.append(
      el(
        "small",
        "第 " + (offset + 1) + "–" + (offset + response.items.length) + " 项",
      ),
      prev,
      next,
    );
  }
  search.oninput = () => {
    revision++;
    offset = 0;
    clearTimeout(timer);
    timer = setTimeout(() => run(load), 180);
  };
  return load();
}
