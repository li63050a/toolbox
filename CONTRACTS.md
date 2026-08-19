# 开发契约（各模块子代理必读）

本文件定义模块间 API 约定。**只新增本模块文件，禁止修改**：
- `build.gradle.kts` / `settings.gradle.kts` / `AndroidManifest.xml`
- `data/`、`log/` 核心、`ui/filebrowser/`、`ui/theme/`、`ui/App.kt`、`ui/home/`、`ToolboxApp.kt`、`MainActivity.kt`
- 其他代理负责的目录

## 环境
- Kotlin 2.0.21 / Compose BOM 2024.09.03 / minSdk 26 / targetSdk 34
- **禁止运行 gradle，禁止联网下载库**，集成方负责编译
- 依赖已就绪：compose material3 + material-icons-extended、navigation-compose、datastore-preferences、kotlinx-serialization-json 1.7.3、okhttp 4.12、jsch 0.2.21（`com.jcraft.jsch.*` 或 `com.github.mwiede.jsch.*`，mwiede fork 包名仍为 com.jcraft.jsch）、commons-net 3.11.1、minio 8.5.12、aliyun-sdk-oss 3.17.4、cos-android-sdk 5.4.3（包名 `com.tencent.cos.xml.*`）、bcprov/bcpkix jdk18on 1.78.1、androidx.documentfile

## 数据层（data/ 已实现，只读）
`com.toolbox.app.data.ConnectionConfig`（sealed，字段见源码）：`Ssh(id,name,host,port,user,auth,pw/key…)`、`Ftp(id,name,host,port,user,pw,security(FtpSecurity),passive)`、`S3(id,name,endpoint,region,accessKeyId,secretKey,bucket,pathStyle,https)`、`Oss(id,name,endpoint,accessKeyId,accessKeySecret,bucket)`、`Cos(id,name,region,secretId,secretKey,bucket)`
- 注入：`RepositoryProvider.connections`（`ConnectionRepository`）
- API：`connections: Flow<List<ConnectionConfig>>`、`suspend add(c)`、`update(c)`、`delete(id)`、`get(id)`

## 日志（log/ 已实现，只读）
`com.toolbox.app.log.Log`（object）：
`d/i/w/e(tag: String, message: String, throwable: Throwable? = null)`；`entries: StateFlow<List<LogEntry>>`；`clear()`；`logDirFile()`
`LogEntry(level, time, tag, message, throwable, timeText, levelText)`；级别常量 `Log.LEVEL_DEBUG/INFO/WARN/ERROR`
**重要 IO 错误必须 Log.e。**

## 文件浏览器（ui/filebrowser/ 已实现，只读，SFTP/FTP/对象存储复用）
```kotlin
data class FileEntry(path, name, isDirectory, size, modified)  // 注意: path 可能含空格
interface FileOps {
    suspend fun list(path: String): Result<List<FileEntry>>
    suspend fun mkdir(path: String): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun rename(oldPath: String, newName: String): Result<Unit>
    suspend fun download(remotePath: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit>
    suspend fun upload(remoteDir: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit>
    fun rootPath(): String
    fun displayName(): String
    fun close()
}
@Composable fun FileBrowserScreen(ops: FileOps, onBack: () -> Unit)
```
进度回调线程不定，本地 Consumer 只更新内存状态。

## UI 规范
- 全中文界面
- 每个模块暴露**唯一顶层 composable**：`fun XxxScreen(onBack: () -> Unit)`，放 `ui/<module>/` 下；内部子页面用 `remember { mutableStateOf(...) }` 切换 + 返回箭头调 onBack；已 import `Icons.AutoMirrored.Filled.ArrowBack`
- 顶级页面统一 `Scaffold + TopAppBar`；Context 用 `LocalContext.current`；IO 一律 `Dispatchers.IO`；错误用 Snackbar 或 AlertDialog 展示，并 Log.e
- Compose import 规范：`@OptIn(ExperimentalMaterial3Api::class)` 按需加
- 不用自定义主题色，直接用 `MaterialTheme.colorScheme`

## 里程碑
骨架已编译通过（app-debug.apk 生成）。各模块完成后由集成方统一接线导航并编译修复。