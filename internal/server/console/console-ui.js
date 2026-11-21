import {
  $,
  action,
  api,
  el,
  field,
  form,
  input,
  manageAt,
  message,
  modal,
  select,
  uid,
} from "./ui.js";
import { icon } from "./components.js";

export { $, action, el, field, form, input, message, modal, select, icon };
export const read = (op, args = {}) => api("admin/manage", { op, ...args });
export const write = (op, args = {}) => manageAt("admin/manage", op, args);
export const freshCode = () => uid() + uid();
export const roleName = {
  reader: "只读",
  contributor: "投稿",
  editor: "编辑",
  manager: "日历管理",
  admin: "团组管理",
  member: "成员",
  remove: "移除",
};
export const timeLabel = (ts) =>
  new Date(ts * 1000).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
export function node(tag, cls, ...children) {
  const n = el(tag, undefined, cls);
  n.append(...children);
  return n;
}
export function tag(text, tone = "neutral") {
  return el("span", text, "status-tag " + tone);
}
export function link(label, route, cls) {
  const a = el("a", label, cls);
  a.href = "#" + route;
  return a;
}
export function panel(title, content, more) {
  const head = node("header", "panel-head", el("h2", title));
  if (more) head.append(more);
  return node("section", "panel", head, content);
}
export function empty(text) {
  return el("p", text, "empty-state");
}
export function confirmChange(title, detail, fn) {
  const body = modal(title);
  body.append(
    el("p", detail),
    node(
      "div",
      "modal-actions",
      action("取消", () => $("dialog").close()),
      action(
        "确认" + title,
        async () => {
          await fn();
          $("dialog").close();
        },
        "primary",
      ),
    ),
  );
}
export function textInput(value = "") {
  const i = input("text", value);
  i.required = true;
  i.maxLength = 80;
  return i;
}
export function showCode(title, code, detail) {
  const body = modal(title),
    value = input("text", code);
  value.readOnly = true;
  value.autocomplete = "off";
  value.onclick = () => value.select();
  body.append(
    el("p", detail),
    field(title, value),
    action("复制", async () => {
      value.select();
      if (navigator.clipboard) await navigator.clipboard.writeText(code);
      else if (!document.execCommand("copy")) throw Error("请复制已选中的内容");
      message("已复制");
    }),
  );
}
export const auditLabels = {
  "instance.accountCreate": "添加成员账号",
  "instance.accountRename": "修改登录名",
  "instance.accountPassword": "重设成员密码",
  "instance.releasePublish": "发布安卓版本",
  "instance.releaseWithdraw": "撤回安卓版本",
  "instance.backupCreate": "创建数据库备份",
  "instance.invite": "发放注册码",
  "instance.recovery": "发放恢复码",
  "instance.cancelRecovery": "撤销恢复码",
  "instance.signout": "退出成员会话",
  "instance.uninvite": "撤销邀请",
  "instance.unshare": "关闭只读链接",
  "instance.revokeToken": "撤销接口令牌",
  "instance.groupCreate": "创建团组",
  "instance.groupRename": "修改团组名称",
  "instance.groupMember": "维护团组成员",
  "instance.calendarCreate": "创建日历",
  "instance.calendarUpdate": "修改日历",
  "instance.calendarGrant": "调整日历授权",
  "instance.account": "账号启停",
  "model.grant": "共用模型授权",
  "instance.model": "配置共用模型",
  "instance.registration": "修改注册策略",
};
export function auditList(entries) {
  if (!entries.length)
    return empty("还没有后台操作记录。下一次管理变更会出现在这里。");
  const list = el("ol", undefined, "audit-list");
  for (const e of entries) {
    const setting =
      e.action === "instance.registration"
        ? { on: "开放注册", off: "邀请注册" }[e.setting]
        : { on: "启用", off: "关闭", ...roleName }[e.setting];
    const label = [auditLabels[e.action] || e.action, setting]
      .filter(Boolean)
      .join(" · ");
    const context = [e.actor, e.target, e.subject].filter(Boolean).join(" / ");
    list.append(
      node(
        "li",
        "audit-line",
        node("div", "audit-copy", el("strong", label), el("small", context)),
        el("time", timeLabel(e.at)),
      ),
    );
  }
  return list;
}
