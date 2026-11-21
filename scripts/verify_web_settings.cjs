// Run with playwright-cli run-code --filename on a local TimePin Web tab.
async (tab) => {
  const origin = new URL(tab.url()).origin;
  const context = await tab.context().browser().newContext({ reducedMotion: "reduce" });
  const page = await context.newPage();
  const calendar = (id, name) => ({ id, name, ownerKind: "person", ownerId: "fixture", role: "manager", visible: true, color: "#fe9100" });
  const state = {
    user: { id: "fixture", name: "fixture" }, groups: [],
    calendars: [calendar("a", "calendar_displayname_xiaomi"), calendar("b", "家人"), calendar("c", "第三本")],
    ai: { enabled: false },
  };
  const display = { weekStart: "sunday", weekNumbers: true, lunar: false, holidayRegion: "off", fontSize: 14 };
  const prompts = [], scopes = [];
  let personalRead, createdEvent;
  let releaseReply;
  function assert(ok, reason) { if (!ok) throw Error(reason); }
  try {
    await page.route("**/api/v1/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname.split("/").pop();
      const payload = request.method() === "POST" ? request.postDataJSON() : {};
      let json;
      if (path === "state") json = state;
      else if (path === "tools") json = [{ type: "function", function: { name: "event_get" } }];
      else if (payload.op === "event.get") {
        personalRead = payload;
        json = { code: "ok", record: { id: "example", version: 1 } };
      }
      else if (payload.op === "calendar.detail") json = { members: [], invites: [] };
      else if (payload.op === "event.create") {
        createdEvent = payload;
        json = { code: "ok" };
      }
      else if (payload.op === "calendar.delete") {
        state.calendars = state.calendars.filter(c => c.id !== payload.target);
        json = { ok: true };
      }
      else if (path === "changes") json = { revision: "sample" };
      else if (path === "chat") {
        prompts.push(payload.message);
        scopes.push(payload.calendarIds || [payload.calendarId]);
        if (payload.message === "延迟回复") await new Promise(resolve => { releaseReply = resolve; });
        json = { text: "收到：" + payload.message };
      }
      else if (payload.op === "display.detail") json = display;
      else if (payload.op === "event.query") json = { code: "ok", occurrences: [] };
      else throw Error("Unexpected request: " + path);
      await route.fulfill({ json });
    });
    await page.addInitScript(() => {
      // This suite exercises OS light/dark switches; the appearance suite covers fresh defaults.
      localStorage.setItem("timepin.appearance.v1.fixture", JSON.stringify({ theme: "system", mode: "solid", color: "#DCE7F4" }));
      const native = window.fetch.bind(window);
      window.fetch = (url, options) => {
        if (!String(url).endsWith("/ai/chat") || JSON.parse(options.body).message !== "分段回复")
          return native(url, options);
        const encoder = new TextEncoder();
        const stream = new ReadableStream({
          start(c) {
            const first = encoder.encode('event: delta\r\ndata: "早到的文字"\r\n\r\n');
            c.enqueue(first.slice(0, 23));
            c.enqueue(first.slice(23));
            window.finishStream = () => {
              c.enqueue(encoder.encode('event: result\ndata: {"text":"早到的文字，回复完成"}\n\n'));
              c.close();
            };
            options.signal.addEventListener("abort", () => c.error(new DOMException("aborted", "AbortError")), { once: true });
          },
        });
        return Promise.resolve(new Response(stream, { headers: { "Content-Type": "text/event-stream" } }));
      };
    });
    await page.goto(origin);
    const lunarLabels = await page.evaluate(async () => {
      const { lunar } = await import("/display.js");
      return [lunar("2026-11-01","numeric"), lunar("2026-11-09","numeric"),
        lunar("2025-07-25","numeric"), lunar("2025-07-25")];
    });
    assert(lunarLabels.join("|") === "9/23|10/1|闰6/1|闰六月", "numeric and text lunar month boundaries");
    await page.getByRole("button", { name: "新建日程", exact: true }).click();
    const eventForm = page.getByRole("dialog");
    const kind = eventForm.getByLabel("类型", { exact: true });
    for (const calendar of state.calendars) {
      await eventForm.getByLabel("日历", { exact: true }).selectOption(calendar.id);
      assert(await kind.inputValue() === "date", "new event should be all-day in " + calendar.id);
    }
    await kind.selectOption("timed");
    assert(await eventForm.getByLabel("开始", { exact: true }).getAttribute("type") === "datetime-local", "timed event controls");
    await kind.selectOption("date");
    await eventForm.getByLabel("日程标题").fill("跨年全天");
    await eventForm.getByLabel("开始", { exact: true }).fill("2040-12-31");
    await eventForm.getByLabel("结束（全天含当日）").fill("2040-12-31");
    await eventForm.getByRole("button", { name: "创建日程" }).click();
    await eventForm.waitFor({ state: "hidden" });
    assert(createdEvent.calendarId === "c" && createdEvent.event.span.kind === "date"
      && createdEvent.event.span.start === "2040-12-31" && createdEvent.event.span.end === "2041-01-01", "one-day event crosses year");
    for (const width of [320, 390, 620, 768, 1366]) {
      await page.setViewportSize({ width, height: 800 });
      const boxes = page.getByRole("checkbox");
      await boxes.first().waitFor();
      const sizes = await boxes.evaluateAll(nodes => nodes.map(n => [n.offsetWidth, n.offsetHeight]));
      assert(sizes.length === 3 && sizes.every(([w, h]) => w === 18 && h === 18), "checkbox size at " + width);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "overflow at " + width);
      const heading = page.locator(".week-heading");
      assert(await heading.innerText() === "周", "week column heading");
      const trigger = page.getByRole("button", { name: "日历助手", exact: true });
      await trigger.click();
      const panel = await page.locator("dialog.assistant-drawer").boundingBox();
      assert(panel && Math.abs(panel.x + panel.width - width) < 2, "right-side drawer at " + width);
      await page.emulateMedia({ colorScheme: width === 390 || width === 768 ? "dark" : "light" });
      const arrow = page.getByRole("button", { name: "回车键行为" });
      await arrow.click();
      const menu = await page.getByRole("menu", { name: "回车键行为" }).boundingBox();
      const toggle = await arrow.boundingBox();
      assert(menu.width > 160 && menu.y + menu.height < toggle.y && menu.x >= 0, "menu layout at " + width);
      await page.keyboard.press("Escape");
      assert(await page.locator("dialog").evaluate(d => d.open), "menu Escape keeps drawer open");
      await page.keyboard.press("Escape");
    }
    await page.locator("#new-event").click();
    await page.getByRole("button", { name: "湖蓝", exact: true }).click();
    assert(await page.getByLabel("自定义颜色").inputValue() === "#337fb5", "preset color");
    await page.getByRole("button", { name: "跟随日历" }).click();
    assert(await page.getByRole("button", { name: "跟随日历" }).getAttribute("aria-pressed") === "true", "inherited color");
    await page.getByLabel("类型", { exact: true }).selectOption("timed");
    await page.getByRole("combobox", { name: "时区" }).selectOption("Asia/Shanghai");
    await page.locator("#dialog-close").click();
    await page.route("**/test-model/chat/completions", async route => {
      const req = route.request().postDataJSON();
      assert(req.stream === true, "personal model requested SSE");
      const last = req.messages.at(-1);
      const message = last.role === "tool" ? { role: "assistant", content: "已查到日程" } :
        { role: "assistant", tool_calls: [{ id: "lookup", type: "function",
          function: { name: "event_get", arguments: '{"eventId":"example"}' } }] };
      if (message.tool_calls) message.tool_calls[0].index = 0;
      const packet = JSON.stringify({ choices: [{ index: 0, delta: message, finish_reason: message.tool_calls ? "tool_calls" : "stop" }] });
      await route.fulfill({ contentType: "text/event-stream", body: "data: " + packet + "\r\n\r\ndata: [DONE]\r\n\r\n" });
    });
    await page.evaluate(() => localStorage.setItem("timepin.models.fixture", JSON.stringify({
      profiles: [{ id: "local", label: "个人测试", name: "fixture", url: location.origin + "/test-model" }],
      selected: "",
    })));
    await page.locator("#chat-open").click();
    const source = page.getByLabel("模型来源", { exact: true });
    assert(await source.inputValue() === "server", "legacy empty preference must use server");
    assert(await page.getByRole("button", { name: "发送", exact: true }).isDisabled(), "unavailable server must block sending");
    const scope = page.getByRole("group", { name: "对话日历" });
    const master = scope.getByRole("checkbox",{name:"全部日历"});
    assert(await master.isChecked() && await scope.locator("input:checked").count()===4, "all books selected initially");
    await source.selectOption("local");
    await master.uncheck();
    assert(await page.getByRole("button",{name:"发送",exact:true}).isDisabled(),"empty scope disables submit");
    await scope.getByRole("checkbox",{name:"calendar_displayname_xiaomi"}).check();
    await page.getByLabel("消息").fill("查询日程");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.getByText("已查到日程", { exact: true }).waitFor();
    assert(personalRead?.calendarId === "a" && !("operationId" in personalRead), "personal read command contract");
    await page.locator("#dialog-close").click();
    await page.goto(origin + "/?view=settings");
    const sourceCard = page.getByRole("button", { name: /模型来源/ });
    assert((await sourceCard.innerText()).includes("个人测试"), "explicit choice after reload");
    await sourceCard.click();
    await page.getByRole("radio", { name: /服务器模型/ }).check();
    await page.locator("#dialog-close").click();
    assert((await sourceCard.innerText()).includes("服务器模型"), "settings summary after switching");
    state.ai = { enabled: true, model: "instance-fixture" };
    await page.reload();
    assert((await sourceCard.innerText()).includes("instance-fixture"), "authorized model name");
    await page.getByRole("button", { name: "返回月历" }).click();
    await page.locator("#chat-open").click();
    assert(await source.inputValue() === "server", "settings and chat must agree");
    const submit = page.getByRole("button", { name: "发送", exact: true });
    const mode = page.getByRole("button", { name: "回车键行为" });
    async function checked(name) {
      await mode.click();
      const current = await page.getByRole("menuitemradio", { name }).getAttribute("aria-checked");
      await page.keyboard.press("Escape");
      return current === "true";
    }
    const editor = page.getByRole("textbox", { name: "消息" });
    assert(await submit.isEnabled() && await checked("回车换行"), "authorized chat defaults");
    await scope.getByRole("checkbox",{name:"第三本"}).uncheck();
    await editor.fill("第一行");
    await editor.press("Enter");
    await editor.press("B");
    assert(await editor.inputValue() === "第一行\nB" && prompts.length === 0, "Enter inserts a newline");
    await editor.press("Control+Enter");
    await page.getByText("收到：第一行\nB", { exact: true }).waitFor();
    assert(scopes[0].join(",")==="a,b","shared model uses every selected book");
    await mode.click();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await editor.fill("草稿");
    const submittedDuringIME = await editor.evaluate(n => {
      n.dispatchEvent(new CompositionEvent("compositionstart"));
      const key = () => new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true });
      const handled = !n.dispatchEvent(key());
      n.dispatchEvent(new CompositionEvent("compositionend"));
      const composingKey = new KeyboardEvent("keydown", { key: "Enter", isComposing: true, cancelable: true });
      return handled || !n.dispatchEvent(composingKey);
    });
    assert(!submittedDuringIME && prompts.length === 1, "IME Enter only confirms the candidate");
    await editor.press("Shift+Enter");
    assert(await editor.inputValue() === "草稿\n", "Shift+Enter keeps multiline input");
    await editor.fill("延迟回复");
    const inFlight = page.waitForRequest(request => new URL(request.url()).pathname === "/api/v1/ai/chat");
    await editor.press("Enter");
    await page.getByRole("button", { name: "发送中…" }).waitFor();
    await inFlight;
    await editor.press("Enter");
    assert(prompts.length === 2 && releaseReply, "one in-flight request");
    await page.mouse.click(12, 190);
    assert(!(await page.locator("dialog").evaluate(d => d.open)), "backdrop closes drawer");
    await page.locator("#chat-open").click();
    assert(await editor.inputValue() === "延迟回复" && await page.getByRole("button", { name: "停止生成" }).isVisible(), "in-flight conversation restored");
    await page.locator(".chat-log").click({ position: { x: 28, y: 12 } });
    assert(await page.locator("dialog").evaluate(d => d.open), "inside click keeps drawer open");
    releaseReply();
    await page.getByText("收到：延迟回复", { exact: true }).waitFor();
    await editor.fill("分段回复");
    await editor.press("Enter");
    await page.getByText("早到的文字", { exact: true }).waitFor();
    await page.getByRole("button", { name: "停止生成" }).click();
    await page.getByText("已停止接收回复").waitFor();
    await page.getByRole("button", { name: "重试" }).click();
    await page.getByText("早到的文字", { exact: true }).waitFor();
    await page.evaluate(() => window.finishStream());
    await page.getByText("早到的文字，回复完成", { exact: true }).waitFor();
    await editor.fill("   ");
    await editor.press("Enter");
    assert(prompts.length === 2, "ignore a blank message");
    await page.reload();
    await page.locator("#chat-open").click();
    assert(await checked("回车发送"), "account preference persists after reload");
    state.user = { id: "other", name: "other" };
    await page.reload();
    await page.locator("#chat-open").click();
    assert(await checked("回车换行"), "account preferences are isolated");
    await page.locator("#dialog-close").click();
    state.user = { id: "fixture", name: "fixture" };
    await page.reload();
    for (const width of [390, 1280]) {
      await page.setViewportSize({ width, height: 820 });
      if (width < 620) await page.locator("#mobile-tools").click();
      await page.locator(".manage-icon").first().click();
      const role = page.getByLabel("加入后的角色");
      await role.focus();
      const choose = await role.boundingBox(), invite = await page.getByRole("button", { name: "创建一次性邀请" }).boundingBox();
      assert(invite.y - choose.y - choose.height >= 8, "invite focus-ring clearance at " + width);
      await page.getByRole("button", { name: "删除日历", exact: true }).click();
      await page.getByRole("button", { name: "取消", exact: true }).click();
      await page.locator("#dialog-close").click();
    }
    await page.locator(".manage-icon").first().click();
    await page.getByRole("button", { name: "删除日历", exact: true }).click();
    await page.getByRole("button", { name: "确认删除日历", exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll(".rail-item input[type=checkbox]").length === 2);
    const dialog = page.locator("#dialog");
    const transitionTime = () => dialog.evaluate(d => parseFloat(getComputedStyle(d).transitionDuration));
    assert(await transitionTime() === 0, "reduced motion");
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.locator("#chat-open").click();
    assert(await transitionTime() > 0, "dialog has a motion-enabled transition");
    await page.locator("#dialog-close").click();
    return "五种宽度、两种主题、动态效果偏好、发送菜单与输入法、流式中止及重试、抽屉恢复、邀请间距和日历删除通过";
  } finally {
    await context.close();
  }
}
