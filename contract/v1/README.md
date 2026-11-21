# TimePin 日历操作契约 v1

Go 核心 `timepin/calendar` 对一本日历执行命令。它既是后续服务端命令层的基础，也是安卓离线实现的行为参考。第一阶段使用进程内数据和回执，从 `calendar.New` 创建新实例会得到一本空日历。持久化适配器需要在一个事务中保存事件、导入来源版本和回执，才能在重启后提供同样的重试语义。

[机器可读契约](./schema.json) 的根节点校验命令，`$defs.reply` 校验回执，`$defs.calendar` 描述日历。每个 `$defs["op.…"]` 带有 `x-timepin` 注解，指明执行范围、权限和成功结果字段。格式采用 [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12)。Go 嵌入同一份 Schema，首次使用时编译，再按操作名选取分支，校验可完全离线执行。

## 调用示例

Web 和安卓在任何可写日历中新增日程时，默认选中全天，开始和结束都显示当前选定的日期。用户可以切换为指定时刻。助手在用户只指定日期时采用相同默认值。命令和同步记录显式携带 `span`，单日全天事件的 `end` 是次日。

下面的命令创建一个跨月夜班。手机离线创建时同样由客户端预先分配事件编号和操作编号。

```json
{
  "v": 1,
  "op": "event.create",
  "calendarId": "work",
  "operationId": "phone-17",
  "eventId": "night-01",
  "event": {
    "title": "夜班",
    "color": "#7856AB",
    "participants": ["wang"],
    "span": {
      "kind": "timed",
      "start": "2026-10-31T22:00:00+08:00",
      "end": "2026-11-01T06:00:00+08:00",
      "zone": "Asia/Shanghai"
    }
  }
}
```

Go 调用 `calendar.New(calendar.Calendar{ID: "work", OwnerKind: "group", OwnerID: "clinic", Color: "#336699"})` 创建日历，随后调用 `book.Execute(principal, requestBytes)`。返回值为 UTF-8 JSON 和 Go error。业务结果由 JSON 中的 `code` 表达，Go error 用于内部编码错误。创建成功后 `record.id` 为 `night-01`，`record.version` 为 1。`record.creatorId` 取自当前身份。用下面的命令查询 11 月 1 日，可以查到上月开始的这个班次。

```json
{
  "v": 1,
  "op": "event.query",
  "calendarId": "work",
  "window": {"start": "2026-11-01", "end": "2026-11-02", "zone": "Asia/Shanghai"}
}
```

## 身份、操作和回执

宿主在每次调用时为这一本日历解析 `Principal{ID, Role}`。服务端取成员当前有效权限与凭据范围的交集，纯单机宿主使用本机身份。被撤权身份无权读取历史回执。角色值为 `manager`、`editor`、`contributor` 和 `reader`，其中投稿者只能修改自己创建的事件。标识符为 1～128 个 ASCII 字符，具体字符范围见 Schema。编号作用域为一本日历，事件删除后仍占用原编号。

| 操作 | 输入 | 权限与成功结果 |
|---|---|---|
| `event.get` | `eventId` | 可读成员获取完整 `record`，包括版本和删除标记。 |
| `event.query` | `window`，可在窗口内指定 `participantId` | 可读成员获取按时间排列的 `occurrences`。每项带稳定的事件编号、原始发生日期 `on`、实际时间和有效颜色。 |
| `event.create` | `operationId, eventId, event` | 投稿、编辑或管理角色可创建事件，返回 `record`。手工事件编号避开导入专用前缀 `imp-`。 |
| `event.replace` | `operationId, eventId, baseVersion, event` | 按当前编辑权验证后完整替换内容，保留创建者和编号，版本加一。 |
| `event.delete` | `operationId, eventId, baseVersion` | 按相同编辑权校验，返回带 `deleted: true` 的新版本。该事件从时间窗口中移除。 |
| `import.preview` | `operationId, batch` | 逐行检查权限和版本，返回 `rows` 中的预测结果。事件、来源版本和回执均保持原状。 |
| `import.commit` | `operationId, batch` | 逐行校验并提交，返回 `batchId` 与 `rows`。提交可由已获授权的自动化直接发起。 |
| `import.undoPreview` | `batchId` | 写入角色可预览逐项撤销结果，返回 `undo`。 |
| `import.undo` | `operationId, batchId` | 按当前权限及事件版本执行撤销，返回批次编号及逐项 `undo` 回执。 |

一次提交以“日历、操作者、operationId”标识。成功回执内的编号、版本和逐行结果在重试时保持一致。对象键顺序和 JSON 空白不影响重试。重用一个已经提交的编号发送不同内容，得到 `operation_reused`。在原操作之后降权的成员重试时，核心仍检查当前编辑权。个人或组所有权属于日历元数据，创建者和参与人分开保存。

其他失败码包括 `invalid`（输入形状或领域规则错误）、`not_found`、`forbidden`、`conflict` 和 `window_too_dense`。单次事件写入的版本冲突附带当前 `current` 记录。客户端保留原待提交内容供用户比较，解决冲突后以新的操作编号提交。客户端根据稳定错误码处理逻辑，`detail` 供诊断使用。

## 日期、时区与单次例外

- **日期与时刻。** `date` 类型的起止只包含公历日期。`timed` 起止为精确到秒的 RFC 3339 时刻，各自的偏移必须与指定 IANA 时区相符。Go 内嵌时区数据，手机运行同一组时间样例。起止和查询窗口均为左闭右开，结束必须晚于开始。
- **按窗口查询。** 查询以所选时区的当地日期定义，允许 1～366 天。全天事件保留原日期，定时事件按真实时间与窗口求交。每个实例先按查询时区的开始日期排序，同日先全天、后定时，再按实际开始时刻、事件编号、原始发生日期排序。超过 10,000 个实例返回明确错误。
- **重复。** v1 支持 `daily, weekly, monthly, yearly`，以首个事件为锚，`interval` 为 1～999。可选 `until` 包含当天，以系列原始发生日期为准。月末和闰日遇到不存在的日期跳过该次。跨午夜的定时系列保留起止墙上时间和相隔的民用日期数。夏令时跳过导致起止时刻不存在时跳过该次。回拨出现两种偏移时优先沿用原事件对应端点的偏移，其次选择较早的瞬间。
- **例外。** 在完整事件的 `exceptions` 中，用 `{"on":"2026-11-09","cancel":true}` 取消一次，或用 `{"on":"2026-11-09","move":{…时间段…}}` 改期一次。每个原始日期最多一条例外，必须对应有效的原始实例。改到其他月份甚至系列截止日以后，仍可从新月份查到，并保留原 `on`。事件色覆盖日历色，返回大写 `#RRGGBB`。

## 结构化导入与预览冲突

```json
{
  "v": 1,
  "op": "import.preview",
  "calendarId": "work",
  "operationId": "roster-202611-a",
  "batch": {
    "source": "qq-roster",
    "rows": [
      {"externalId": "shift-01", "event": {
        "title": "培训",
        "span": {"kind": "date", "start": "2026-11-05", "end": "2026-11-06"}
      }}
    ]
  }
}
```

1. 预览每行得到从 0 开始的 `index`、`eventId`、观察到的 `baseVersion` 和 `outcome`。新行的基准版本为 0。成功时 `version` 为该次写入或跳过后的版本。交互端提交时沿用相同 `operationId`、来源和行顺序，将每行预览得到的 `baseVersion` 带回 `import.commit`，保证预览之后发生的更改能被识别。
2. 带 `externalId` 的行按“日历、来源、外部编号”定位。无外部编号的行按“本批次、行序号”一次性创建。外部工具可提交 1～5,000 行。批内重复外部编号或任一行数据格式错误，整批返回 `invalid`，此前各行也不会写入。
3. `created / updated / unchanged / conflict / forbidden` 是逐行结果。合法且可处理的行与回执一起提交。发生冲突或权限不足的行保持原状。省略旧行会保留原事件。没有提供基准版本时，仍会比较来源上次确认的版本，保护手动修改。
4. 用户核对过手动修改后，可为该行同时提交 `acceptCurrent: true` 和当前正整数 `baseVersion`，显式确认采用提交内容。内容相同时只重新确认来源版本。删除标记会继续阻止导入复活旧事件。可用 `event.get` 读取最新内容进行比较。
5. 安卓独立导入需要生成相同标识。将下列字符串元组以单个 NUL 字节连接，对 UTF-8 求 SHA-256，取前 16 字节的小写十六进制。批次编号取元组 `(calendarId, actorId, operationId)` 并加 `batch-` 前缀。稳定行取 `("source", calendarId, source, externalId)`，一次性行取 `("operation", batchId, 十进制行序号)`，均加 `imp-` 前缀。

用 `import.undoPreview` 传入 `v, op, calendarId, batchId` 预览撤销；`import.undo` 增加一个新的 `operationId` 后提交。两者都要求当前具备写权限，并逐项检查事件创建者和批次写入版本。成功回执的 `undo` 仅列出该批实际改变过内容的事件，按事件编号排序，包含 `eventId,version,outcome`。没有变更时返回空数组。未知批次返回 `not_found`。

`restored` 恢复原内容、`removed` 将该批创建的事件标记为删除，两者都会递增当前事件版本。以前的手动改动仍受来源版本保护。`changed` 保留后来改过的版本，`forbidden` 表示当前身份无权撤销该条。撤销新增后可用同一外部编号和新的操作编号重新导入，沿用已有事件编号和版本序列。正常手工删除的事件仍保留删除标记。重试原撤销请求返回原回执。[撤销跨端样例](./cases/import-undo.json)

## 跨端验收

从仓库根目录运行 `go test -run TestPortableContract -v ./calendar`。[共享样例](./cases) 的每个 JSON 文件都是一场独立测试，按 `calendar` 建立空日历，以 `as` 指定的实际身份依次执行 `command`。`expect` 对对象匹配列出的字段，对数组精确匹配长度和顺序，对标量精确匹配值。可选 `sameAs` 是此前步骤的零基索引，指示重试应返回同一回执。Go 还检查所有回复符合公布的 Schema。安卓阶段直接使用这些文件。

Go 内存执行器用锁提供提交边界。SQLite 适配器在同一事务里读取授权，按窗口索引选取候选事件，并将写入、来源版本、批次前值及回执原子持久化。可用 `go test -run TestSQLPortableCases -v ./internal/server` 逐步重开文件数据库并比较两种执行器的输出。[账号、授权与服务端 HTTP 契约](./web.md)
### 助手的批次预览

Web 个人模型与实例模型使用相同的预览语义。模型提出 `import.commit` 时，客户端或服务器先执行 `import.preview`，将逐行结果展示给操作者，并将缺省的 `baseVersion` 填为对应预览行的版本（新行为 0）。明确给出的版本保持原值。确认时提交同一操作号和已核对的行；在预览后发生的修改可在正式回执中返回冲突。`import.undo` 同样先执行 `import.undoPreview`，正式撤销时重验各事件当前状态。按批次找回记录时，下一页传上一页末项的 `seq` 为 `before`。[并发预览样例](./cases/preview-concurrency.json)
