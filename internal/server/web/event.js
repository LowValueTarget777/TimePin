import {
  $,
  el,
  action,
  command,
  uid,
  field,
  input,
  select,
  row,
  pair,
  form,
  modal,
  plus,
  zone,
  tint,
  message,
} from "./ui.js";
const parts = new Map();
const frequentZones = new Map([
  ["Asia/Shanghai", "中国标准时间"], ["Asia/Hong_Kong", "香港"],
  ["Asia/Taipei", "台北"], ["Asia/Tokyo", "东京"], ["Asia/Singapore", "新加坡"],
  ["Europe/London", "伦敦"], ["Europe/Paris", "巴黎"],
  ["America/New_York", "纽约"], ["America/Los_Angeles", "洛杉矶"],
  ["Australia/Sydney", "悉尼"], ["UTC", "协调世界时"],
]);
function timeZoneSelect(current) {
  const picker = select([]);
  const common = el("optgroup");
  common.label = "当前及常用时区";
  const preferred = new Set([current, zone, ...frequentZones.keys()]);
  for (const id of preferred) {
    const label = frequentZones.get(id);
    common.append(new Option(label ? label + " · " + id : id, id));
  }
  picker.append(common);
  const others = el("optgroup");
  others.label = "其他时区";
  for (const id of Intl.supportedValuesOf?.("timeZone") || [])
    if (!preferred.has(id)) others.append(new Option(id, id));
  if (others.childElementCount) picker.append(others);
  picker.value = current;
  return picker;
}
const eventPalette = [
  ["松绿", "#31866b"], ["湖蓝", "#337fb5"], ["靛蓝", "#5968b3"],
  ["紫藤", "#9064a9"], ["玫红", "#c75579"], ["珊瑚", "#d76443"],
  ["琥珀", "#e29328"], ["石板灰", "#697882"],
];
function eventColors(initial, calendarColor) {
  let value = initial || "";
  const box = el("fieldset", undefined, "event-colors");
  box.append(el("legend", "事件颜色"));
  const inherit = action("跟随日历", () => update(""), "inherit-color");
  const dot = el("span", undefined, "dot");
  inherit.prepend(dot);
  const colors = el("div", undefined, "color-options");
  const swatches = eventPalette.map(([name, hex]) => {
    const button = tint(action("", () => update(hex), "color-swatch"), hex);
    button.title = name;
    button.setAttribute("aria-label", name);
    colors.append(button);
    return button;
  });
  const custom = input("color");
  custom.oninput = custom.onchange = () => update(custom.value);
  const customField = field("自定义颜色", custom);
  customField.className = "custom-color";
  function update(next) {
    value = next.toLowerCase();
    tint(dot, calendarColor());
    custom.value = value || calendarColor();
    inherit.setAttribute("aria-pressed", String(!value));
    swatches.forEach((button, i) =>
      button.setAttribute("aria-pressed", String(eventPalette[i][1] === value)));
  }
  const controls = el("div", undefined, "color-controls");
  controls.append(inherit, customField);
  box.append(controls, colors);
  update(value);
  return { box, selected: () => value, refresh: () => update(value) };
}
function localStamp(instant, tz) {
  if (!parts.has(tz))
    parts.set(
      tz,
      new Intl.DateTimeFormat("sv-SE", {
        timeZone: tz,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hourCycle: "h23",
      }),
    );
  return parts.get(tz).format(instant).replace(" ", "T");
}
function toZoned(value, tz, original) {
  if (!value) throw Error("请填写起止时间");
  const wall = value.length === 16 ? value + ":00" : value;
  const nominal = Date.parse(wall + "Z");
  const offsets = new Set();
  if (original)
    offsets.add(
      (Date.parse(localStamp(new Date(original), tz) + "Z") -
        Date.parse(original)) /
        60000,
    );
  for (const h of [-36, 0, 36]) {
    const t = nominal + h * 3600000;
    offsets.add((Date.parse(localStamp(new Date(t), tz) + "Z") - t) / 60000);
  }
  for (const off of offsets) {
    const t = new Date(nominal - off * 60000);
    if (localStamp(t, tz) === wall) {
      const abs = Math.abs(off);
      return (
        wall +
        (off < 0 ? "-" : "+") +
        String(Math.floor(abs / 60)).padStart(2, "0") +
        ":" +
        String(abs % 60).padStart(2, "0")
      );
    }
  }
  throw Error("该时区不存在这个当地时刻，请调整时间");
}
export async function eventEditor(state, refresh, date, item, one = false) {
  const writable = state.calendars.filter((c) => c.role !== "reader"),
    cal = item ? state.calendars.find((c) => c.id === item.calendarId) : null;
  let record;
  if (item && !state.public) {
    record = (
      await command({
        op: "event.get",
        eventId: item.eventId,
        calendarId: item.calendarId,
      })
    ).record;
    if (record.deleted) throw Error("日程已删除");
  }
  const event = record?.event,
    editable = !item
      ? writable.length > 0
      : !state.public &&
        (cal?.role === "manager" ||
          cal?.role === "editor" ||
          (cal?.role === "contributor" && record.creatorId === state.user.id));
  const body = modal(
    !item
      ? "新建日程"
      : one
        ? "修改这一次"
        : editable
          ? "编辑日程"
          : "日程详情",
  );
  if (!editable) {
    if (!item) {
      body.append(el("p", "请先创建或选择可写入的日历"));
      return;
    }
    body.append(
      el("h3", item.title),
      el("p", (cal?.name || state.publicName) + " · " + item.on),
      el("p", item.span.start + " — " + item.span.end),
      el("p", item.span.zone || "全天", "subtle"),
    );
    return;
  }
  const span = one ? item.span : event?.span;
  const calSel = select(
    writable.map((c) => [c.id, c.name]),
    cal?.id || writable.find((c) => c.visible)?.id || writable[0].id,
  );
  calSel.disabled = !!item;
  const title = input("text", event?.title || "");
  title.required = true;
  title.maxLength = 512;
  title.disabled = one;
  const kind = select(
    [
      ["date", "全天"],
      ["timed", "指定时刻"],
    ],
    span?.kind || "date",
  );
  const tz = timeZoneSelect(span?.zone || zone);
  const start = input("date"),
    end = input("date");
  const renderDates = (initial) => {
    const timed = kind.value === "timed";
    start.type = end.type = timed ? "datetime-local" : "date";
    if (timed) {
      start.step = end.step = "1";
      start.value =
        initial && span?.kind === "timed"
          ? localStamp(new Date(span.start), tz.value)
          : date + "T09:00";
      end.value =
        initial && span?.kind === "timed"
          ? localStamp(new Date(span.end), tz.value)
          : date + "T10:00";
    } else {
      start.value = initial && span?.kind === "date" ? span.start : date;
      end.value = initial && span?.kind === "date" ? plus(span.end, -1) : date;
    }
    tz.disabled = !timed;
  };
  renderDates(true);
  kind.onchange = () => renderDates(false);
  start.required = end.required = true;
  const colors = eventColors(event?.color, () =>
    writable.find((c) => c.id === calSel.value).color);
  calSel.onchange = colors.refresh;
  const rep = select(
    [
      ["", "不重复"],
      ["daily", "每天"],
      ["weekly", "每周"],
      ["monthly", "每月"],
      ["yearly", "每年"],
    ],
    event?.repeat?.frequency || "",
  );
  const interval = input("number", event?.repeat?.interval || 1);
  interval.min = 1;
  interval.max = 999;
  const until = input("date", event?.repeat?.until || "");
  const repeatFields = pair(
    field("每隔几个周期", interval),
    field("截止日期（可选）", until),
  );
  repeatFields.hidden = !rep.value;
  rep.onchange = () => (repeatFields.hidden = !rep.value);
  const attendees = input("text", (event?.participants || []).join(", "));
  const eventId = record?.id || "e-" + uid();
  let previous = "",
    operationId = "";
  async function save(op, content) {
    const payload = { op, eventId, calendarId: calSel.value, ...content };
    const fingerprint = JSON.stringify(payload);
    if (previous !== fingerprint) {
      operationId = uid();
      previous = fingerprint;
    }
    await command({ ...payload, operationId });
    $("dialog").close();
    await refresh();
  }
  function exceptions(change) {
    return [
      ...(event.exceptions || []).filter((x) => x.on !== item.on),
      change,
    ];
  }
  const fields = [
    field("日程标题", title),
    pair(field("日历", calSel), field("类型", kind)),
    pair(field("开始", start), field("结束（全天含当日）", end)),
    field("时区", tz),
  ];
  if (!one)
    fields.push(
      colors.box,
      field("重复", rep),
      repeatFields,
      field("参与人编号（逗号分隔，可选）", attendees),
    );
  body.append(
    form(
      item ? "保存修改" : "创建日程",
      async () => {
        const value =
          kind.value === "date"
            ? { kind: "date", start: start.value, end: plus(end.value, 1) }
            : {
                kind: "timed",
                start: toZoned(start.value, tz.value, span?.start),
                end: toZoned(end.value, tz.value, span?.end),
                zone: tz.value,
              };
        let updated = one
          ? { ...event, exceptions: exceptions({ on: item.on, move: value }) }
          : { title: title.value.trim(), span: value };
        if (!one) {
          if (colors.selected()) updated.color = colors.selected();
          if (attendees.value.trim())
            updated.participants = attendees.value
              .split(/[,，]/)
              .map((x) => x.trim())
              .filter(Boolean);
          if (rep.value) {
            updated.repeat = {
              frequency: rep.value,
              interval: Number(interval.value),
            };
            if (until.value) updated.repeat.until = until.value;
            if (event?.exceptions?.length)
              updated.exceptions = event.exceptions;
          }
        }
        await save(item ? "event.replace" : "event.create", {
          event: updated,
          ...(record ? { baseVersion: record.version } : {}),
        });
      },
      ...fields,
    ),
  );
  if (item && !one) {
    const tools = row(
      action(
        event?.repeat ? "删除整个系列" : "删除日程",
        async () => {
          if (
            confirm(
              "确认删除" + (event.repeat ? "整个重复系列" : "此日程") + "？",
            )
          )
            await save("event.delete", { baseVersion: record.version });
        },
        "danger",
      ),
    );
    if (event.repeat) {
      tools.prepend(
        action("只改这一次", () =>
          eventEditor(state, refresh, date, item, true),
        ),
        action("取消这一次", async () => {
          if (confirm("取消选中的这一次日程？"))
            await save("event.replace", {
              baseVersion: record.version,
              event: {
                ...event,
                exceptions: exceptions({ on: item.on, cancel: true }),
              },
            });
        }),
      );
      body.prepend(
        el("p", "此日程按规则重复。下方表单修改整个系列。", "subtle"),
      );
    }
    body.append(tools);
  }
}
