package org.timepin.app

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

private const val limit = 64L * 1024 * 1024
private const val checkInterval = 12L * 60 * 60 * 1000

@Suppress("DEPRECATION")
private fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signers() =
    (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
        .orEmpty().map { it.toCharsString() }.toSet()

@Suppress("DEPRECATION")
internal fun PackageManager.readSignedUpdate(file: File, pkg: String): Pair<PackageInfo, PackageInfo> {
    // MIUI 12 on Android 10 only collects archive certificates when GET_SIGNATURES is also set.
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES
    val current = getPackageInfo(pkg, flags)
    val archive = getPackageArchiveInfo(file.path, flags)
    val trusted = current.signers()
    require(archive != null && archive.packageName == pkg && trusted.isNotEmpty() && archive.signers() == trusted) {
        "安装包的身份或签名与本应用不符"
    }
    return archive to current
}

internal class AppUpdates(private val app: MainActivity) {
    private val pkg get() = app.packageName
    private val installed get() = app.packageManager.getPackageInfo(pkg, 0)
    private var checking = false
    private var downloading = false
    private var awaitingFocus: Pair<Account, JSONObject>? = null
    private var afterPermission: Pair<Account, File>? = null

    fun page(): View = with(app) {
        val box = padded()
        val head = padded().apply { background = shape(panel, 20) }
        head.addView(medium("TimePin", 25))
        head.addView(text("安卓版本 " + installed.versionName + "（" + installed.code() + "）"))
        head.addView(text("作者  " + getString(R.string.timepin_author), 14, muted))
        box.addView(head)
        val account = Account.read(this)
        val status = if (account == null) "登录服务器后可检查更新" else "从已连接的服务器获取"
        box.addView(settingRow("检查更新", R.drawable.ic_sync, groupInk(SettingGroup.ABOUT)) { check(true) })
        box.addView(text(status, 13, muted))
        box
    }

    fun showAvailableUpdate() {
        val (account, release) = awaitingFocus ?: return
        if (!app.hasWindowFocus()) return
        awaitingFocus = null
        if (Account.read(app) == account) offer(account, release)
    }

    fun foreground() {
        val pending = afterPermission
        if (pending != null) {
            afterPermission = null
            if (Account.read(app) == pending.first && app.packageManager.canRequestPackageInstalls()) install(pending.second)
            return
        }
        check(false)
    }

    private fun check(manual: Boolean) {
        val account = Account.read(app)
        if (account == null) {
            if (manual) app.message("请先在「账号与同步」登录服务器")
            return
        }
        if (checking || downloading) {
            if (manual) app.message("正在检查或下载更新")
            return
        }
        val prefs = app.getSharedPreferences("app-updates", 0)
        val key = account.origin + "/" + account.user
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(key, 0) in 0 until checkInterval) return
        checking = true
        app.work(Store.net, { runCatching { account.api("releases/" + pkg + "/latest").optJSONObject("release") } }) { result ->
            checking = false
            if (Account.read(app) != account) return@work
            result.onSuccess { r ->
                prefs.edit().putLong(key, now).apply()
                if (r != null && r.getString("packageName") == pkg && r.getLong("versionCode") > installed.code()) {
                    awaitingFocus = account to r
                    showAvailableUpdate()
                } else if (manual) app.message(if (r == null) "服务器尚未发布此应用的安装包" else "当前已是最新版本")
            }.onFailure {
                // Retry transient failures on a later foreground entry.
                prefs.edit().putLong(key, now - checkInterval + 5 * 60 * 1000L).apply()
                if (manual) app.message(if (it is HttpFailure && it.status == 404) "此服务器尚未支持应用更新" else it.message ?: "检查更新失败")
            }
        }
    }

    private fun offer(account: Account, release: JSONObject) {
        val size = release.getLong("bytes")
        val description = listOf(release.optString("notes"), "%.1f MiB · %s".format(size / 1048576.0, account.endpoint))
            .filter { it.isNotBlank() }.joinToString("\n\n")
        app.calendarDialog().setTitle("发现新版本 " + release.getString("versionName")).setMessage(description)
            .setNegativeButton("稍后", null).setPositiveButton("下载并安装") { _, _ ->
                if (downloading || Account.read(app) != account) return@setPositiveButton
                downloading = true
                app.message("正在从服务器下载安装包")
                app.work(Store.net, { runCatching { download(account, release) } }) { result ->
                    downloading = false
                    if (Account.read(app) != account) return@work
                    result.onSuccess { file ->
                        if (app.packageManager.canRequestPackageInstalls()) install(file)
                        else app.calendarDialog().setTitle("允许安装 TimePin 更新")
                            .setMessage("在系统设置中允许 TimePin 安装应用，返回后继续更新。")
                            .setNegativeButton("取消", null).setPositiveButton("打开系统设置") { _, _ ->
                                afterPermission = account to file
                                runCatching { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + pkg))) }
                                    .onFailure { afterPermission = null; app.message("请在系统设置中允许 TimePin 安装应用") }
                            }.show()
                    }.onFailure { app.message(it.message ?: "下载失败") }
                }
            }.show()
    }

    @Suppress("DEPRECATION")
    internal fun download(account: Account, r: JSONObject): File {
        val code = r.getLong("versionCode")
        val size = r.getLong("bytes")
        val sha = r.getString("sha256")
        require(r.getString("packageName") == pkg && code > installed.code() && size in 1..limit &&
            sha.matches(Regex("[0-9a-f]{64}"))) { "更新信息无效" }
        val dir = File(app.cacheDir,"updates").apply { mkdirs() }
        val target = File(dir, sha + ".apk")
        val temp = File.createTempFile("download-", ".part", dir)
        val conn = URI(baseUrl(account.endpoint, calendarServer = true) + "/api/v1/releases/" + pkg + "/" + code + "/apk").toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.setRequestProperty("Cookie", account.cookie)
            require(conn.responseCode == 200) { "此版本无法下载，请重新检查更新" }
            val hash = MessageDigest.getInstance("SHA-256")
            var received = 0L
            conn.inputStream.use { input -> temp.outputStream().use { output ->
                val buf = ByteArray(32768)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    received += n
                    require(received <= size) { "安装包大小不符" }
                    hash.update(buf,0,n)
                    output.write(buf,0,n)
                }
            } }
            require(received == size && hash.digest().joinToString("") { "%02x".format(it) } == sha) { "安装包校验失败" }
            val (archive, current) = app.packageManager.readSignedUpdate(temp, pkg)
            require(archive.code() == code && archive.versionName == r.getString("versionName") && code > current.code()) {
                "安装包版本与发布信息不符，或手机已安装此版本"
            }
            check(temp.renameTo(target)) { "无法保存已校验的安装包" }
            dir.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach { it.delete() }
            return target
        } finally { conn.disconnect(); temp.delete() }
    }

    private fun install(file: File) {
        val uri = Uri.Builder().scheme("content").authority(pkg + ".updates").appendPath(file.name).build()
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { app.message("无法打开系统安装器") }
    }
}
