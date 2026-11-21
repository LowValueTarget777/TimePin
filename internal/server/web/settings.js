import { $, action, block, el, modal } from "./ui.js";
import { icon } from "./components.js";

export function showSettings(groups) {
  const body = $("settings-page");
  body.replaceChildren();
  const header = el("header", undefined, "settings-heading");
  const back = action("返回月历", () => {
    if (history.state?.settings) history.back();
    else location.assign("/");
  }, "settings-back");
  back.prepend(icon("back"));
  const heading = el("h1", "个人设置");
  heading.tabIndex = -1;
  header.append(back, heading);
  body.append(header);
  for (const [title, ...cards] of groups) {
    const section = el("section", undefined, "preference-section");
    const rows = el("div", undefined, "preference-rows");
    rows.append(...cards);
    section.append(el("h2", title), rows);
    body.append(section);
  }
}
export function pickCollection(state, open, createCal, createGroup) {
  const body = modal("日历与团组");
  const calendars = block("我的日历", action("新建日历", createCal));
  for (const c of state.calendars)
    calendars.append(
      c.role === "manager"
        ? action(c.name, () => open("calendar", c))
        : el("p", c.name),
    );
  const groups = block("我的团组", action("新建团组", createGroup));
  for (const g of state.groups)
    groups.append(
      g.role === "admin"
        ? action(g.name, () => open("group", g))
        : el("p", g.name),
    );
  body.append(calendars, groups);
}
