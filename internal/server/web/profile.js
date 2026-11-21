import { $, action, el, field, form, input, manage, modal, run } from "./ui.js";

let picture;
function initials(name) { return Array.from(name.trim())[0]?.toUpperCase() || "·"; }
function avatar(name, data = "") {
  const node = el("span", initials(name), "profile-avatar");
  node.setAttribute("aria-hidden", "true");
  if (data) {
    const img = el("img");
    img.alt = "";
    img.src = "data:image/jpeg;base64," + data;
    node.replaceChildren(img);
  }
  return node;
}

export function renderAccount(state) {
  const name = state.profile?.displayName || state.user.name;
  const login = state.user.name;
  const summary = el("div", undefined, "profile-summary");
  summary.append(el("strong", name), el("small", "@" + login));
  const button = $("account"), header = $("account-profile");
  button.replaceChildren(avatar(name), el("span", name));
  header.replaceChildren(avatar(name), summary);
  const version = state.profile?.avatarVersion;
  if (!version) { picture = undefined; return; }
  const key = state.user.id + "/" + version;
  if (picture?.key !== key) {
    const request = manage("profile.detail").then(p => p.avatarVersion === version ? p.avatar : "");
    picture = { key, request };
  }
  picture.request.then(data => {
    if (picture?.key === key && data) {
      button.firstChild.replaceWith(avatar(name, data));
      header.firstChild.replaceWith(avatar(name, data));
    }
  }).catch(() => { if (picture?.key === key) picture = undefined; });
}

async function cropPhoto(file) {
  if (file.size > 16 * 1024 * 1024) throw Error("请选择 16 MiB 以内的图片");
  const url = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(Error("无法读取这张图片"));
    reader.readAsDataURL(file);
  });
  const img = new Image();
  img.src = url;
  await img.decode();
  const side = Math.min(img.naturalWidth, img.naturalHeight);
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 192;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "white";
  ctx.fillRect(0, 0, 192, 192);
  ctx.drawImage(img, (img.naturalWidth-side)/2, (img.naturalHeight-side)/2, side, side, 0, 0, 192, 192);
  return canvas.toDataURL("image/jpeg", .85).split(",")[1];
}

export async function editProfile(refresh) {
  const p = await manage("profile.detail");
  const body = modal("账号资料"), name = input("text", p.displayName), photo = input("file");
  name.maxLength = 40; name.required = true; name.autocomplete = "nickname";
  photo.accept = "image/*";
  let image, choice = 0;
  const preview = el("div", undefined, "profile-preview");
  const paint = () => preview.replaceChildren(avatar(name.value || p.loginName, image ?? p.avatar), el("span", "@" + p.loginName));
  paint();
  name.oninput = paint;
  const save = form("保存资料", async () => {
    await manage("profile.update", {revision: p.revision, displayName: name.value.trim(), avatar: image});
    $("dialog").close();
    await refresh();
  }, preview, field("显示昵称", name), field("选择头像", photo),
     action("使用文字头像", () => { choice++; image = ""; photo.value = ""; paint(); save.querySelector('[type=submit]').disabled=false; }));
  photo.onchange = () => run(async () => {
    const file = photo.files[0];
    if (!file) return;
    const id = ++choice, submit = save.querySelector('[type=submit]');
    submit.disabled = true;
    try { const data = await cropPhoto(file); if (choice === id) { image = data; paint(); } }
    finally { if (choice === id) submit.disabled = false; }
  });
  body.append(save);
}
