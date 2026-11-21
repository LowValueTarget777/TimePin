import {
  $,
  el,
  action,
  api,
  command,
  manage,
  uid,
  field,
  form,
  input,
  select,
  row,
  pair,
  block,
  modal,
  linkBox,
  roles,
  message,
  run,
} from "./ui.js";
const calendarRoles = ["reader", "contributor", "editor", "manager"];
import { undoImports } from "./automation.js";
const groupRoles = ["member", "admin"];
const roleSelect = (names, selected) =>
  select(
    names.map((k) => [k, roles[k]]),
    selected,
  );
function textInput(value = "") {
  const n = input("text", value);
  n.required = true;
  n.maxLength = 80;
  return n;
}
function finish(refresh) {
  $("dialog").close();
  return refresh();
}
export function newCalendar(state, refresh) {
  const body = modal("新建日历"),
    name = textInput(),
    color = input("color", "#317c72"),
    team = select([
      ["", "我自己"],
      ...state.groups
        .filter((g) => g.role === "admin")
        .map((g) => [g.id, g.name]),
    ]);
  body.append(
    form(
      "创建日历",
      async () => {
        await manage("calendar.create", {
          name: name.value.trim(),
          color: color.value,
          team: team.value,
        });
        await finish(refresh);
      },
      field("日历名称", name),
      pair(field("归属", team), field("默认颜色", color)),
    ),
  );
}
export function newTeam(refresh) {
  const body = modal("新建团组"),
    name = textInput();
  body.append(
    form(
      "创建团组",
      async () => {
        await manage("group.create", { name: name.value.trim() });
        await finish(refresh);
      },
      field("团组名称", name),
    ),
  );
}
export async function settings(kind, item, state, refresh) {
  const body = modal(item.name),
    target = item.id;
  const detail = await manage(kind + ".detail", { target });
  const rerender = async () => {
    await refresh();
    const fresh = (kind === "group" ? state.groups : state.calendars).find(
      (c) => c.id === target,
    );
    if (!fresh || !["admin", "manager"].includes(fresh.role)) {
      $("dialog").close();
      return;
    }
    await settings(kind, fresh, state, refresh);
  };
  const change = async (op, args) => {
    await manage(kind + "." + op, { target, ...args });
    await rerender();
  };
  if (kind === "calendar") {
    const name = textInput(item.name),
      color = input("color", item.color);
    body.append(
      form(
        "保存日历设置",
        () => change("update", { name: name.value.trim(), color: color.value }),
        pair(field("名称", name), field("默认颜色", color)),
      ),
    );
  }
  const members = block(kind === "group" ? "团组成员" : "日历授权");
  body.append(members);
  if (!detail.members.length)
    members.append(
      el(
        "p",
        kind === "group"
          ? "暂无成员"
          : "尚未添加单独授权。日历所有者仍可管理。",
        "subtle",
      ),
    );
  for (const person of detail.members) {
    const n = el("div", undefined, "member");
    n.append(el("span", person.name + " · " + roles[person.role]));
    const subject = person.kind === "user" ? person.name : person.id;
    n.append(
      action("移除", async () => {
        if (confirm("移除 " + person.name + " 的这项权限？"))
          await change(kind === "group" ? "member" : "grant", {
            subject,
            subjectKind: person.kind,
            role: "remove",
          });
      }),
    );
    members.append(n);
  }
  const role = roleSelect(kind === "group" ? groupRoles : calendarRoles),
    subject = textInput(),
    type = select([
      ["user", "个人账号"],
      ["group", "我的团组"],
    ]),
    team = select(state.groups.map((g) => [g.id, g.name]));
  const targetField = field("账号名", subject),
    groupField = field("选择团组", team);
  groupField.hidden = true;
  type.onchange = () => {
    targetField.hidden = type.value === "group";
    subject.required = !targetField.hidden;
    groupField.hidden = !targetField.hidden;
  };
  const fields =
    kind === "group"
      ? [targetField, field("团组角色", role)]
      : [
          pair(field("授权对象", type), field("日历角色", role)),
          targetField,
          groupField,
        ];
  members.append(
    form(
      "添加或更新权限",
      () =>
        change(kind === "group" ? "member" : "grant", {
          subject:
            kind === "calendar" && type.value === "group"
              ? team.value
              : subject.value.trim(),
          subjectKind: type.value,
          role: role.value,
        }),
      ...fields,
    ),
  );
  if (kind === "group") {
    const successors = detail.members.filter((m) => m.id !== state.user.id);
    if (successors.length) {
      const to = select(successors.map((m) => [m.name, m.name]));
      body.append(
        block(
          "移交管理权",
          field("接任成员", to),
          action("移交并成为普通成员", async () => {
            if (confirm("将管理权交给 " + to.value + "？"))
              await change("transfer", { subject: to.value });
          }),
        ),
      );
    }
  }
  const inviteRole = roleSelect(kind === "group" ? groupRoles : calendarRoles);
  const invites = block("成员邀请", field("加入后的角色", inviteRole));
  body.append(invites);
  invites.append(
    action("创建一次性邀请", async () => {
      const result = await manage(kind + ".invite", {
        target,
        role: inviteRole.value,
      });
      await rerender();
      linkBox($("dialog-content"), "invite", result.token);
    }),
  );
  for (const inv of detail.invites) {
    const n = el("div", undefined, "member");
    n.append(
      el(
        "span",
        roles[inv.role] +
          " · " +
          new Date(inv.expires * 1000).toLocaleDateString() +
          " 到期",
      ),
      action("撤销邀请", () => change("uninvite", { inviteId: inv.id })),
    );
    invites.append(n);
  }
  if (kind === "calendar") {
    const shares = block("免登录只读链接");
    body.append(shares);
    if (detail.shareToken) {
      linkBox(shares, "share", detail.shareToken);
      shares.append(
        row(
          action("换新链接", () => change("share", { enabled: true })),
          action(
            "关闭只读链接",
            () => change("share", { enabled: false }),
            "danger",
          ),
        ),
      );
    } else
      shares.append(
        action("开启只读链接", () => change("share", { enabled: true })),
      );
    const owns = item.ownerKind === "person" ? item.ownerId === state.user.id :
      state.groups.some(g => g.id === item.ownerId && g.role === "admin");
    if (owns) {
      const remove = action("删除日历", () => {
        const section = modal("删除日历");
        const warning = el("p", "「" + item.name + "」和其中的全部日程将永久删除。所有成员会失去访问权限，邀请和只读链接也会失效。");
        const cancel = action("取消", () => settings(kind, item, state, refresh));
        const confirm = action("确认删除日历", async () => {
          await manage("calendar.delete", { target });
          await finish(refresh);
          message("已删除「" + item.name + "」");
        }, "danger");
        section.append(warning, row(cancel, confirm));
        cancel.focus();
      }, "danger");
      body.append(block("删除日历", remove));
    }
  }
}
export function account(state, refresh) {
  const body = modal("账号与安全"),
    old = input("password"),
    next = input("password");
  old.required = next.required = true;
  old.autocomplete = "current-password";
  next.autocomplete = "new-password";
  next.minLength = 10;
  body.append(
    el("p", state.user.name + " · " + state.user.id, "account-detail"),
    form(
      "修改密码",
      async () => {
        await api("password", { old: old.value, new: next.value });
        await finish(refresh);
        message("密码已更新，其他会话已退出");
      },
      field("当前密码", old),
      field("新密码（至少 10 个字符）", next),
    ),

  );
}
export function importEvents(state, refresh) {
  const body = modal("批量导入"),
    cal = select(
      state.calendars
        .filter((c) => c.role !== "reader")
        .map((c) => [c.id, c.name]),
    ),
    file = input("file"),
    area = el("textarea"),
    source = textInput("manual");
  file.accept = ".json,application/json";
  area.placeholder =
    '[{"event":{"title":"培训","span":{"kind":"date","start":"2026-11-05","end":"2026-11-06"}}}]';
  const output = el("div", undefined, "result"),
    commit = action(
      "确认导入",
      async () => {
        if (!prepared) return;
        const r = await command(prepared);
        await refresh();
        prepared = null;
        commit.hidden = true;
        show(r, "导入结果");
        message("已收到批次回执");
      },
      "primary",
    );
  commit.hidden = true;
  let prepared = null;
  const reset = () => {
    prepared = null;
    commit.hidden = true;
    output.replaceChildren();
  };
  for (const n of [cal, area, source]) n.oninput = reset;
  file.onchange = () =>
    run(async () => {
      reset();
      if (file.files.length) area.value = await file.files[0].text();
    });
  const labels = {
    created: "新增",
    updated: "更新",
    unchanged: "跳过",
    conflict: "版本冲突",
    forbidden: "权限不足",
  };
  const show = (r, title) => {
    output.replaceChildren(el("h3", title));
    const counts = {};
    for (const row of r.rows)
      counts[row.outcome] = (counts[row.outcome] || 0) + 1;
    output.append(
      el(
        "p",
        Object.entries(counts)
          .map(([k, v]) => labels[k] + " " + v)
          .join(" · "),
      ),
    );
    for (const x of r.rows)
      output.append(
        el(
          "p",
          "第 " +
            (x.index + 1) +
            " 行 · " +
            labels[x.outcome] +
            " · " +
            x.eventId,
          "subtle",
        ),
      );
    if (r.batchId) output.prepend(el("p", r.batchId, "subtle"));
  };
  body.append(
    field("目标日历", cal),
    field("来源编号", source),
    field("选择 JSON 文件", file),
    field("或粘贴结构化事件", area),
    action("查看批次与撤销",()=>undoImports(cal.value,refresh)),
    row(
      action("预览导入", async () => {
        reset();
        const value = JSON.parse(area.value);
        const batch = value.batch || value;
        const rows = Array.isArray(batch) ? batch : batch.rows;
        if (!Array.isArray(rows) || !rows.length)
          throw Error("需要包含事件的 rows 数组");
        const operationId = uid();
        const payload = {
          op: "import.preview",
          calendarId: cal.value,
          operationId,
          batch: { source: batch.source || source.value.trim(), rows },
        };
        const result = await command(payload);
        show(result, "预览结果");
        prepared = {
          ...payload,
          op: "import.commit",
          batch: {
            ...payload.batch,
            rows: rows.map((v, i) => ({
              ...v,
              baseVersion: v.baseVersion ?? result.rows[i].baseVersion,
            })),
          },
        };
        commit.hidden = false;
      }),
      commit,
    ),
    output,
  );
}
