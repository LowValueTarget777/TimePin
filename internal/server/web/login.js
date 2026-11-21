import {
  reveal,
  $,
  api,
  day,
  el,
  monthGrid,
  monthName,
  monthStart,
  message,
  run,
} from "./ui.js";
const invite = new URLSearchParams(location.hash.slice(1)).get("invite");
const targetQuery =
  new URLSearchParams(location.search).get("view") === "settings"
    ? "?view=settings"
    : "";
const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
let register = false,
  recovering = false;
function readInvite(value) {
  if (!value || !value.includes("#")) return value;
  return new URLSearchParams(value.split("#")[1]).get("invite") || value;
}
let signupAllowed = false;
function authMode() {
  reveal($("auth-form"));
  const f = $("auth-form");
  $("auth-title").textContent = register ? "创建账号" : "登录日历";
  $("auth-submit").textContent = register ? "创建账号" : "登录";
  $("auth-mode").textContent = register ? "已有账号，去登录" : "创建账号";
  $("recovery-field").hidden = !recovering;
  f.elements.recoveryCode.required = recovering;
  $("auth-recovery").hidden = register || recovering;
  if (recovering) {
    $("auth-title").textContent = "找回账号";
    $("auth-submit").textContent = "保存新密码";
    $("auth-mode").textContent = "返回登录";
    $("auth-hint").textContent = "填写账号名、你的新密码和管理员给你的恢复码。";
    $("invite-field").hidden = true;
    f.elements.invite.required = false;
    f.elements.password.autocomplete = "new-password";
    f.elements.password.minLength = 10;
    return;
  }
  const needsInvite = register && !signupAllowed && !invite;
  $("invite-field").hidden = !needsInvite;
  f.elements.invite.required = needsInvite;
  f.elements.password.autocomplete = register
    ? "new-password"
    : "current-password";
  f.elements.password.minLength = register ? 10 : 1;
  $("auth-hint").textContent = invite
    ? "登录或注册后即可接受这份邀请。"
    : register
      ? !signupAllowed && !invite
        ? "此服务器使用邀请注册，请填写收到的邀请链接或邀请码。"
        : "密码至少 10 个字符。"
      : "登录后查看你的个人与共享日历。";
}
function showAuth(can) {
  signupAllowed = can || !!invite;
  $("auth").hidden = false;
  authMode();
  const today = day(new Date());
  $("paper-month").textContent = monthName(today);
  const p = $("paper-grid");
  p.replaceChildren();
  weekdays.forEach((w) => p.append(el("span", w)));
  monthGrid(today).forEach((d) =>
    p.append(
      el(
        "span",
        monthStart(d) === monthStart(today) ? String(Number(d.slice(8))) : "",
        d === today ? "current-day" : "",
      ),
    ),
  );
}
$("auth-recovery").onclick = () => {
  recovering = true;
  register = false;
  $("auth-form").reset();
  authMode();
};
$("auth-mode").onclick = () => {
  if (recovering) {
    recovering = false;
    register = false;
    $("auth-form").reset();
  } else register = !register;
  authMode();
};
$("auth-form").onsubmit = (e) => {
  e.preventDefault();
  const f = e.currentTarget;
  run(async () => {
    const creds = {
      name: f.elements.name.value.trim(),
      password: f.elements.password.value,
    };
    if (recovering) {
      await api("recover", {
        ...creds,
        token: f.elements.recoveryCode.value.trim(),
      });
      f.reset();
      recovering = false;
      register = false;
      authMode();
      message("密码已更新，请重新登录");
      return;
    }
    const token =
      invite || (register ? readInvite(f.elements.invite.value.trim()) : "");
    if (register && token) creds.invite = token;
    await api(register ? "register" : "login", creds);
    // Registration already accepts the invite atomically. For login, the calendar accepts it on arrival.
    f.reset();
    location.replace(
      "/" + targetQuery + (!register && invite ? location.hash : ""),
    );
  }, $("auth-submit"));
};
run(async () => {
  const data = await api("state");
  if (data.user) {
    location.replace("/" + targetQuery + location.hash);
    return;
  }
  register =
    data.firstAccount ||
    new URLSearchParams(location.search).get("mode") === "register";
  showAuth(data.canRegister);
});
