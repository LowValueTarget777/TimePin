// Run against a disposable loopback TimePin instance with open registration.
async (tab) => {
  const url = new URL(tab.url());
  if (url.hostname !== "127.0.0.1") throw Error("Expected a loopback test instance");
  const context = await tab.context().browser().newContext();
  const inviter = await tab.context().browser().newContext();
  const page = await context.newPage();
  async function api(path, data) {
    const target = url.origin + "/api/v1/" + path;
    const res = data ? await context.request.post(target, { data }) : await context.request.get(target);
    if (!res.ok()) throw Error(path + " HTTP " + res.status());
    return res.json();
  }
  function check(ok, what) { if (!ok) throw Error(what); }
  try {
    await api("register", { name: "WebQA" + Date.now(), password: "isolated-actions-test" });
    const state = await api("state");
    const inviteAPI = url.origin + "/api/v1/";
    await inviter.request.post(inviteAPI+"register",{data:{name:"InviteQA"+Date.now(),password:"isolated-invite-test"}});
    const shared = (await (await inviter.request.get(inviteAPI+"state")).json()).calendars[0].id;
    const invite = (await (await inviter.request.post(inviteAPI+"manage",{data:{
      op:"calendar.invite",target:shared,role:"reader",operationId:"invite"
    }})).json()).token;
    const cal = state.calendars[0].id;
    const event = title => ({ title, span: {kind:"date",start:"2026-11-20",end:"2026-11-21"} });
    const batch = {source:"qa", rows:[{externalId:"shift",event:event("早班")}]};
    const command = (op, args) => api("commands",{v:1,calendarId:cal,op,...args});
    for (let i=0; i<51; i++) {
      batch.rows[0].event.title = "排班 " + i;
      check((await command("import.commit",{operationId:"seed-"+i,batch})).rows.length===1,"seed batch");
    }
    const firstPage = await api("batches?calendarId="+cal);
    const before = firstPage.at(-1).seq;
    check(firstPage.length===50 && (await api("batches?calendarId="+cal+"&before="+before)).length===1, "seed pagination");
    const rows = [{externalId:"shift",event:event("改为中班")},{externalId:"second",event:event("新增夜班")}];
    let lastQuery, commit, batchId;
    page.on("request", r => {
      const u = new URL(r.url());
      if (u.pathname.endsWith("/batches")) lastQuery=u.searchParams.get("before");
      if (u.pathname.endsWith("/commands") && r.postDataJSON()?.op==="import.commit") commit=r.postDataJSON();
    });
    await page.addInitScript(({user, origin}) => localStorage.setItem("timepin.models."+user,JSON.stringify({
      selected:"qa",profiles:[{id:"qa",label:"测试模型",name:"test",url:origin+"/fixture"}]
    })), {user:state.user.id,origin:url.origin});
    await page.route("**/fixture/chat/completions", async route => {
      const { messages, tools }=route.request().postDataJSON();
      const prompt=messages.filter(m => m.role==="user").at(-1).content;
      let name,args;
      if (prompt==="查旧批次") { name="batches"; args={before}; }
      else if (prompt==="导入班次") { name="import_commit"; args={batch:{source:"qa",rows}}; }
      else if (prompt==="撤销导入") { name="import_undo"; args={batchId}; }
      else if (prompt==="建组") { name="group_create"; args={name:"网页排班组"}; }
      else if (prompt==="创建一本轮班日历") { name="calendar_create"; args={name:"轮班",color:"#278B77"}; }
      else if (prompt.includes("[本次邀请]")) {
        check(!JSON.stringify(messages).includes(invite),"invite secret reached the model");
        name="invite_accept"; args={};
      }
      else throw Error(prompt);
      check(tools.some(t => t.function.name===name), "missing "+name);
      const last=messages.at(-1);
      const message=last.role==="tool" ? {role:"assistant",content:"找到 "+JSON.parse(last.content).length+" 个旧批次"} :
        {role:"assistant",tool_calls:[{id:"qa",type:"function",function:{name,arguments:JSON.stringify(args)}}]};
      await route.fulfill({json:{choices:[{message}]}});
    });
    await page.goto(url.origin);
    await page.locator("#chat-open").click();
    const submit=page.getByRole("button",{name:"发送",exact:true});
    const send=async text=>{
      await page.getByRole("textbox",{name:"消息"}).fill(text);
      await submit.click();
    };
    await send("查旧批次");
    await page.getByText("找到 1 个旧批次").waitFor();
    check(lastQuery===String(before),"missing before cursor");
    await send("导入班次");
    await page.getByText("改为中班 · 更新",{exact:true}).waitFor();
    await page.getByText("新增夜班 · 新增",{exact:true}).waitFor();
    check(!commit,"wrote before confirmation");
    // A colleague changes one row between preview and confirmation.
    batch.rows[0].event.title="同事的调整";
    await command("import.commit",{operationId:"other-writer",batch});
    await page.getByRole("button",{name:"确认执行"}).click();
    await page.getByText(/冲突 1 · 新增 1/).waitFor();
    check(commit.batch.rows[0].baseVersion===51 && commit.batch.rows[1].baseVersion===0,"lost preview versions");
    batchId=(await api("batches?calendarId="+cal))[0].id;
    await send("撤销导入");
    await page.getByText("移除新增事件 1",{exact:true}).waitFor();
    await page.getByRole("button",{name:"确认执行"}).click();
    await page.getByRole("button",{name:"确认执行"}).waitFor({state:"detached"});
    const all=page.getByRole("checkbox",{name:"全部日历",exact:true});
    await all.uncheck();
    check(await submit.isDisabled(),"calendar send enabled with empty selection");
    const scope=page.getByLabel("操作范围");
    await page.getByRole("button",{name:"创建日历", exact:true}).click();
    check(await scope.inputValue()==="account","create calendar did not open account scope");
    await send("创建一本轮班日历");
    await page.getByRole("button",{name:"确认执行"}).waitFor();
    check(!(await api("state")).calendars.some(c=>c.name==="轮班"),"created calendar before confirmation");
    await page.getByRole("button",{name:"确认执行"}).click();
    await page.getByRole("dialog").getByLabel("轮班",{exact:true}).waitFor({state:"attached"});
    check((await api("state")).calendars.some(c=>c.name==="轮班"),"missing confirmed calendar");
    await send("建组");
    await page.getByRole("button",{name:"确认执行"}).waitFor();
    check((await api("state")).groups.length===0,"created group before confirmation");
    await page.getByRole("button",{name:"确认执行"}).click();
    await page.getByText("已执行账号操作").waitFor();
    check((await api("state")).groups.length===1,"group was not created");
    await scope.selectOption("calendar");
    check(await submit.isDisabled(),"scope switch lost the empty selection");
    await scope.selectOption("account");
    await send("接受邀请 "+url.origin+"/#invite="+invite);
    await page.getByRole("button",{name:"确认执行"}).click();
    await page.waitForFunction(async id => (await (await fetch("/api/v1/state")).json()).calendars.some(c=>c.id===id && c.role==="reader"),shared);
    check(await scope.inputValue()==="account","account mode lost after joining a calendar");
    await scope.selectOption("calendar");
    check(await submit.isDisabled(),"joining a calendar reset the cleared selection");
    return "批次跨页、逐行预览、并发保护、撤销、账号确认、加入共享日历和空选区通过";
  } finally { await context.close(); await inviter.close(); }
}
