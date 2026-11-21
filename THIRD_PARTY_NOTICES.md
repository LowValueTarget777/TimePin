# 第三方软件与数据

TimePin 原创部分由 LowValueTarget 以 MIT 许可证发布。下表按分发内容列出第三方组件。再分发时请同时提供对应的版权声明、许可证原文及附加声明。[TimePin 许可证](./LICENSE)

## 安卓应用

| 运行依赖 | 版本 | 来源和条款 |
|---|---|---|
| AndroidX Room runtime、common | 2.6.1 | Android Open Source Project，Apache-2.0 |
| AndroidX SQLite、SQLite framework | 2.4.0 | Android Open Source Project，Apache-2.0 |
| AndroidX Arch Core runtime、common | 2.2.0 | Android Open Source Project，Apache-2.0 |
| AndroidX Annotation、Annotation Experimental | 1.3.0、1.1.0-rc01 | Android Open Source Project，Apache-2.0 |
| Kotlin stdlib、stdlib-jdk7、stdlib-jdk8 | 1.9.24、1.8.22、1.8.22 | JetBrains 及贡献者，Apache-2.0 |
| JetBrains Java Annotations | 13.0 | JetBrains 及贡献者，Apache-2.0 |

清单依据 Gradle 的 `releaseRuntimeClasspath`。官方版和社区版共用这一组运行依赖；KSP 和 Room compiler 仅在构建时运行。APK 的 `assets/legal/` 包含 TimePin 许可、此清单和安卓依赖原文；节假日声明位于 `assets/holidays/LICENSE.txt`。[Apache-2.0 原文](./licenses/android/Apache-2.0.txt) · [Kotlin 上游声明](./licenses/android/Kotlin-NOTICE.txt) · [AndroidX](https://android.googlesource.com/platform/frameworks/support/) · [Kotlin](https://github.com/JetBrains/kotlin/tree/v1.9.24/license) · [Java Annotations](https://github.com/JetBrains/java-annotations)

## Go 服务端与 CLI

清单覆盖 Linux amd64、Linux arm64 及 Windows amd64 源码构建。CLI 使用其中的子集。Linux 发布目录保留完整的 Go 依赖声明。

| 模块 | 版本 | 随附声明 |
|---|---|---|
| Go 标准库及其内置依赖 | 1.26.11 | [许可与专利授权](./licenses/go/go-runtime.txt) · [内置模块](./licenses/go/go-vendor.txt) |
| github.com/dustin/go-humanize | v1.0.1 | [许可](./licenses/go/github.com_dustin_go-humanize.txt) |
| github.com/google/uuid | v1.6.0 | [许可](./licenses/go/github.com_google_uuid.txt) |
| github.com/mattn/go-isatty、github.com/ncruces/go-strftime（Windows 构建） | v0.0.20、v1.0.0 | [go-isatty](./licenses/go/github.com_mattn_go-isatty.txt) · [go-strftime](./licenses/go/github.com_ncruces_go-strftime.txt) |
| github.com/remyoudompheng/bigfft | v0.0.0-20230129092748-24d4a6f8daec | [许可](./licenses/go/github.com_remyoudompheng_bigfft.txt) |
| github.com/santhosh-tekuri/jsonschema/v6 | v6.0.2 | [许可](./licenses/go/github.com_santhosh-tekuri_jsonschema_v6.txt) |
| golang.org/x/sys、golang.org/x/text | v0.46.0、v0.14.0 | [sys](./licenses/go/golang.org_x_sys.txt) · [text](./licenses/go/golang.org_x_text.txt) |
| modernc.org/libc | v1.74.1 | [许可、作者及其第三方声明](./licenses/go/modernc.org_libc.txt) |
| modernc.org/mathutil | v1.7.1 | [许可](./licenses/go/modernc.org_mathutil.txt) |
| modernc.org/memory | v1.11.0 | [许可与附加声明](./licenses/go/modernc.org_memory.txt) |
| modernc.org/sqlite 及 SQLite | v1.54.0 | [模块许可和 SQLite 声明](./licenses/go/modernc.org_sqlite.txt) |
| Unicode 数据、IANA 时区数据 | 随上述库提供 | [Unicode](./licenses/go/Unicode.txt) · [IANA](./licenses/go/IANA-tzdata.txt) |

## 节假日、构建工具与容器

- **节假日数据**由 Python Holidays 0.109 生成，以 MIT 条款分发，保留 Vacanza Team 及其他上游版权。中国内地年度调整的来源列在生成脚本中。Web 与安卓都包含该数据。[原始声明](./internal/server/web/holidays/LICENSE.txt) · [生成脚本](./scripts/build_holidays.py)
- **Gradle Wrapper 8.7** 随源码分发。构建时由 Wrapper 下载 Gradle 的二进制分发包并核对 SHA-256。仓库保留该版本完整的许可及 NOTICE。[Gradle LICENSE](./licenses/gradle/LICENSE.txt) · [Gradle NOTICE](./licenses/gradle/NOTICE.txt)
- **容器 TLS 根证书集合**取自 Dockerfile 固定的 Alpine 构建镜像，最终镜像内置其 ca-certificates 文件。Mozilla 根证书数据以 MPL-2.0 条款发布。镜像内的 TimePin 和适用的第三方声明存放在 `/usr/share/doc/timepin/`。[MPL-2.0 原文](./licenses/container/MPL-2.0.txt) · [Mozilla 源数据及其版本历史](https://hg.mozilla.org/projects/nss/log/tip/lib/ckfw/builtins/certdata.txt) · [Alpine 打包来源](https://gitlab.alpinelinux.org/alpine/aports/-/tree/master/main/ca-certificates)

更改依赖时，用 `go list -deps` 分别核对两个发布目标，用 `./android/gradlew -p android :app:dependencies --configuration communityRuntimeClasspath` 检查安卓最终依赖，再更新相应的原文和交付包。
