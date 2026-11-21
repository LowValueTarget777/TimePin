import { api } from "./ui.js";
import { action, confirmChange, el, empty, field, form, message, node, panel, tag, timeLabel } from "./console-ui.js";

const editions = { "org.timepin.app": "日常版", "org.timepin.app.community": "自签名社区版", "org.timepin.app.glass": "预览版", "org.timepin.app.qa": "验收版" };

export async function renderReleases(root) {
  const file = el("input");
  file.type = "file";
  file.accept = ".apk";
  file.required = true;
  const notes = el("textarea");
  notes.rows = 3;
  notes.placeholder = "本次更新了什么？";
  const publish = form("发布版本", async () => {
    const apk = file.files[0];
    if (!apk || apk.size > 64 * 1048576) throw Error("请选择 64 MiB 以内的 APK");
    const data = new FormData();
    data.append("notes", notes.value);
    data.append("apk", apk);
    const response = await fetch("/api/v1/admin/releases", { method: "POST", body: data });
    const r = await response.json();
    if (!response.ok) throw Error(r.error || "发布失败");
    message(r.published ? "已发布 " + r.versionName : "该版本已撤回，请提高版本号后发布");
    await renderReleases(root);
  }, field("安卓安装包", file), field("更新说明", notes));
  const rows = el("div");
  let offset = 0;
  const more = action("更多版本", load);
  async function load() {
    const result = await api("admin/releases?offset=" + offset);
    if (!result.releases.length && offset === 0) rows.append(empty("还没有发布版本。"));
    for (const r of result.releases) {
      const info = node("div", "audit-copy", el("strong", editions[r.packageName] + " " + r.versionName),
        el("small", "构建 " + r.versionCode + " · " + (r.bytes / 1048576).toFixed(1) + " MiB · " + timeLabel(r.created)));
      if (r.notes) info.append(el("p", r.notes));
      const line = node("div", "audit-line", info, tag(r.published ? "已发布" : "已撤回"));
      if (r.published) line.append(action("撤回", () => confirmChange("撤回版本",
        "停止分发 " + r.versionName + "。已安装的设备会继续使用当前版本。", async () => {
          await api("admin/releases/" + r.packageName + "/" + r.versionCode + "/withdraw", {});
          await renderReleases(root);
        })));
      rows.append(line);
    }
    offset += result.releases.length;
    more.hidden = !result.more;
  }
  more.hidden = true;
  root.replaceChildren(node("div", "settings-columns", panel("发布安卓版本", publish),
    panel("版本记录", node("div", "settings-stack", rows, more))));
  await load();
}
