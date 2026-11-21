import {
  pickCollection,
  showSettings,
} from "./settings.js";
import { card, icon } from "./components.js";
import { useAppearance, appearanceName, editAppearance } from "./appearance.js";
import { editProfile, renderAccount } from "./profile.js";
import { modelSettings, modelSummary } from "./models.js";
import { accessSettings } from "./automation.js";
import {
  $,
  el,
  action,
  api,
  command,
  manage,
  uid,
  day,
  local,
  plus,
  monthStart,
  monthGrid,
  monthName,
  zone,
  tint,
  reveal,
  run,
  message,
  modal,
} from "./ui.js";
import {
  newCalendar,
  newTeam,
  settings,
  account,
  importEvents,
} from "./manage.js";
import { eventEditor } from "./event.js";
import { openChat } from "./automation.js";
import {
  defaults,
  holidays,
  lunar,
  weekNumber,
  editDisplay,
} from "./display.js";
let display = { ...defaults },
  holidayDates = {};
const initialHash = location.hash,
  params = new URLSearchParams(initialHash.slice(1)),
  share = params.get("share"),
  invite = params.get("invite");
const state = {
  user: null,
  calendars: [],
  groups: [],
  public: !!share,
  events: [],
};
let selected = day(new Date()),
  month = monthStart(selected),
  mode = "month",
  revision = 0;
const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
const phone = matchMedia("(max-width: 620px)");
const viewDates = () => monthGrid(month, display.weekStart === "sunday");
const clock = new Intl.DateTimeFormat("zh-CN", {
  timeZone: zone,
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});
function labelTime(i) {
  return i.span.kind === "date"
    ? "全天"
    : clock.format(new Date(i.span.start)) +
        " – " +
        clock.format(new Date(i.span.end));
}
function occurs(i, d) {
  return i.startDay <= d && i.endDay >= d;
}
function stamp(i) {
  if (i.span.kind === "date") {
    i.startDay = i.span.start;
    i.endDay = plus(i.span.end, -1);
  } else {
    i.startDay = day(new Date(i.span.start));
    i.endDay = day(new Date(Date.parse(i.span.end) - 1));
  }
  return i;
}
function edit(item) {
  return eventEditor(state, refresh, selected, item);
}
function eventLine(i) {
  const n = action("", () => edit(i), "event-line");
  n.append(
    tint(el("span", undefined, "dot"), i.color),
    el("span", labelTime(i), "time"),
    el("b", i.title),
  );
  return n;
}
function pick(d) {
  if (selected === d) return;
  selected = d;
  renderCalendar();
  reveal($("day-panel"));
}
let lastRenderView;
function renderCalendar() {
  $("month-title").textContent = monthName(month);
  $("month-view").classList.toggle("active", mode === "month");
  $("list-view").classList.toggle("active", mode === "list");
  $("month-view").setAttribute("aria-pressed", String(mode === "month"));
  $("list-view").setAttribute("aria-pressed", String(mode === "list"));
  const box = $("calendar-body");
  const viewKey = month + "/" + mode;
  if (lastRenderView && lastRenderView !== viewKey) reveal(box);
  lastRenderView = viewKey;
  box.replaceChildren();
  const dates = viewDates();
  if (mode === "month") {
    const grid = el("div", undefined, "month-grid");
    grid.classList.toggle("with-weeks", display.weekNumbers);
    grid.style.setProperty("--month-size", display.fontSize + "px");
    if (display.weekNumbers) {
      const heading = el("span", "周", "week-heading");
      heading.title = "周数";
      grid.append(heading);
    }
    const sunday = display.weekStart === "sunday";
    const headers = sunday ? ["日", ...weekdays.slice(0, 6)] : weekdays;
    headers.forEach((w) => grid.append(el("div", "周" + w, "weekday")));
    for (const d of dates) {
      if (display.weekNumbers && local(d).getDay() === (sunday ? 0 : 1)) {
        const number = weekNumber(d, sunday);
        const label = el("div", undefined, "week-label");
        label.append(el("span", String(number)));
        label.title = "第 " + number + " 周";
        label.setAttribute("aria-label", label.title);
        const today = day(new Date());
        label.classList.toggle("this-week", d <= today && today <= plus(d, 6));
        grid.append(label);
      }
      const cell = el(
        "div",
        undefined,
        "cell" +
          (d.slice(0, 7) !== month.slice(0, 7) ? " outside" : "") +
          (d === selected ? " selected" : ""),
      );
      const num = action(
        String(Number(d.slice(8))),
        () => pick(d),
        "day-number" + (d === day(new Date()) ? " current-day" : ""),
      );
      num.setAttribute("aria-label", d);
      num.setAttribute("aria-pressed", String(d === selected));
      cell.onclick = (e) => {
        if (e.target === cell) pick(d);
      };
      cell.classList.toggle("sunday", local(d).getDay() === 0);
      cell.append(num);
      const note = [holidayDates[d], display.lunar ? lunar(d, display.lunarFormat) : ""]
        .filter(Boolean)
        .join(" · ");
      if (note) {
        const caption = el("span", note, "date-note");
        caption.title = note;
        caption.onclick = () => pick(d);
        cell.append(caption);
      }
      const matches = state.events.filter((i) => occurs(i, d));
      for (const i of matches.slice(0, 3)) {
        const when = labelTime(i);
        const n = tint(
          action(i.title, () => edit(i), "chip"),
          i.color,
        );
        if (phone.matches && i.span.kind === "timed") {
          const [start, end] = when
            .split(" – ")
            .map((t) => t.replace(/^0/, "").replace(/:00$/, ""));
          n.classList.add("shift-time");
          n.replaceChildren(el("span", start), el("span", "–" + end));
        }
        n.title = i.title + " · " + when;
        n.setAttribute("aria-label", n.title);
        cell.append(n);
      }
      if (matches.length > 3)
        cell.append(
          action("+" + (matches.length - 3) + " 项", () => pick(d), "more"),
        );
      grid.append(cell);
    }
    box.append(grid);
  } else {
    const list = el("div", undefined, "agenda");
    let count = 0;
    for (const d of dates.filter((d) => monthStart(d) === month)) {
      const events = state.events.filter((i) => occurs(i, d));
      if (events.length) {
        list.append(el("h3", d));
        events.forEach((i) => list.append(eventLine(i)));
        count += events.length;
      }
    }
    if (!count) list.append(el("p", "本月暂无日程", "empty"));
    box.append(list);
  }
  $("day-title").textContent = [
    selected + " · 周" + weekdays[(local(selected).getDay() + 6) % 7],
    holidayDates[selected],
    display.lunar ? lunar(selected, display.lunarFormat) : "",
  ]
    .filter(Boolean)
    .join(" · ");
  const section = $("day-events");
  section.replaceChildren();
  const items = state.events.filter((i) => occurs(i, selected));
  if (!items.length) section.append(el("p", "这一天暂无日程", "empty"));
  items.forEach((i) => section.append(eventLine(i)));
}
async function loadEvents() {
  const rev = ++revision,
    dates = viewDates(),
    window = { start: dates[0], end: plus(dates.at(-1), 1), zone };
  let values = [];
  if (share) {
    const p = await api(
      "public/" + encodeURIComponent(share) + "?" + new URLSearchParams(window),
    );
    if (p.result.code !== "ok") throw Error(p.result.detail);
    state.publicName = p.name;
    $("calendar-list").textContent = p.name;
    values = p.result.occurrences;
  } else {
    const visible = state.calendars.filter((c) => c.visible);
    const batches = await Promise.all(
      visible.map(async (c) =>
        (
          await command({ op: "event.query", calendarId: c.id, window })
        ).occurrences.map((i) => ({ ...i, calendarId: c.id })),
      ),
    );
    values = batches.flat();
  }
  const notes = await holidays(display.holidayRegion);
  if (rev !== revision) return;
  holidayDates = notes;
  state.events = values
    .map(stamp)
    .sort(
      (a, b) =>
        a.startDay.localeCompare(b.startDay) ||
        a.span.kind.localeCompare(b.span.kind) ||
        (a.span.kind === "timed"
          ? Date.parse(a.span.start) - Date.parse(b.span.start)
          : 0) ||
        a.title.localeCompare(b.title),
    );
  renderCalendar();
}
function rail() {
  const list = $("calendar-list");
  list.replaceChildren();
  for (const c of state.calendars) {
    const n = el("div", undefined, "rail-item"),
      l = el("label"),
      check = document.createElement("input");
    check.type = "checkbox";
    check.checked = c.visible;
    tint(check, c.color);
    check.onchange = () =>
      run(async () => {
        try {
          check.disabled = true;
          await manage("calendar.visible", {
            target: c.id,
            enabled: check.checked,
          });
          c.visible = check.checked;
          await loadEvents();
          reveal($("calendar-body"));
        } catch (e) {
          await refresh();
          throw e;
        } finally {
          check.disabled = false;
        }
      });
    const name = el("span", c.name, "calendar-name");
    name.title = c.name;
    l.append(check, name);
    n.append(l);
    if (c.role === "manager") {
      const b = action(
        "···",
        () => settings("calendar", c, state, refresh),
        "manage-icon",
      );
      b.setAttribute("aria-label", "管理日历 " + c.name);
      n.append(b);
    }
    list.append(n);
  }
  const teams = $("team-list");
  teams.replaceChildren();
  for (const g of state.groups) {
    const n =
      g.role === "admin"
        ? action(g.name, () => settings("group", g, state, refresh), "team-btn")
        : el("span", g.name, "team-btn");
    teams.append(n);
  }
  $("new-event").disabled =
    $("day-add").disabled =
      !state.calendars.some((c) => c.role !== "reader");
  renderAccount(state);
}
async function refresh(initialState) {
  if (!share) {
    const data = initialState ?? (await api("state"));
    if (!data.user) {
      location.replace("/login.html" + location.search + location.hash);
      return;
    }
    display = await manage("display.detail");
    Object.assign(state, data);
    useAppearance(state.user.id);
    rail();
  }
  $("workspace").hidden = false;
  if (!share) startUpdates();
  if (!share && settingsRoute()) renderSettings();
  else {
    closeSettings();
    await loadEvents();
  }
}
function finishInvite() {
  history.replaceState(null, "", location.pathname);
  location.reload();
}
window.addEventListener("hashchange", () => location.reload());
function bind(id, fn) {
  $(id).onclick = () => run(fn);
}
bind("prev", () => move(-1));
bind("next", () => move(1));
function move(n) {
  const d = local(month);
  d.setMonth(d.getMonth() + n);
  month = day(d);
  selected = month;
  return loadEvents();
}
bind("today", () => {
  selected = day(new Date());
  month = monthStart(selected);
  return loadEvents();
});
bind("month-view", () => {
  setMobileTools(false);
  mode = "month";
  renderCalendar();
});
bind("list-view", () => {
  setMobileTools(false);
  mode = "list";
  renderCalendar();
});
function setMobileTools(open) {
  setAccountMenu(false);
  $("workspace").classList.toggle("tools-open", open);
  $("mobile-tools").setAttribute("aria-expanded", String(open));
}
bind("mobile-tools", () =>
  setMobileTools(!$("workspace").classList.contains("tools-open")),
);
phone.addEventListener("change", () => {
  setMobileTools(false);
  if (!$("workspace").hidden) run(loadEvents);
});
bind("new-calendar", () => newCalendar(state, refresh));
bind("new-group", () => newTeam(refresh));
bind("new-event", () => edit());
bind("day-add", () => edit());
const calendarDisplay = () =>
  editDisplay(display, async (value) => {
    if (share)
      localStorage.setItem("timepin.public.month", JSON.stringify(value));
    else await manage("display.update", { display: value });
    display = value;
    if (settingsRoute() && !share) renderSettings();
    else await loadEvents();
  });
let observed = "",
  timer,
  checking = false;
function startUpdates() {
  if (!timer && !document.hidden)
    timer = setInterval(() => run(checkChanges), 30000);
}
async function checkChanges() {
  if (checking || document.hidden || $("dialog").open || share || !state.user)
    return;
  checking = true;
  try {
    const actual = await api("changes");
    if (actual.revision !== observed) {
      observed = actual.revision;
      await refresh();
    }
  } finally {
    checking = false;
  }
}
function settingsRoute() {
  return new URLSearchParams(location.search).get("view") === "settings";
}
function closeSettings() {
  if (!$("settings-page").hidden) reveal($("calendar-body"));
  $("settings-page").hidden = true;
  $("workspace").classList.remove("in-settings");
}
async function aboutTimePin() {
  // Older instances only distribute the official daily package.
  const communityLatest = api("releases/org.timepin.app.community/latest").catch(error => {
    if (error.status === 404) return { release: null };
    throw error;
  });
  const [instance, official, community] = await Promise.all([api("instance"), api("releases/org.timepin.app/latest"), communityLatest]);
  const body = modal("关于 TimePin");
  body.append(el("p", "服务器版本 " + instance.version), el("p", "作者  " + instance.author));
  if (!official.release && !community.release) body.append(el("p", "服务器尚未发布安卓安装包。"));
  for (const [result, title, prefix] of [[official, "下载安卓 ", "TimePin-"], [community, "下载安卓社区版 ", "TimePin-community-"]]) {
    const release = result.release;
    if (!release) continue;
    const link = el("a", title + release.versionName + "（" + (release.bytes / 1048576).toFixed(1) + " MiB）");
    link.href = "/api/v1/releases/" + release.packageName + "/" + release.versionCode + "/apk";
    link.download = prefix + release.versionName + ".apk";
    if (release.notes) body.append(el("p", release.notes));
    const row = el("p");
    row.append(link);
    body.append(row);
  }
}
function renderSettings() {
  $("settings-page").hidden = false;
  $("workspace").classList.add("in-settings");
  const sourceCard = card("model", "模型来源", modelSummary(state), () =>
    modelSettings(state, () => {
      sourceCard.querySelector("small").textContent = modelSummary(state);
    }));
  const themeCard = card("sun", "主题与背景", appearanceName(), () => editAppearance(() => themeCard.focus()));
  themeCard.id = "theme-settings";
  const groups = [
    ["外观",
      themeCard,
      card("calendar", "月历显示",
        (display.weekStart === "sunday" ? "周日" : "周一") + "起始 · " +
        (display.weekNumbers ? "显示周数" : "隐藏周数") + " · " + display.fontSize + " px",
        calendarDisplay)],
    ["日历与协作",
      card("people", "日历与团组", state.calendars.length + " 本日历 · " + state.groups.length + " 个团组", () =>
        pickCollection(state, (k, i) => settings(k, i, state, refresh),
          () => newCalendar(state, refresh), () => newTeam(refresh)),
      ),
    ],
    ["助手与导入", sourceCard,
      card("sync", "批量导入", "预览并导入 JSON 日程", () => importEvents(state, refresh)),
    ],
    ["应用信息", card("info", "关于 TimePin", "版本、作者与安卓下载", aboutTimePin)],
    ["账号与访问",
      card("account", "账号资料", state.profile?.displayName || state.user.name, () => editProfile(refresh)),
      card("key", "账号与安全", state.user.name, () => account(state, refresh)),
      card("key", "接口令牌", "管理 CLI、机器人和外部工具的访问", () => accessSettings(state)),
    ],
  ];
  showSettings(groups);
}
window.addEventListener("timepin-appearance", () => {
  const summary = document.querySelector("#theme-settings small");
  if (summary) summary.textContent = appearanceName();
});
function openSettings() {
  if (share) return calendarDisplay();
  if (!settingsRoute())
    history.pushState({ settings: true }, "", "?view=settings");
  renderSettings();
  $("settings-page").querySelector("h1").focus();
}
$("display-open").append(icon("settings"));
$("display-open").setAttribute("aria-label", share ? "月历显示设置" : "设置");
$("display-open").title = share ? "月历显示设置" : "设置";
bind("display-open", openSettings);
window.addEventListener("popstate", () => {
  // Hash changes reload into a different sharing or invitation context.
  if (state.user && location.hash === initialHash) run(refresh);
});
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    clearInterval(timer);
    timer = null;
  } else if (!share && !$("dialog").open) run(refresh);
});
window.addEventListener("languagechange", () => {
  if (!$("workspace").hidden) run(loadEvents);
});
function setAccountMenu(open) {
  $("account-menu").hidden = !open;
  $("account").setAttribute("aria-expanded", String(open));
}
bind("account", () => setAccountMenu($("account-menu").hidden));
bind("account-profile", () => { setAccountMenu(false); return editProfile(refresh); });
bind("account-settings", () => { setAccountMenu(false); openSettings(); });
bind("account-logout", async () => {
  await api("logout", {});
  location.replace("/login.html");
});
document.addEventListener("click", (event) => {
  if (!event.target.closest(".rail-bottom")) setAccountMenu(false);
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !$("account-menu").hidden) {
    setAccountMenu(false);
    $("account").focus();
  }
});
for (const id of ["chat-open", "mobile-chat"]) {
  $(id).prepend(icon("model"));
  bind(id, () => { setAccountMenu(false); openChat(state, refresh); });
}

const dialog = $("dialog");
$("dialog-close").onclick = () => dialog.close();
function onDrawerBackdrop(event) {
  if (event.target !== dialog || !dialog.classList.contains("assistant-drawer")) return false;
  const rect = dialog.getBoundingClientRect();
  return event.clientX < rect.left || event.clientX >= rect.right ||
    event.clientY < rect.top || event.clientY >= rect.bottom;
}
let backdropPointer = null;
dialog.addEventListener("pointerdown", event => {
  backdropPointer = event.isPrimary && event.button === 0 && onDrawerBackdrop(event) ? event.pointerId : null;
});
dialog.addEventListener("click", event => {
  if (backdropPointer !== null && onDrawerBackdrop(event)) dialog.close();
  backdropPointer = null;
});
dialog.addEventListener("pointercancel", () => { backdropPointer = null; });
run(async () => {
  if (share) {
    try {
      Object.assign(
        display,
        JSON.parse(localStorage.getItem("timepin.public.month")),
      );
    } catch {}
    document.body.classList.add("public");
    $("view-label").textContent = "只读共享日历";
    $("view-label").hidden = false;
    try {
      await refresh();
    } catch (e) {
      $("workspace").hidden = true;
      const error = el("main", undefined, "preferences");
      error.append(el("h1", "共享链接无法打开"), el("p", e.message));
      document.body.prepend(error);
    }
    return;
  }
  const data = await api("state");
  if (data.user && invite) {
    await manage("invite.accept", { token: invite });
    finishInvite();
    return;
  }
  await refresh(data);
});
