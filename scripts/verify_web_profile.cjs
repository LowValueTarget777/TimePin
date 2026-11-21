async page => {
  const base = new URL(page.url());
  if (!["127.0.0.1", "localhost"].includes(base.hostname)) throw Error("请使用开放注册的回环测试实例");
  const login = "profile-" + Date.now();
  const password = "qa-profile-only-1234";
  const registered = await page.request.post(base.origin + "/api/v1/register", {data: {name: login, password}});
  if (!registered.ok()) throw Error("测试账号未注册");
  await page.goto(base.origin);
  const account = page.locator("#account");
  await account.click();
  await page.getByRole("button", {name:"编辑账号资料"}).click();
  await page.getByLabel("显示昵称").fill("晨班同学");
  await page.getByLabel("选择头像").setInputFiles("docs/android-month-widget.png");
  await page.locator(".profile-preview img").waitFor();
  await page.getByRole("button",{name:"保存资料"}).click();
  await account.locator("img").waitFor();
  await page.reload();
  await account.locator("img").waitFor();
  if (await account.innerText() !== "晨班同学") throw Error("账号昵称未持久化");
  await account.click();
  if (!(await page.locator("#account-profile").innerText()).includes("@"+login)) throw Error("缺少登录账号");
  await page.getByRole("button", {name:"编辑账号资料"}).click();
  await page.getByRole("button",{name:"使用文字头像"}).click();
  await page.getByRole("button",{name:"保存资料"}).click();
  await page.locator("#dialog").waitFor({state:"hidden"});
  await account.locator("img").waitFor({state:"detached"});
  return {nickname:"晨班同学",uploaded:true,removed:true};
}
