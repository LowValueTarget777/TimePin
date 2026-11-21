// Both transports use UTF-8 SSE frames. Network chunks need not end on character or line boundaries.
async function events(response, consume) {
  const reader = response.body.getReader(), decoder = new TextDecoder();
  let buffer = "", kind = "message", data = [], size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) throw Error("回复连接中断，请重试");
      size += value.length;
      if (size > 4 * 1024 * 1024) throw Error("回复过长，请缩小问题范围");
      buffer += decoder.decode(value, { stream: true });
      let end;
      while ((end = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, end).replace(/\r$/, "");
        buffer = buffer.slice(end + 1);
        if (line === "") {
          if (data.length && consume(kind, data.join("\n"))) return;
          kind = "message";
          data = [];
        } else if (line.startsWith("event:")) kind = line.slice(6).trimStart();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export async function serverReply(response, update) {
  let result;
  await events(response, (kind, frame) => {
    const value = JSON.parse(frame);
    if (kind === "result") { result = value; return true; }
    if (kind === "error") throw Error(value);
    if (kind === "delta" || kind === "status") update(kind, value);
  });
  return result;
}

export async function modelReply(response, update) {
  if (!response.headers.get("content-type")?.startsWith("text/event-stream")) {
    const choice = (await response.json()).choices?.[0];
    if (choice?.finish_reason && !["stop", "tool_calls"].includes(choice.finish_reason))
      throw Error("模型回复不完整，请重试");
    return choice?.message;
  }
  const answer = { role: "assistant", content: "" }, tools = [];
  let finish;
  await events(response, (_, frame) => {
    if (frame === "[DONE]") {
      if (finish !== (tools.length ? "tool_calls" : "stop") ||
          tools.some(c => !c.id || c.type !== "function" || !c.function.name))
        throw Error("模型回复不完整，请重试");
      return true;
    }
    const chunk = JSON.parse(frame);
    if (chunk.error) throw Error("模型返回错误，请检查配置或重试");
    for (const c of chunk.choices || []) {
      if (c.index && c.index !== 0) continue;
      if (finish) throw Error("模型返回了无效的结束标记");
      finish = c.finish_reason;
      const d = c.delta || {};
      if (d.content) { answer.content += d.content; update("delta", d.content); }
      if (d.reasoning_content) answer.reasoning_content = (answer.reasoning_content || "") + d.reasoning_content;
      for (const part of d.tool_calls || []) {
        const i = part.index;
        if (!Number.isInteger(i) || i < 0 || i > tools.length || i >= 128)
          throw Error("无效的模型工具片段");
        const call = tools[i] ??= { id: "", type: "", function: { name: "", arguments: "" } };
        call.id += part.id || "";
        call.type += part.type || "";
        call.function.name += part.function?.name || "";
        call.function.arguments += part.function?.arguments || "";
      }
    }
  });
  if (tools.length) answer.tool_calls = tools;
  return answer;
}
