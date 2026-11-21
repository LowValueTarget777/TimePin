import { $, field, form, modal, select } from "./ui.js";

export const defaults = {
  weekStart: "sunday",
  weekNumbers: true,
  lunar: false,
  lunarFormat: "text",
  holidayRegion: "system",
  fontSize: 14,
};
const names = new Intl.DisplayNames(["zh-CN"], { type: "region" });
const chinese = new Intl.DateTimeFormat("zh-CN-u-ca-chinese", {
  month: "long",
  day: "numeric",
  timeZone: "UTC",
});
const numericChinese = new Intl.DateTimeFormat("zh-CN-u-ca-chinese", { month: "numeric", day: "numeric", timeZone: "UTC" });
const cache = new Map();
function data(key) {
  if (!cache.has(key))
    cache.set(
      key,
      fetch("/holidays/" + key + ".json")
        .then((r) => {
          if (!r.ok) throw Error("节假日数据暂时无法读取");
          return r.json();
        })
        .catch((e) => {
          cache.delete(key);
          throw e;
        }),
    );
  return cache.get(key);
}
export function systemRegion() {
  const tag = navigator.languages?.[0] || navigator.language;
  const override = /-rg-([a-z]{2})zzzz/i.exec(tag);
  return override?.[1].toUpperCase() || new Intl.Locale(tag).maximize().region;
}
export async function holidays(region) {
  if (region === "system") region = systemRegion();
  if (region === "off" || !(await data("regions")).includes(region)) return {};
  return data(region);
}
export function lunar(day, format = "text") {
  const parts = (format === "numeric" ? numericChinese : chinese).formatToParts(new Date(day + "T12:00:00Z"));
  const n = Number(parts.find((p) => p.type === "day").value);
  if (format === "numeric") return parts.find(p => p.type === "month").value + "/" + n;
  if (n === 1)
    return parts
      .find((p) => p.type === "month")
      .value.replace("十一月", "冬月")
      .replace("十二月", "腊月");
  return (
    { 10: "初十", 20: "二十", 30: "三十" }[n] ||
    ["初", "十", "廿"][Math.floor(n / 10)] + "〇一二三四五六七八九"[n % 10]
  );
}
export function weekNumber(start, sunday) {
  const thursday = new Date(start + "T12:00:00Z");
  thursday.setUTCDate(thursday.getUTCDate() + (sunday ? 4 : 3));
  return (
    Math.floor(
      (thursday - Date.UTC(thursday.getUTCFullYear(), 0, 1)) / 604800000,
    ) + 1
  );
}
export async function editDisplay(options, save) {
  const region = systemRegion(),
    codes = await data("regions");
  const regions = codes
    .map((code) => [code, names.of(code)])
    .sort((a, b) => a[1].localeCompare(b[1], "zh-CN"));
  const start = select(
      [
        ["sunday", "周日"],
        ["monday", "周一"],
      ],
      options.weekStart,
    ),
    holiday = select(
      [
        ["system", "跟随系统地区" + (region ? " · " + names.of(region) : "")],
        ["off", "关闭"],
        ...regions,
      ],
      options.holidayRegion,
    ),
    size = select(
      [12, 14, 16, 18].map((v, i) => [v, ["小", "标准", "大", "特大"][i]]),
      options.fontSize,
    );
  const toggle = (value) =>
    select(
      [
        ["on", "显示"],
        ["off", "隐藏"],
      ],
      value ? "on" : "off",
    );
  const weeks = toggle(options.weekNumbers),
    lun = select([["off", "关闭"], ["text", "中文 · 廿五"], ["numeric", "数字 · 11/25"]], options.lunar ? options.lunarFormat || "text" : "off");
  const body = modal("月历显示");
  body.append(
    form(
      "保存",
      async () => {
        await save({
          weekStart: start.value,
          weekNumbers: weeks.value === "on",
          lunar: lun.value !== "off",
          lunarFormat: lun.value === "numeric" ? "numeric" : "text",
          holidayRegion: holiday.value,
          fontSize: Number(size.value),
        });
        $("dialog").close();
      },
      field("每周第一天", start),
      field("节假日地区", holiday),
      field("农历", lun),
      field("周数", weeks),
      field("月历字号", size),
    ),
  );
}
