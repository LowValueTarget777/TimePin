# TimePin 开发代理规约

本规约适用于整个仓库。开始工作前先阅读 [架构与开发计划](./TimePin-架构与开发计划.md) 中对应的模块、阶段与验收场景。处理数据归属或调用路径时，对照 [应用关系图](./TimePin-应用关系图.svg) 和 [技术架构图](./TimePin-技术架构图.svg)。

## 当前架构与发布边界

NAS 是日历服务器。个人 Web、安卓和管理后台是独立入口；Web 和安卓通过同一服务端授权层读写账号数据。后台账号由部署者本机配置，独立于日历用户。后台页面、管理 API、凭据和会话均单独维护。首个用户、旧管理员映射和成员令牌均不得取得后台权限。验证两种身份同时登录、分别退出、版本迁移和独立托管 Web。运维目录只投影管理元数据，后台管理写入与有界操作记录须同事务去重。恢复码单用且存摘要，备份只落主机私有目录。执行 `go test -run TestConsoleOperations ./internal/server` 验证跨账号隔离、权限变更、码与分享失效、备份可打开及重试。增加功能时须标明它属于个人设置还是实例策略，并交付对应 API、权限与界面。服务器打包静态文件仅是部署方式。

安卓须保留无服务器启动、Room 离线写入及原组件编号。局域网发现只能由连接操作触发，发现结果需由用户选定并正常登录。Web 与安卓均支持默认 DeepSeek 的个人模型、自定义兼容接口，以及管理员开启且按成员授权的实例模型。个人模型的密钥留在各自客户端，实例密钥不可通过管理读取接口返回。测试停用账号、撤销模型权限和网页跨账号切换。

## 开工与架构边界

PassNAT 通过额外的回环监听访问成员服务。开启 `TIMEPIN_CONSOLE_LAN` 后，后台页面、管理 API 与版本发布共用 TCP 连接两端的局域网检查，保留全部成员入口。更改路由时运行 `go test -run TestConsoleLAN ./internal/server`，从局域网和隧道分别核对成员服务、管理登录及后台会话访问。

- 采用以下开发顺序，日历核心与契约 → 自部署与 Web → API、CLI、MCP 和实例 AI → 同步协议验证 → **最后开发安卓与桌面小组件**。前期保留移动端需要的接口与行为样例，安卓阶段复用。
- 写代码前明确本次操作使用谁的身份、谁授权、在哪一端执行、写入哪份数据。默认采用 Go 单进程、内嵌 Web 与 SQLite。安卓采用 Kotlin 和 Room。服务端负责共享访问授权，安卓本地命令负责本机日历、显示选择和组件配置。
- 组件从本地库读取日程。已连接日历的本地写入与待提交记录须同一事务提交。服务端接收待提交操作时，按当前授权和事件版本重新校验。
- 模型提出工具调用，业务命令负责验证和执行。安卓自配的凭据留在设备，实例共用的凭据留在服务端。手动操作、普通查询和结构化导入须能在零次模型调用下完成。

## 功能、权限与数据

- 增加或变更用户操作，在同一项工作中更新操作定义、输入输出、适用入口、所需权限和验收用例。Web、CLI、机器人和外部 AI 复用实例命令。安卓 UI 与对话复用本地命令。涉及组件等设备偏好的操作注明设备作用域。
- 授权以日历和实际身份为边界，区分创建者、参与人及操作者。批量修改、撤销、重试与同步也要验证权限。免登录只读链接仅授予该日历的只读视图，撤销后重新请求应被拒绝。
- 写入有可重复识别的操作编号，更新有基准版本，批次有逐项结果。来源编号用于识别同一日历中同一来源的事件。重试复用已有回执，冲突保留双方变化。撤销按批次恢复并列出后来改过的事件。
- 修改数据库结构时提供迁移与兼容用例，使用旧版数据验证升级。确认事件标识、日历授权、批次记录和已有组件引用仍有效。记录失败后的恢复方式。

新版本验收执行 `go test -run TestInstanceAdministration ./internal/server`；验证管理员操作隔离、总开关与按账号授权、会话和令牌撤销。Web 分别检查七列和带周数的八列布局；安卓在局域网确认发现的地址和手动登录路径，再检查原组件绑定及离线使用。

## 验证与性能

- 安卓与服务端执行相同的行为样例。覆盖日期边界、跨午夜、重复规则单次例外、导入重试、并发改期和权限撤销。改变某条语义时一起更新两端预期。
- 改查询、同步、批量写入或组件刷新时，按计划中的固定基准复测对应指标。月历只读所选窗口，循环按需展开。已有副本按变化同步。同一批写入合并刷新相关组件。
- 增加运行时依赖、常驻进程或后台轮询时，记录要解决的实测问题、替代方案和资源代价。可在 1 核、512 MiB 容器及固定数据集上复测。移动端电量结论需标明真机与测量条件。
- 工具链落地时在此补充从仓库根目录可运行的实际构建、测试、迁移验证与基准命令，并保持可执行。交付时列出已运行的命令、环境、用例与结果，分别标明自动化、模拟器、安卓真机和 NAS 实测。尚未执行的检查如实标注。

项目使用 Go 1.25 或更高版本。以下命令均从仓库根目录运行。

发布版本固定为 `go.mod` 的 `toolchain` 补丁版本，Docker 构建器按对应多架构摘要锁定。升级工具链时同步更新两者，在 Linux amd64 和 arm64 分别运行 `scripts/build_go_release.py` 并保存两份清单与扫描记录，检查实际二进制构建信息及有调用路径的公告。

```sh
go build ./...
go mod verify
go vet ./...
go test -count=1 ./...
go test -run TestPortableContract -v ./calendar
go test -run 'TestSQLPortableCases|TestSharingAndTransactions|TestMigrationAndAtomicRollback' -v ./internal/server
go test -run '^$' -bench . -benchmem -cpu=1 -benchtime=350ms -count=3 ./calendar
```

修改操作时同步维护 [事件契约](./contract/v1/README.md)、[Web 服务契约](./contract/v1/web.md) 和 [跨端样例](./contract/v1/cases)。在支持 Go 竞态检测的环境运行 `go test -race -count=1 ./...`。Windows 上需要启用 CGO 并选用兼容的 MinGW-w64 GCC。正常发布使用纯 Go SQLite，设置 `CGO_ENABLED=0` 可交叉构建 Linux amd64 或 arm64。

SQLite 样例逐步重开数据库，与内存核心比较结果；迁移测试覆盖新建、重开、未知版本及写入失败后的事务回滚。Web 变更用不同会话走一遍邀请、授权、事件、导入与撤销链接；检查桌面和手机宽度、浅色与深色模式。用 `go run ./cmd/timepin` 启动本机实例，容器启动、备份和恢复见 [部署说明](./README.md)。

服务端基准使用标准库 Python 3 脚本。从空的专用测试实例开始，选择一个新的私有临时目录保存状态。先执行下列两步，完成后清理专用实例、数据及生成的状态文件。每次复测从同样的空库开始。

```sh
python3 scripts/benchmark.py seed --url http://127.0.0.1:8080 --state /tmp/timepin-bench-state.json
python3 scripts/benchmark.py measure --url http://127.0.0.1:8080 --state /tmp/timepin-bench-state.json
```

在 NAS 上核对 Docker 的 CPU 和内存限制。导入完成后通过容器进程的 `/proc/<pid>/status` 记录 RSS 和高水位，通过该容器 cgroup 的 `memory.peak` 记录含缓存的总内存峰值。空闲 CPU 取连续 10 秒的进程 CPU tick 差；启动耗时从容器 `StartedAt` 计到使用已有会话的 HTTP 月查询首次返回完整 30 项。记录窗口、样本数、查询计划及操作系统缓存条件。

## 架构修改与交付

修改第三阶段接口时同步维护 [OpenAPI](./contract/v1/openapi.json)、[事件 Schema](./contract/v1/schema.json) 和 [自动化说明](./docs/automation.md)。CLI、MCP 和网页模型工具的参数目录从这些契约读取。对话以当前会话身份在选中的日历执行，批量提案只能由本人通过服务端确认。模型请求在数据库事务之外执行。

```sh
go build ./cmd/timepin ./cmd/timepinctl
go run ./cmd/timepinctl tools
go test -run 'TestAdaptersAndSharedModel|TestPhaseTwoUpgrade|TestSQLPortableCases' -count=1 ./internal/server
uvx --from openapi-spec-validator openapi-spec-validator contract/v1/openapi.json
```

接口验收覆盖限权令牌与实时授权的交集、撤销时保留他人修改、模型禁用时导入零调用、批量确认前后撤权、两次确认只提交一次及升级前的批次。网页或 MCP 变更另用 Chromium 或 MCP 客户端核对真实入口。交付记录分别注明兼容测试端点和实际供应商的联测情况。

改变存储归属、授权边界、离线语义或部署拓扑属于架构修改。先在架构计划中记录变更动机、影响、兼容与迁移办法和验收场景，随后在同一项变更里更新相关图、Mermaid 源码、契约、实现与测试。常规功能直接沿用已有边界。完成前复核用户可见入口、越权与失败路径、重试后状态及本次涉及的性能指标。

## 个人 Web 设置验收

安卓选择服务器与成员认证分别验收。匿名连接只校验实例并保存地址，重开或退出后可从独立页面登录或注册。验收邀请注册、已有日历副本、同账号重新登录和已登录时更换实例的限制。后台成员建号、改名、重设密码及按所有者查看日历，运行 `go test -run 'TestConsoleMemberCredentials|TestConsoleOperations' ./internal/server`。测试成员密码只用于隔离实例。

安卓与个人 Web 缺省均为雾光毛玻璃和冰蓝纯色（#DCE7F4），升级优先读取显式偏好。应用自绘的返回、按钮、菜单和对话框复用主题控件及面板样式。调整主题时，在开启注册的回环验收实例运行 `scripts/verify_web_appearance.cjs`（使用下文 Playwright 的 run-code），在安卓 QA 包运行 `AppearanceTest#testThemeBackAndColorPicker` 与 `AppearanceTest#testWidgetBackdropIsolation`。验证拖动、输入、保存、取消、按账号隔离及组件独立背景。

账号资料归个人会话，日历令牌和管理后台不能代改。状态与变化摘要只查昵称、版本，头像按需读取。变更资料接口时执行 `go test -run TestPersonalProfile ./internal/server`；在开放注册的回环实例用 Playwright CLI 运行 `scripts/verify_web_profile.cjs`。安卓核对登录后身份卡、选图更换、跨端刷新、离线查看、退出和换号。调整组件初始布局时，在桌面保留原绑定，打开 App 后验证月历日期与近期日程内容。

变更安卓日历管理时，运行 `DeviceTest#testCalendarSettingsTarget` 核对开关、改名和删除均指向详情所属日历。运行 `AppearanceTest#testCalendarDirectoryAvailability` 检查设置卡片与目录的数量、离线可见性、撤权和重新授权。

自部署镜像使用 UID 65532 运行。NAS 离线构建同仓库 Dockerfile 复制完整证书目录树，目录模式为 0755，CA 文件为 0644。部署后核对运行镜像的 /etc 可遍历，并从已授权的 Web 会话执行一次只读查询，核对模型工具回执。

Web 事件颜色保留继承日历色与自定义色，时区下拉须沿用旧事件的 IANA 编号。分别验证桌面侧栏和窄屏工具栏打开助手抽屉。全部日历令牌使用显式目标 `*`，每本日历独立校验成员权限和令牌角色上限。测试覆盖 v7 令牌迁移、授权变化和撤销。

个人 Web 的模型来源按浏览器和账号保存，缺省为服务器。测试保留旧版显式选择、未开通时暂停发送、个人模型切回服务器，以及宽窄视口中长日历名的复选框尺寸。启动本地实例后，从仓库根目录执行以下浏览器回归。用例在独立浏览器上下文模拟账号和授权。

```sh
npx --yes --package @playwright/cli playwright-cli -s=web-settings open http://127.0.0.1:8080 --browser chrome
npx --yes --package @playwright/cli playwright-cli -s=web-settings run-code --filename scripts/verify_web_settings.cjs
npx --yes --package @playwright/cli playwright-cli -s=web-settings close
```

修改助手传输或整本日历删除时，运行 `go test -run 'TestStreamingCalendarLifecycle|TestDeleteCalendar' -count=1 ./internal/server`。运行上述 Web 用例验证未结束时的可见增量、停止和重试、回车菜单键盘操作、抽屉收起与恢复，以及成员邀请控件的聚焦间距。SQLite v9 升级保留原日历与游标，已删除编号不可重新发布。

账号助手通过个人会话确认建日历、建团组或接受邀请。日历对话取消全选仍须禁止发送，只有显式进入账号管理才可在没有日历时发送。设备提案由手机复核选区、组件编号、账号及冲突版本后执行。修改这些入口时，运行 `go test -run TestAccountAssistantWithoutCalendars ./internal/server`、在开启注册的回环测试实例运行 `scripts/verify_assistant_actions.cjs`，以及真机 `DeviceTest#testAccountAndDeviceActions`（参数同助手流式验收）和 `AppearanceTest#testAssistantCalendarSelection`。每个操作同时验证界面入口、确认回执、空选区、授权变化及重试状态。

## 安卓分发与版本验收

官方包沿用已发布证书，自签名社区包用独立包名 `org.timepin.app.community` 和持有者的长期密钥。发布渠道变更时验证两个包可并存、成员按包名取得相应更新、旧实例仍可下载官方包。更新构建依赖时同步维护 [第三方许可声明](./THIRD_PARTY_NOTICES.md)，检查 APK、Linux 发布目录和镜像中的随附许可。独立公开仓库从 Git 归档建立，使用公开作者身份；本地历史、私有验收报告、签名材料和运行日志留在开发端。

正式、开发及 QA 使用独立包名。日常版沿用已发布证书，开发、预览及 QA 共用专用测试证书。正式打包和凭据恢复见[安卓构建说明](./docs/android.md)。每次调整签名核对旧正式包、新 release、QA 应用及运行器的签名，再在小米 8 用 `adb install -r` 核对原账号和组件。

修改 APK 验签时，在小米 8 的独立 QA 包运行 `adb shell am instrument -w -e class org.timepin.app.UpdateArchiveTest org.timepin.app.qa.test/android.test.InstrumentationTestRunner`。验证下载临时文件可读出完整签名，拒绝损坏文件和包名不符的 APK，再用旧构建号通过 App 下载较新 QA 包，经系统安装器覆盖并核对原日历。

发布号和作者归 `contract/v1/release.json`。升级提高 versionCode，保留同一 applicationId 和签名。独立后台会话发布和撤回，成员会话检查和下载；CLI 接入遵循 OpenAPI 的 multipart 和二进制格式。执行 `go test -run 'TestReleaseDistribution|TestMonthPreferences' ./internal/server`，用两个签名相同的 QA 版本在系统安装器确认覆盖安装和数据保留。Web 与安卓「关于」显示版本和作者。农历数字格式另查 2026-11-01（9/23）、跨月与闰月，并在桌面月历核对。

## 同步协议验收

修改撤权草稿时，在空的 QA 安装中先运行 `DraftRecoveryTest#testOfflineRecoveryAndIsolation`，再以 `-e serverUrl` 指向开放注册的隔离回环实例运行 `DraftRecoveryTest#testRevokeDuringOfflineEdit`。同时运行 `AppearanceTest#testCalendarDirectoryAvailability`。覆盖空可用目录、其他可用日历、跨账号及实例隔离、保存原文、重新授权后维持待核对状态。

修改安卓日历分享或团组管理时，使用隔离服务器和小米或其他已解锁的 QA 设备运行 `adb shell am instrument -w -e serverUrl http://127.0.0.1:19497 -e class org.timepin.app.CollaborationTest org.timepin.app.qa.test/android.test.InstrumentationTestRunner`。通过 ADB reverse 连接该端口，服务器开启注册，QA 包退出账号。覆盖普通成员权限、邀请接受与撤销、只读链接换新和关闭、移交管理员及团组日历归属。已有 HTTP 操作继续使用成员会话和真实日历编号。

安卓登录后自动接收当前账号可访问的服务器日历。新建账号日历、编辑和删除要验证两个方向，登录后自动上传所有未连接的本机与系统来源日历，保留原编号和组件，其他账号及撤权记录隔离。保留离线操作、账号隔离与组件引用。使用独立实例在已解锁的 QA 手机上运行 `DeviceTest#testConnectedAutoSync`，通过 `-e serverUrl` 指定 ADB reverse 的服务地址，验证前台接收新共享、Web 隐藏日历的增量、撤权和发件队列。可用测试代理模拟 NAS 暂时不可达，另传 `-e gateUrl` 指向其回环控制地址验证联网后台重试。

同步开发须维护 [协议契约](./contract/v1/sync.md) 和 [OpenAPI](./contract/v1/openapi.json)。真实 HTTP 协议客户端使用独立 SQLite 副本。对一个日历串行应用每页与游标，保留版本冲突的用户输入。改动事件写入、导入或撤销时，复核同步索引和原命令回执的事务一致性。

从启动好的空专用实例执行以下命令。大库验收应在种子写入后、月份导入基准前运行。脚本会修改一条测试事件，并新建仅供双设备验收的日历和成员。只测断网与冲突时，对另一空实例直接运行 `python3 scripts/verify_sync.py --url http://127.0.0.1:8080`。用 Python 3 标准库运行，不启用 `-O`。

```sh
python3 scripts/benchmark.py seed --url http://127.0.0.1:8080 --state /tmp/timepin-bench-state.json
python3 scripts/verify_sync.py --url http://127.0.0.1:8080 --state /tmp/timepin-bench-state.json
go test -run 'TestSyncPaginationAndAuthority|TestSyncUpgradeAndRecovery' -count=1 ./internal/server
```

记录同长度单条事件的上行、下行、JSON 正文字节及返回条数。测试覆盖分页期间写入与删除、丢失页和写回执、设备重开、并发冲突解决、直接和团组授权变更、旧库和备份游标重建。安卓真机另行记录。

## 安卓构建与真机验收

桌面进程可能跨版本缓存 TimePin 的资源。组件使用的布局、控件、背景及 provider XML 的既有编号须保留在 `android/app/widget-resource-ids.txt`；新编号从正式 APK 读取。调整组件资源后，在干净构建中执行 `python scripts/verify_widget_resources.py --aapt <SDK中的aapt> <旧版APK> <新版APK>`。使用小米桌面覆盖升级时，保持桌面进程运行，逐页确认月历日期与近期日程的实际文字，核对原组件编号、日历选区和升级后的加载日志。

安卓使用 JDK 17、Android SDK Platform 34 与项目内的 Gradle Wrapper。构建会把仓库里的事件 Schema 打包到 App，并把相同的跨端样例打包到测试 APK。事件及导入改动须在 Go 和真实 Android Room 上运行同一组输入。

```sh
./android/gradlew -p android :app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest :app:lintDebug
go test -run TestMobilePublication -count=1 ./internal/server
adb install -r android/app/build/outputs/apk/acceptance/app-acceptance.apk
adb install -r android/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk
adb shell am instrument -w -e class org.timepin.app.DeviceTest#testPortableContract org.timepin.app.qa.test/android.test.InstrumentationTestRunner
```

调整设置和组件布局时，在专用 QA 包运行下列真机回归。它检查两种主题、分类入口和返回路径，并通过系统组件宿主在常规和紧凑尺寸检查最后一行。需要更新截图时可加 `-e snapshots yes`，图片保存在 QA 包内部的 `files/`。

```sh
adb shell appwidget grantbind --package org.timepin.app.qa --user 0
adb shell am instrument -w -e class 'org.timepin.app.AppearanceTest#testSettingsAndWidgets' org.timepin.app.qa.test/android.test.InstrumentationTestRunner
adb shell am instrument -w -e class 'org.timepin.app.AppearanceTest#testDragResizeAndAllDayShiftTitle' org.timepin.app.qa.test/android.test.InstrumentationTestRunner
adb shell am instrument -w -e class 'org.timepin.app.AppearanceTest#testDateSelectionAndNavigation' org.timepin.app.qa.test/android.test.InstrumentationTestRunner
```

助手变更需要在独立测试实例上授权测试账号使用临时模型，并通过 ADB reverse 将手机连到实例和模型端点。运行 `DeviceTest#testAssistantScopeAndStream` 时用 `-e serverUrl` 和 `-e modelUrl` 指向对应的手机回环地址，同时运行 `AppearanceTest#testAssistantCalendarSelection`。修改事件编辑器时运行 `AppearanceTest#testEventColorAndZone`。检查默认全选、取消或多选、分段回复、未订阅日历的远端查询和指定日历的写入确认。

Windows PowerShell 用 `.\android\gradlew.bat -p android` 并给含 `#` 的类名加引号。真机和 NAS 的完整顺序、测试账号、切换飞行模式、组件绑定和清理见[安卓验收步骤](./docs/android.md)。记录屏幕、电源和测量时长。测试身份、日历、模型端点、签名密钥及临时会话均与用户正式数据分开。

## 用 Git 管理开发进度

开工或交接时，用 `git status --short --branch` 和 `git log --oneline --decorate -10` 核对工作区与最近进度。每完成一个可验证的工作项，将属于该项的实现、测试及文档一起提交，标题写明阶段和具体成果，正文记录验证结果与待验事项。阶段验收通过后，在 `main` 上对应的提交创建附注标签 `phase-N`（N 为计划中的阶段编号），在标签说明里记录验收依据。

## 系统日历、提醒与地区数据验收

正式安卓包只申请读取日历。QA 包另有写日历权限，`CalendarFeaturesTest` 在系统日历提供程序中建立专用 LOCAL 来源并清理。授予 QA 包 READ_CALENDAR 和 WRITE_CALENDAR 后运行 `adb shell am instrument -w -e class org.timepin.app.CalendarFeaturesTest org.timepin.app.qa.test/android.test.InstrumentationTestRunner`。小米系统还需允许 QA 包的对应 AppOps。日期验收覆盖闰月、跨年 ISO 周、周一起始、公共假日与调休上班日、系统地区变更、手选地区和关闭显示。通知验收检查实发系统通知及静音日历。运行 `AppearanceTest` 检查 App 与月历组件中的同一天。

系统日历自动导入变更运行 `CalendarFeaturesTest#testAutomaticSystemImport`、`testAutomaticImportWork` 和 `testSystemImportMigration`。传入 `-e serverUrl` 并运行 `CalendarFeaturesTest#testSystemSourceRoundTrip` 验证系统来源首次上传、修改与 Web 回传。真机分别核对首次读取、前台来源变更、开关、重复扫描零新增批次、已连接来源的自动导入与跨账号隔离与旧库迁移。

更新节假日用 `uv run --script scripts/build_holidays.py` 重新生成离线资源，核对官方年度调整，数据依赖仅用于构建。新增地区同时更新公开契约的代码枚举。
