# TimePin 服务端 HTTP 契约 v1

匿名 `GET /api/v1/instance` 可带 32 个十六进制字符的 `challenge`。服务端追加 64 个十六进制字符的 `proof`，便于确认不同地址指向同一实例。非法校验码返回 400。省略参数时沿用旧响应。校验只用于地址比较，成员调用仍需独立的会话或令牌。

实例可通过 `TIMEPIN_CONSOLE_LAN` 将后台限于局域网。访问 `/admin` 或 `/api/v1/admin` 下的资源时，服务端先检查连接的本地地址和对端地址，再认证后台账号。被网络策略拒绝时统一返回 HTTP 403 及 JSON 的 `error` 字段。个人登录、账号恢复、日历查询与同步、成员下载安卓版本沿用各自的账号和权限契约。

[可下载的 OpenAPI 3.1 契约](./openapi.json) 与 [事件 JSON Schema](./schema.json) 同时通过 `GET /api/v1/openapi.json` 和 `GET /api/v1/schema.json` 提供。接口按客户端会话或 Bearer 令牌执行。令牌仅可读取自己的限权状态、操作目标日历，并在授权范围内管理该日历。`GET batches?calendarId=…&before=…` 列出该日历最近的批次；`before` 可省略。账号会话还可调用 `token.list/create/revoke` 与已授权成员的 `POST ai/chat`、`POST ai/confirm`。[第三阶段操作示例与权限](../../docs/automation.md)

Web、脚本与机器人均可使用下列接口。实例根路径提供网页，API 前缀为 `/api/v1/`。所有 POST 使用 UTF-8 JSON，单次请求体上限为 16 MiB。登录成功后保存响应设置的 `timepin_session` Cookie，后续请求携带同一 Cookie。服务端根据会话解析操作者。

## 账号与查询

个人会话的 `GET /api/v1/state` 返回 `profile{loginName,displayName,avatarVersion,revision}`。昵称用于展示，登录和邀请仍使用账号名。登录成功后，`POST /api/v1/login` 在原有的 `id,name` 之外同时返回本人的 `profile`，如有头像则带图片，供新设备立即显示。后续账号状态及增量检查只传摘要。

| 个人操作（POST /api/v1/manage） | 输入 | 结果 |
|---|---|---|
| `profile.detail` | 个人登录会话 | 返回资料摘要；设置了头像时另有 `avatar`，值是 JPEG 的标准 Base64 |
| `profile.update` | `operationId,revision`；传 `displayName` 或 `avatar`，也可同时传 | 返回新摘要。昵称 1–40 个字符。头像要求正方形 JPEG，边长至多 256 像素、文件至多 64 KiB；传空串恢复文字头像。省略字段保留原值。旧版本返回 409，同一操作编号重试复用原回执 |

Web、安卓和具有个人会话的 JSON CLI、MCP 可操作本人资料。日历令牌与后台身份不能读取或编辑个人头像。安卓本机保存账号、服务器和最近一次成功读取的资料，离线可查看，重新登录或换号后按当前身份加载。

设备可在选定日历用 `POST /api/v1/sync/pull` 建立副本和续传，命令提交继续使用 `POST /api/v1/commands`。分页、删除标记、撤权和版本冲突的处理见 [同步契约](./sync.md)。

| 方法与相对路径 | 输入 | 成功结果 |
|---|---|---|
| GET `state` | 当前会话 | 登录后返回 `user{id,name}`、`calendars[{id,name,color,ownerKind,ownerId,role,visible}]`、`groups[{id,name,role}]`。匿名返回 `user:null, firstAccount, canRegister`。持邀请的新用户也可注册 |
| POST `register` | `name,password`，可选 `invite` | 返回 `{id,name}` 并登录；个人日历、账号、邀请授权和会话一起提交。密码至少 10 个字符、至多 256 字节 |
| POST `login` | `name,password` | 返回 `{id,name}` 并设置 30 天会话 Cookie |
| POST `logout` | `{}` | 撤销本会话，返回 `{ok:true}` |
| POST `password` | `old,new` | 检查原密码，撤销该账号所有旧会话，给当前客户端签发新会话，返回 `{ok:true}` |
| POST `commands` | 一条带 `v:1,calendarId,op` 的日历命令，字段按具体操作填写 | 返回事件契约规定的 `code` 及结果。查询与预览均按本次请求的日历权限执行。[事件命令与错误码](./README.md) |
| GET `public/{token}` | 查询参数 `start,end,zone`，日期范围左闭右开，时区取 IANA 名称 | 返回 `{name,color,result}`，其中 `result` 是一次事件窗口查询回执；关闭或换新后的旧令牌返回 HTTP 404 |

网页邀请地址形如 `/#invite=<token>`，访客入口形如 `/#share=<token>`。日历公开视图只返回该日历名称、颜色和窗口中的日程，包括标题、时间、有效颜色、事件编号、发生日及版本。成员列表和完整事件需相应的会话权限。

安卓的「日历设置 → 分享与成员权限」和「设置 → 我的团组」复用下表的个人会话操作。已同步日历使用服务端日历编号作为 `target`。手机以当前连接地址生成可复制或通过系统分享的链接。已登录成员可在「我的团组 → 接受邀请」粘贴同一服务器的邀请链接或邀请码。访客链接在浏览器打开。

## 共享管理

本节个人与共享操作使用 POST `manage`。请求包含 `op`，写操作另带客户端分配的 `operationId`（1～128 字节）。`target` 表示日历编号或组编号。可见的编号来自 `state`。同一账号重发相同管理编号及内容可取得原回执；重新使用编号发送不同内容返回 HTTP 409。每次重试都会重新检查目标的管理权限，例如，已移交管理权的原管理员再次发起移交会被拒绝。

| op | 其他输入 | 身份和结果 |
|---|---|---|
| `group.create` | `name` | 已登录用户建立团组，成为管理员，返回 `{id}` |
| `group.detail` | `target` | 组管理员读取 `members,invites` |
| `group.member` | `target,subject,role` | 组管理员指定账号名与 `member/admin/remove` 增加、改权或移除成员；须保留至少一位管理员 |
| `group.transfer` | `target,subject` | 组管理员选择现有成员的账号名作为接任者，将自己改为普通成员 |
| `calendar.create` | `name,color`，可选 `team` | 默认归当前个人；指定组编号时要求该组管理员；返回 `{id}` |
| `calendar.publish` | `operationId,target,name,color,records` | 已登录的个人创建新日历。保留本机编号、事件版本与来源基准，返回 `{id,count}`。请求结构和重试见[发布契约](./sync.md#发布本机日历)。 |
| `calendar.delete` | `target,operationId` | 个人所有者或归属团组的管理员永久删除整本日历。事务清理事件、共享、单本令牌、邀请与导入记录；返回 `{"ok":true}`。同一账号会话可重试原编号。 |
| `calendar.update` | `target,name,color` | 日历管理者改名称及默认颜色，颜色格式为 `#RRGGBB` |
| `calendar.detail` | `target` | 日历管理者读取单独授权 `members[{id,name,kind,role}]`、待接受邀请 `invites[{id,role,expires}]` 和当前 `shareToken`；到期时间为 Unix 秒 |
| `calendar.grant` | `target,subjectKind,subject,role` | 日历管理者授权或移除指定对象；`user` 对应账号名，`group` 对应团组编号。给组新授权时要求操作者属于该组 |
| `calendar.visible` | `target,enabled` | 任一可读成员设置当前账号的显示偏好，默认显示 |
| `token.create` | `target,name,role,token` | 由账号会话签发。传具体日历编号限定单本，显式传 `*` 授予本人现在及以后可访问的日历。每次请求逐本取实际角色和令牌上限的较低者。 |
| `token.list / token.revoke` | 列举无需参数，撤销传令牌编号 `target` | 会话可查看本人令牌的名称、范围、角色和到期时间。全部日历的 `calendarId` 为 `*`，撤销立即生效。 |
| `calendar.share` | `target,enabled` | 日历管理者传 `true` 签发新的访客令牌，返回 `{token}`；传 `false` 关闭。新令牌会替代旧令牌 |
| `group.invite` / `calendar.invite` | `target,role` | 对应管理者创建指定角色的邀请，返回 `{id,token}`。一次有效，7 天到期 |
| `group.uninvite` / `calendar.uninvite` | `target,inviteId` | 对应管理者撤销该对象上的指定邀请 |
| `invite.accept` | `token` | 登录成员接受邀请，返回 `{kind,id}`；新账号也可在注册请求中传 `invite` 原子完成 |

表中未另列结果的管理写操作返回 `{ok:true}`。日历角色由高至低为 `manager/editor/contributor/reader`，显式移除使用 `remove`。个人所有者与所属组管理员拥有管理权，所属组的普通成员默认只读。个人授权和组授权取当前最高有效权限。移除其中一条授权后，其他有效来源继续生效。投稿者仅能修改或删除本人创建的事件，事件参与人字段不授予编辑权。

## 请求示例与错误处理

已登录的客户端可以向 `/api/v1/manage` 发送以下内容，返回体的 `id` 可用于随后的组日历创建。

```json
{"op":"group.create","operationId":"family-setup-01","name":"家庭"}
```

取得日历编号后，向 `/api/v1/commands` 查询班次。

```json
{"v":1,"op":"event.query","calendarId":"替换为实际编号","window":{"start":"2026-11-01","end":"2026-12-01","zone":"Asia/Shanghai"}}
```

客户端先检查 HTTP 状态。输入错误、会话缺失、目标无权访问、失效令牌及重复编号分别常见于 400、401、403、404 和 409；API 失败体含 `error`。成功进入日历命令后，再检查 JSON 的 `code`。例如，同一事件版本竞争会返回 `code: "conflict"` 与 `current` 供比对。批量导入还要逐行检查 `rows[].outcome`。输入、重试和超时处理可参照 [跨端契约样例](./cases)。

## 月历显示偏好

登录会话通过 `POST /api/v1/manage` 读取 `{"op":"display.detail"}`，用 `display.update` 提交 `operationId` 和完整的 `display` 对象。两者返回同一设置结构。每个账号独立保存，日历级令牌无权读写账号设置。公开分享仅在浏览器保存查看者的选择。手机的本机偏好和提醒使用[设备契约](./device.json)。

```json
{"op":"display.update","operationId":"my-month-01","display":{"weekStart":"monday","weekNumbers":true,"lunar":false,"holidayRegion":"system","fontSize":14}}
```

`holidayRegion` 接受 `system`（默认）、`off` 或数据目录中的地区代码。显示端读取自己的地区，显式选择优先。周数取每行周四所属的 ISO 周年和周号。字号可选 12、14、16、18。节假日包收录 2020–2035 年国家级公共假日，后续政策和临时安排随数据版本更新。重建数据用 `uv run --script scripts/build_holidays.py`。整理中国内地公布的假期并标注调休上班日。[数据来源与许可](https://github.com/vacanza/holidays) · [2026 年官方通知](https://www.beijing.gov.cn/zhengce/zhengcefagui/202511/t20251104_4258873.html) · [2027 年官方通知](https://www.kashi.gov.cn/ksdqxzgs/c115966/202611/510945af03af482a8190dda1a63f5603.shtml)

## 账号与服务器管理

Web 助手的回车模式为客户端输入偏好，按当前浏览器和账号保存。快捷键和发送按钮提交相同的 `ai/chat` 请求或个人模型对话。换行与输入法选词在本机完成。个人模型执行 `event.query`、`event.get` 和 `import.undoPreview` 时按只读命令契约省略 `operationId`。

个人 Web 设置中的「模型来源」与助手共用当前浏览器内按用户 ID 隔离的偏好。初次使用、旧版空选择或所选配置已失效时，采用 `server`。已保存的个人配置 ID 仍然有效。服务端来源的可用性取自当前会话 `GET /api/v1/state` 的 `ai.enabled`，可用时显示 `ai.model`，发送至 `POST /api/v1/ai/chat`。不可用时暂停发送，直到成员选用自己的配置或获得授权。切换来源只写本机偏好，个人密钥按账号和接口地址隔离在页面内存中。

`/admin` 是管理员页面，部署者在服务器本机创建后台账号。POST `admin/login` 使用独立账号密码，返回身份并签发有效 12 小时的 `timepin_console` Cookie，路径为 `/api/v1/admin`。GET `admin/state` 读取当前后台身份，匿名返回 401。POST `admin/logout` 撤销当前后台会话。POST `admin/manage` 接受 OpenAPI 的 ConsoleManagement，包括 instance.* 与 model.members/grant。个人会话和日历令牌均无后台权限。账号启停保留其日历和团组，停用会撤销会话和已签发令牌。服务端模型通过实例开关和逐账号授权；这些限制在调用模型及提交工具操作前重新校验。个人模型使用同一日历权限。`GET /api/v1/instance` 返回公共服务标识。`GET /api/v1/changes` 返回当前用户可见日历的摘要；Web 在前台每 30 秒核对一次，有变化时重新加载可见窗口，标签页隐藏时停止。安卓也使用该摘要发现账号变化，通过 `state` 读取获授权日历目录。事件读写沿用逐本游标和 `commands`。

## 实例运维接口

以下只接受后台账号会话。读取和写入统一使用 POST `admin/manage`，命令从 OpenAPI 的 `ConsoleManagement` 获取。所有后台写入携带 operationId，客户端重试时保留同一参数和编号。

| 操作 | 输入与结果 |
|---|---|
| `instance.overview` | 返回账号、日历、团组、有效分享与会话的计数，注册与模型配置、数据库页占用和最近 4 次后台变更。 |
| `instance.directory` | section 选 accounts、calendars、groups、shares、invites、tokens、members 或 grants。后两项需要 target。可按 search 匹配名称或归属，offset 翻页，每页最多 25 项。返回 items 与 more。 |
| `instance.history` | 返回最近 1,000 次成功后台变更中的一页。每页 25 项，before 使用前一页最后的 seq。与业务写入同一事务，重试只记录一次。 |
| `instance.invite / recovery / cancelRecovery` | 客户端用安全随机数生成 32 字节并编码为 64 位小写十六进制 token。注册码有效 7 天，恢复码绑定 target 成员、有效 30 分钟，新码替换旧码。回执为 id 和 expires，数据库只保存码的摘要。 |
| `instance.signout / uninvite / revokeToken / unshare` | target 分别为账号、邀请、接口令牌或日历编号。signout 退出个人会话，保留单独签发的令牌。unshare 还需要列举时的 revision，链接已轮换则返回 409。 |
| `instance.groupCreate / groupRename / groupMember` | 指定首位管理员建组、改组名或维护成员角色。更改保留至少一位团组管理员。 |
| `instance.calendarCreate / calendarUpdate / calendarGrant` | 为个人或团组创建日历、修改名称与颜色、调整直接授权。个人用账号名，团组用编号。继承的角色由账号或团组归属决定。 |
| `instance.backupCreate / backups` | 在服务器数据库旁的 backups 目录按需生成独立 SQLite 文件，通过 quick_check 后登记。返回 file、at、bytes。backups 用 offset 分页读取已创建的记录。文件仅由主机管理员取用，部署者另行保存配置与软件。 |

成员可在个人登录页选择「使用恢复码」，或向 POST `/api/v1/recover` 提交 name、token、password。成功后需要用新密码登录，旧会话和日历令牌失效。停用账号、正常改密与撤销恢复码均使未用的旧码失效。

Web 请求 `POST ai/chat` 时可指定 `Accept: text/event-stream`。每帧包含事件名和 JSON 编码的 data。状态 `status`、文本增量 `delta` 使用字符串。结束帧 `result` 返回完整 ChatReply，或 `error` 返回错误消息。开始传输前的拒绝使用原 HTTP 错误码。原 JSON 调用仍返回完整回执。写入和确认只使用完整的工具参数，重试使用原 operationId。

SQLite v9 记录已删除的日历编号，阻止离线设备用旧编号重新发布。已有合法游标再次同步会收到 revoked。所有日历令牌继续按其他日历的现有权限访问。单本令牌随日历失效。CLI 与 MCP 通过 calendar_delete 调用；助手先展示删除影响并取得确认。

Web 助手默认勾选账号可访问的全部日历，包括在月历上隐藏的日历；可以单独增减或清空。没有勾选时暂停发送。单本仍可传 calendarId，多本传不重复的 calendarIds，二者互斥。查询可省略工具参数 calendarId，按授权范围返回 calendars 分组结果。跨日历对话中的写入指定一本目标并先确认。切换勾选范围会清空旧对话上下文。服务端在读取、工具执行、重试和确认时复核权限。个人模型用 POST ai/tools 取得范围内工具，POST ai/query 批量查询。两者均需本人会话。
## 个人 Web 外观

「设置 → 外观 → 主题与背景」属于当前浏览器里的个人偏好。首次使用为雾光毛玻璃和冰蓝纯色 `#DCE7F4`。其他主题包括跟随系统、浅色和深色。雾光背景可选渐变、本机图片或六位 RGB 纯色。拖动三个 0–255 通道、取色器、预设色和手填颜色编辑同一份草稿。保存应用到当前账号的页面、菜单和弹窗，取消或关闭会放弃草稿。

偏好以用户 ID 分隔在本机浏览器存储。图片在浏览器内缩至长边 1280 像素并编码为 JPEG。登录前及匿名分享采用默认外观，后台延续其独立的系统配色。账号月历的日期、周数和字号仍由 `display.detail/update` 管理。

浏览器自动化可在已登录的日历页导入同源模块 `/appearance.js`。`getAppearance()` 返回 `{theme, mode, color, hasPhoto}`。`updateAppearance({theme, mode, color})` 支持部分更新并返回保存后的同结构结果。主题接受 `frost/light/dark/system`，背景接受 `solid/frost/photo`，选 `photo` 将复用当前账号已在本机选定的图片。新图片通过外观编辑器的文件输入选择。

## 账号与设备助手接口

`GET /api/v1/ai/actions?scope=account|device` 用个人登录会话获取对应的模型工具。账号范围包含 `calendar.create`、`group.create` 和 `invite.accept`。设备范围包含 `widgets.update`、`sync.run` 和 `sync.resolve`。设备状态由本机的 `widgets.detail`、`sync.detail` 提供。

`POST /api/v1/ai/actions` 接收 `scope, message, context` 和可选 `history`。账号上下文使用可见团组的编号、名称和角色。设备上下文使用已选本机日历的同步状态和可配置的组件编号。账号登录、实例模型开关和按人授权同时满足时才请求实例模型。返回 `text` 和可选的 `proposal`；支持既有的 SSE 事件。此步只生成提案。邀请工具无模型参数，客户端保留用户提供的原始邀请，在确认阶段填入 `token`。

`POST /api/v1/ai/account/confirm` 接收 `userId, source, command`。来源为 `personal` 或 `server`。命令需携带稳定的 `operationId`；在一个管理事务中核对当前用户、团组授权及邀请的有效性，实例来源还须复核模型授权。重复的同一命令返回同一管理回执。设备提案通过安卓命令执行。[OpenAPI](./openapi.json) · [设备契约](./device.json)

## 雾光预览的本机背景

预览客户端的设备对话在上下文中附带 `background` 时，实例可以提出 `background.update`。安卓确认时复核账号、当前日历选区和目标组件。省略组件编号时修改本机应用。照片选择和 `background.photo` 导入仅在设备上执行。实例接收模式、颜色和是否有可用照片，图片保存在手机。

## 安卓版本与关于

安卓更新按包名隔离。日常版为 `org.timepin.app`，自签名社区版为 `org.timepin.app.community`，预览版和 QA 分别为 `org.timepin.app.glass`、`org.timepin.app.qa`。管理员上传带版本描述的 APK；成员调用 `GET /api/v1/releases/{package}/latest` 获取该渠道最新版本，经 `GET /api/v1/releases/{package}/{code}/apk` 下载。已支持的空渠道返回 `{"release":null}`，未知渠道返回 404。手机安装前须校验本机签名。

版本号与作者通过 `GET /api/v1/instance` 的 `version` 和 `author` 返回。后台使用独立会话向 `POST /api/v1/admin/releases` 提交 multipart/form-data 的 `apk` 文件（最多 64 MiB）与可选的 `notes`（4096 UTF-8 字节）。`GET /api/v1/admin/releases?offset=0` 分页列出版本及发布状态。包名与构建号组成发布操作标识，同包同说明重试返回已有记录，冲突返回 409。撤回用 `POST /api/v1/admin/releases/{package}/{code}/withdraw` 提交空 JSON 对象；撤回操作可重试。后续修订须使用新构建号。

成员通过 `GET /api/v1/releases/{package}/latest` 获取最高已发布构建的 release，空目录返回 null；用 `GET /api/v1/releases/{package}/{code}/apk` 下载对应文件。每次请求检查当前会话。安卓在回到前台或登录时检查，成功后同账号 12 小时内自动检查一次；手动检查可随时执行。选择安装后验证大小、SHA-256、包名、实际版本及当前签名，再进入系统安装器。内网服务器可以用 HTTP，公网连接使用 HTTPS。APK 分块随实例数据库备份；已下载并安装的版本可在撤回后继续运行。

月历显示的 `lunar` 控制开关，可选 `lunarFormat` 取 `text`（缺省）或 `numeric`。2026-11-01 的数字标注为 9/23，闰六月初一为闰6/1。安卓该选项作用于本机月历和桌面月历，个人 Web 按账号保存。

## 成员账号与日历目录（0.14.0）

未登录客户端通过 `GET /api/v1/instance` 校验实例标识。成员登录和注册分别调用 `POST /api/v1/login` 和 `POST /api/v1/register`，两者返回个人会话；邀请注册随注册请求提交 `invite`。

以下命令要求独立后台会话，写操作携带 `operationId`。

| 操作 | 参数 | 结果 |
|---|---|---|
| `instance.accountCreate` | `name`、`password` | 创建成员和默认个人日历，返回 `id` 和 `name`。邀请码策略只约束成员自助注册。 |
| `instance.accountRename` | `target` 为用户编号、`name` 为新登录名 | 返回 `ok`。原成员编号、日历、团组及个人会话继续有效。 |
| `instance.accountPassword` | `target`、`password` | 返回 `ok`。事务内撤销该成员的会话、接口令牌及恢复码。相同操作重试返回原回执。 |
| `instance.directory` | `section=calendarOwners`；或 `section=calendars`、`subjectKind=user/group`、`target` | 返回最多 25 条 `items` 及 `more`。归属目录给出 `id/name/kind/calendars`，仅统计实际拥有的日历。普通日历搜索沿用原契约。 |
