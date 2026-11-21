# 自动化、MCP 与实例共用模型

按实际操作查阅内置助手、外部 MCP 和安卓设备接口的覆盖范围，见[自动化能力审查](./automation-coverage.md)。

## 助手操作范围

Web 助手的「操作范围」可切换「所选日历」和「账号管理」。安卓助手还可选择「本机设备」。取消全选会禁止发送日历对话和设备对话。账号管理独立于日历选区，可在没有日历时创建第一本。

在账号管理中，可以输入“创建轮班组”“在轮班组里建一本绿色的班表”或粘贴日历或团组的一次性邀请。两种模型均先提出操作，用户确认后通过当前会话执行。客户端在发给模型的账号消息中遮盖邀请码，确认时才将原码交给实例。

在安卓本机设备对话中，可以说“桌面月历只显示排班，近期日程只显示家庭”（逐个组件确认）、“把排班发布到已登录的服务器”或“同步排班，查看我和同事改班的冲突”。未安装的组件需先加到桌面。调整组件时，选区须包含该组件现有及拟显示的日历。采用远端版本前会显示将放弃的本机草稿数；采用本机改班适用于唯一一项、远端仍存在的事件修改。新改动会使旧的冲突确认失效。[设备命令](../contract/v1/device.json)

Web 个人模型按实际游标翻查批次。提交和撤销均先取得逐项结果；导入确认保留预览时的基准版本。预览之后有人改班时，正式回执会列出冲突。[导入语义](../contract/v1/README.md)

## 连接实例

运行中的实例公开 `GET /api/v1/openapi.json` 和 `GET /api/v1/schema.json`。两份文件可一起离线使用。OpenAPI 描述 HTTP 方法、会话、令牌、账号、管理操作和模型接口；事件 Schema 定义窗口、版本、导入行和回执。[OpenAPI 文件](../contract/v1/openapi.json) · [事件 Schema](../contract/v1/schema.json) · [HTTP 权限说明](../contract/v1/web.md)

在网页打开「个人设置 → 账号与访问 → 接口令牌」，选日历和最高角色，创建令牌并保存到客户端的密钥配置中。每个令牌有效 90 天，可单独撤销。授权日历可选具体日历或「所有日历」。后者包含本人现在及以后可访问的日历，每次请求都会重新检查成员对目标日历的实际权限。例行班表修订可给机器人投稿角色，需要代改他人创建的班次时才授予编辑角色。服务端仅持久化自动化令牌的摘要。

## JSON CLI

安卓设备命令 `calendar.visibility` 接受 `calendarId,enabled,operationId`，控制本机日历可见性。`calendar.update` 接受 `calendarId,name,color,operationId`，本机日历在 Room 中更新，已连接日历复用服务器管理命令并刷新本机副本。两者均按目标日历执行。

使用登录会话运行 `timepinctl profile.detail` 获取本人资料和 `revision`。将该版本、唯一 `operationId` 和待改的 `displayName` 或 `avatar` 写入 JSON 后，执行 `timepinctl -f profile.json profile.update`。MCP 工具名为 `profile_detail`、`profile_update`。这些操作以日历账号本人身份执行。

需要维护本地副本的工具可以调用 `timepinctl -f pull.json sync.pull`。首次请求填写 `calendarId`，后续携带上次落库的 `cursor`。写入仍按原命令的操作号和版本提交。[同步协议与回包](../contract/v1/sync.md)

从仓库根目录运行 `go install ./cmd/timepinctl`，或用 `go build -o timepinctl ./cmd/timepinctl` 构建。Windows 输出名可设为 `timepinctl.exe`。容器内也提供 `/timepinctl`。配置环境变量 `TIMEPIN_URL` 为实例根地址，默认为 `http://127.0.0.1:8080`。使用已签发的 `TIMEPIN_TOKEN` 时，每次请求使用 Bearer 身份。

```sh
timepinctl openapi
timepinctl tools
timepinctl state
timepinctl -f preview.json import.preview
timepinctl -f commit.json import.commit
timepinctl -f undo.json import.undoPreview
timepinctl -f confirm-undo.json import.undo
```

命令接收一个 UTF-8 JSON 对象，文件或管道均可。读取标准输入也可显式传 `-f -`。成功输出 JSON 到 stdout；HTTP 拒绝或日历命令失败时保留服务端 JSON，退出码为 3。逐行冲突属于批次成功回执，自动化还需检查每个 `outcome`。本地参数或传输错误写入 stderr，退出码为 1。

命名命令自动补入 `op` 及事件协议的 `v:1`。也可向 `timepinctl commands` 或 `timepinctl manage` 传完整请求。登录个人日历可用 `timepinctl login` 从标准输入读取 `{"name":"账号名","password":"账号密码"}`。登录会话默认存入系统用户配置目录，也可设置 `TIMEPIN_SESSION_FILE` 为私有文件路径；文件与实例地址绑定。登录、注册和改密成功会保存新会话。账号会话可以操作本人获得授权的多本日历。使用 `timepinctl logout` 退出该会话。

### 从预览到撤销

例如，以下是 `preview.json`。将示例的 `calendarId` 替换为 `state` 返回的编号。

```json
{"calendarId":"c-example","operationId":"roster-nov-01","batch":{"source":"qq-roster","rows":[{"externalId":"shift-19","event":{"title":"夜班","span":{"kind":"timed","start":"2026-11-19T22:00:00+08:00","end":"2026-11-20T06:00:00+08:00","zone":"Asia/Shanghai"}}}]}}
```

核对预览后，将相应的 `rows[i].baseVersion` 放回原始输入行，保存为 `commit.json`，保留来源、操作编号和行顺序。收到超时可以原样重试。下一次修订使用新的操作编号，同一班次沿用原 `externalId`。导入 30 条后只修改一条，回执应为 1 条更新、29 条跳过。

`import.undoPreview` 的输入是 `{"calendarId":"c-example","batchId":"batch-example"}`，`import.undo` 在此基础上增加一个新的 `operationId`。网页「批量导入 → 查看批次与撤销」和 `timepinctl batches` 均能找回编号，后者输入 `{"calendarId":"c-example"}`，可用末项的 `seq` 作为下一页的 `before`。预览计算撤销的影响，正式撤销会重新核对当前版本及操作者权限。回执中的 `removed/restored/changed/forbidden` 分别表示移除本批新增、恢复原内容、保留后续修改、权限不足。没有事件内容变化的批次返回空数组。

## 用 MCP 客户端操作

在支持 stdio MCP 的客户端中配置下列服务。将程序路径换成本机实际路径，并通过客户端的私有环境配置传入 `TIMEPIN_TOKEN`。下例由宿主进程继承已设置的凭据，具体的环境变量传递方式取决于客户端。

```json
{"mcpServers":{"timepin":{"command":"/absolute/path/to/timepinctl","args":["mcp"],"env":{"TIMEPIN_URL":"http://127.0.0.1:8080"}}}}
```

客户端启动子进程后调用 `initialize`、`tools/list` 和 `tools/call`。如调用 `event_query`，参数为 `{"calendarId":"c-example","window":{"start":"2026-11-01","end":"2026-12-01","zone":"Asia/Shanghai"}}`。导入工具名为 `import_preview`、`import_commit`、`import_undoPreview` 和 `import_undo`。每个工具声明具体输入 Schema，工具结果中的文本为原始 API 回执；领域或 HTTP 错误带 `isError:true`。也可以显式指定已登录的会话文件。stdio 适配器在客户端运行，实例上无需额外的 MCP 进程。[MCP 标准传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)

## 配置 DeepSeek 或兼容服务

安卓的「日历助手」可选个人兼容接口或实例共用模型。个人模式在手机执行当前日历命令并预览写操作。实例模式沿用下表的服务端凭据和成员名单。安卓自动同步使用服务端的 `calendar.publish` 建立初始副本，设备助手可以查询同步状态、立即同步及处理冲突。[安卓说明](./android.md) · [发布契约](../contract/v1/sync.md#发布本机日历)

| 服务端配置 | 默认值或行为 |
|---|---|
| `TIMEPIN_AI_BASE_URL` | `https://api.deepseek.com`。填写兼容服务的根地址，可含 `/v1`；程序在其后追加 `/chat/completions` |
| `TIMEPIN_AI_MODEL` | `deepseek-flash`。切换服务时填写该服务实际支持工具调用的模型名 |
| `TIMEPIN_AI_API_KEY` | 服务端专用密钥。默认 DeepSeek 地址也支持 `DEEPSEEK_API_KEY`。自定义地址使用专用变量，可连接允许无密钥的本地服务 |

1. 部署者用主机命令初始化独立后台账号，步骤见[部署说明](../README.md)。
2. 在 `/admin` 设置模型地址、模型名和密钥，也可用上表环境变量配置初始连接。
3. 在「账号与模型授权」给需要使用共用模型的日历账号授权。成员刷新后可打开「日历助手」。
4. 在对话中勾选一本或多本日历，默认全选。例如“帮我查询明天的班次”。服务器向模型发送该日历名称、角色、当地日期时区、用户消息和必要的查询结果。本轮最多进行四次模型请求，实例同时最多处理两段对话。切换日历或模型会重新开始对话。点击抽屉外的空白处可收起助手；在当前页面、账号和授权范围内重新打开可继续查看回复和草稿。
5. 明确的单项事件命令可直接执行。批量导入或撤销会显示实际预览和拟提交内容，只有本人确认后才写入。确认会重新检查会话、模型授权、日历权限、基准版本；同一编号重试取得已有回执。模型不可调用主机命令或任意 URL。

OpenAPI 中的 `aiChat` 接收 `operationId,calendarId,zone,message` 和可选 `history`；`aiConfirm` 只接收对话的 `operationId`。结果含 `status,text`，涉及操作时附 `op,result`，等待确认的批次另附 `proposal`。普通网页操作、JSON CLI、MCP 日历工具和结构化导入均可在关闭模型时使用。[DeepSeek 接口与模型名](https://api-docs.deepseek.com/) · [DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)

## 月历显示与设备自动化

已登录的 CLI 和 MCP 可使用 `display_detail`、`display_update` 读取或保存本人的 Web 月历设置。示例输入和返回结构见[Web 契约](../contract/v1/web.md#月历显示偏好)。Web 对话可按当前账号执行同名操作。安卓自配助手与已授权 ADB 使用[设备命令](../contract/v1/device.json)，可以查询系统日历、导入指定来源、配置提醒和设置月历，权限由手机控制。

## 实例后台与客户端模型

后台 CLI 通过 `timepinctl admin.login` 从标准输入读取账号密码。它使用独立的 `.console` 会话文件，退出使用 `timepinctl admin.logout`。日历自动化用 `mcp`，实例管理自动化显式使用 `mcp-admin`，各自只发布对应操作。已登录后台的 CLI 可调用 `instance.detail`、`instance.account`、`instance.registration`、`instance.model`，以及逐账号的 `model.grant`。后台只返回密钥配置状态。实例开关和成员授权同时满足时，共用模型才可调用。个人模型直接请求自己的供应商；浏览器用 `GET /api/v1/ai/tools?calendarId=…` 获取当前日历可用工具，再通过原来的命令执行。配置预设依据 [DeepSeek 接口](https://api-docs.deepseek.com/)、[通义千问兼容接口](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope) 和 [Ollama 兼容接口](https://docs.ollama.com/api/openai-compatibility)。

独立后台 CLI 与 `mcp-admin` 同步提供实例目录、后台变更记录、团组与日历权限、注册码与恢复码、分享回收、数据库备份等工具。使用 `timepinctl instance.overview` 查看实例，向 `timepinctl instance.directory` 提交 `{"section":"calendars","offset":0}` 分页查询。个人 MCP 仍只列日历工具。[后台操作目录](../contract/v1/web.md#实例运维接口)

## Web 助手的模型来源

首次打开个人 Web 助手时选中服务器模型。打开「个人设置 → 助手与导入 → 模型来源」可查看可用状态。在未开通的账号中，助手给出开通提示并暂停发送。选择「添加个人模型」可以从 DeepSeek 等预设开始，也可以添加自定义兼容接口。「保存并使用」会将新配置设为当前来源。助手里的来源选择器也可以随时切回服务器。

来源和个人配置按当前浏览器、当前账号保存。个人密钥只在当前页面内存中使用，刷新后有密钥要求的服务需重新输入。浏览设置、切换来源和保存配置均不会请求模型。

发送按钮右侧的小箭头可以切换「回车换行」和「回车发送」，该偏好按浏览器和账号保存。默认回车换行。两种模式都支持 Shift + Enter 换行、Ctrl + Enter 或 ⌘ + Enter 发送。中文输入法选词时可以正常按回车。

Web 的实例和个人模型会逐段显示回复。点击「停止生成」可以停止接收，已提交的日历操作仍以实际回执为准。重试同一条指令沿用原操作编号。助手删除整本日历需要用户确认，权限与 Web 设置一致。

多选日历时，助手可一次查询所选范围。编辑日程先明确目标日历并展示提案。只读日历仍然只可查询。

安卓设备命令 `system.autoImport.detail` 返回 `enabled` 和 `readGranted`，后者反映 Android 的读取授权及系统应用权限限制。`system.autoImport.update` 接受 `{"enabled":true}` 或 `{"enabled":false}`。开关默认开启，授权在手机上完成，前台和下次打开 App 时自动导入。

Web 和已登录的安卓助手提供「创建日历」入口。点选后进入账号管理，输入要创建的日历名称并确认助手的提案。即使账号尚无日历也可以创建第一本。

安卓设备命令支持 `background.detail` 和 `background.update`。省略 `widgetId` 表示雾光主题本身，组件编号可以从 `widgets.detail` 读取。例如 `{"widgetId":12,"mode":"solid","color":"#294658"}` 只改变编号 12 的底色。`follow` 跟随应用的雾光背景，`frost` 使用内置纹理，`photo` 恢复该位置上次选过的图片。模式和照片均按设备与组件编号记忆。通过系统选图器或已授权的本机命令 `background.photo` 导入图片，参数为 `image`（Base64、最多 524288 字符、长边至多 1280 像素）和可选的 `widgetId`。照片留在设备，模型只提出切换背景的指令。纯色接受六位 RGB，取消编辑保留原选择。

## 版本管理

`timepinctl admin.releasePublish` 接受 JSON 参数 `{"file":"TimePin.apk","notes":"更新说明"}`，使用独立后台会话。用 `admin.releaseList` 查看版本，`admin.releaseWithdraw` 接收 `{"package":"org.timepin.app","code":13}`。成员执行 `android.latest`，参数为包名 `package`。用 `android.download` 提供 `package`、`code` 和新文件路径 `file`，返回实际写入字节数和 SHA-256。上传和下载均流式处理。文件路径取 CLI 运行主机上的路径。

## 后台成员与日历归属

后台 CLI 和管理 MCP 通过 `instance.accountCreate` 创建成员，使用 `instance.accountRename` 修改登录名、`instance.accountPassword` 重设密码。密码从 JSON 标准输入提交，返回值与后台记录仅含操作结果和成员元数据。使用 `instance.directory` 的 `calendarOwners` 查看归属者和日历数，再以 `calendars`、`subjectKind` 和 `target` 分页读取该归属者的日历。
