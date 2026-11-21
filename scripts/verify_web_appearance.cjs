// Run against a disposable loopback server with open registration.
async (tab) => {
  const url = new URL(tab.url());
  if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) throw Error("需要本机专用验收实例");
  const context = await tab.context().browser().newContext({ colorScheme: "dark", viewport: { width: 1100, height: 800 } });
  const page = await context.newPage(), origin = url.origin;
  const check = (ok, message) => { if (!ok) throw Error(message); };
  const role = (name) => page.getByRole("button", { name });
  const edit = () => role(/主题与背景/).click();
  const save = () => role("保存外观").click();
  const color = page.getByLabel("十六进制颜色"), theme = page.getByLabel("主题样式");
  const root = page.locator("html");
  const scheme = () => root.evaluate(n => getComputedStyle(n).colorScheme);
  const register = async (suffix) => {
    const res = await context.request.post(origin + "/api/v1/register", {
      data: { name: "appearance-" + Date.now() + suffix, password: "test-" + Date.now() }
    });
    check(res.ok(), "注册临时账号 " + res.status());
    await page.goto(origin + "/?view=settings");
    await role(/主题与背景/).waitFor();
  };
  try {
    await register("a");
    await edit();
    check(await color.inputValue() === "#DCE7F4" && await scheme() === "light", "深色系统下默认冰蓝雾光");
    const red = page.getByRole("slider", { name: "红色" }), rect = await red.boundingBox();
    await page.mouse.move(rect.x + rect.width * .86, rect.y + rect.height / 2);
    await page.mouse.down();
    await page.mouse.move(rect.x + rect.width * .2, rect.y + rect.height / 2, {steps: 10});
    await page.mouse.up();
    const selected = await color.inputValue();
    check(/^#[0-9A-F]{2}E7F4$/.test(selected) && selected !== "#DCE7F4", "拖动单一颜色通道");
    await save(); await page.reload(); await edit();
    check(await color.inputValue() === selected, "自定义色已保存");
    await role("丁香").click(); await role("取消").click(); await edit();
    check(await color.inputValue() === selected, "取消保留原色");
    await color.fill("#oops"); check(await role("保存外观").isDisabled(), "无效色禁止保存");
    await role("冰蓝").click();
    for (const [value, expected] of [["dark","dark"],["light","light"],["system","dark"]]) {
      await theme.selectOption(value);
      await save();
      await page.waitForFunction(wanted => getComputedStyle(document.documentElement).colorScheme === wanted, expected);
      await edit();
    }
    await page.emulateMedia({ colorScheme: "light" });
    check(await scheme() === "light", "跟随系统热切换");
    await theme.selectOption("frost");
    await page.getByLabel("毛玻璃背景").selectOption("photo");
    check(await role("保存外观").isDisabled(), "等待选择照片");
    const bytes = await page.evaluate(() => {
      const canvas = document.createElement("canvas");
      canvas.width = 40; canvas.height = 20;
      const ctx = canvas.getContext("2d"); ctx.fillStyle = "#66aaff"; ctx.fillRect(0,0,40,20);
      return canvas.toDataURL("image/png").split(",")[1];
    });
    await page.getByLabel("从设备选择图片").setInputFiles({name:"sample.png", mimeType:"image/png", buffer:Buffer.from(bytes,"base64")});
    await save();
    await page.reload(); await role(/主题与背景/).waitFor();
    check((await page.locator("body").evaluate(n => getComputedStyle(n).backgroundImage)).includes("data:image/jpeg"), "照片回读");
    await page.setViewportSize({width:360,height:700});
    await edit(); await page.getByLabel("毛玻璃背景").selectOption("solid");
    check(await role("保存外观").isVisible() && await page.evaluate(() =>
      document.documentElement.scrollWidth <= innerWidth && document.querySelector("dialog").scrollWidth <= document.querySelector("dialog").clientWidth), "窄屏设置");
    await page.keyboard.press("Escape");
    await context.request.post(origin + "/api/v1/logout", {data:{}});
    await register("b");
    await edit();
    check(await color.inputValue() === "#DCE7F4" && await root.getAttribute("data-backdrop") === "solid", "账号外观隔离");
    await role("取消").click();
    const output = await page.evaluate(async () => {
      const mod = await import("/appearance.js");
      mod.updateAppearance({color:"#aabbdd"});
      return mod.getAppearance();
    });
    check(output.color === "#AABBDD" && !output.hasPhoto && (await role(/主题与背景/).innerText()).includes("#AABBDD"), "本机自动化更新和界面一致");
    return "通过：冰蓝默认、拖动、自定义色、取消、校验、深浅及跟随系统、图片、窄屏、账号隔离";
  } finally { await context.close(); }
}
