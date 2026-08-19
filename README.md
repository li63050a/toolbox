# 工具箱（Toolbox）开发方案

> 本文件是项目开发指导文档，自动读取。所有实施必须遵循本方案。

## 项目概述

安卓全功能工具箱 App：SSH 终端、SFTP/FTP/FTPS 文件管理、对象存储（S3/OSS/COS）、全流量 VPN（改 DNS、改 hosts、SNI 防阻断、SNI 伪装）。

**目录**：`/data/home/admin1/work/apk/app/toolbox/`
**包名**：`com.toolbox.app`
**应用名**：工具箱
**界面语言**：中文

## 技术栈与版本

| 项 | 值 |
|---|---|
| Gradle | 8.9（本机 `/data/home/admin1/work/apk/gradle/gradle-8.9`） |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Java | 17 |
| minSdk | 26（Android 8.0） |
| targetSdk / compileSdk | 34 |
| UI | Compose + Material 3，单 Activity，Compose Navigation |
| 构建语言 | Kotlin DSL |

支持 Android 8.0（API 26）～ Android 16。

### 构建环境
```bash
source /data/home/admin1/work/apk/android-env-setup.sh
# 或
source /data/home/admin1/work/apk/env.sh
# 构建
./gradlew assembleDebug
# 校验 minSdk 兼容
./gradlew lint
```

依赖仓库走阿里云镜像（`/data/home/admin1/work/apk/gradle/init.d/aliyun-mirror.gradle` 已全局配置，项目内正常声明 google()/mavenCentral() 即可）。

## 功能模块

### 1. 连接管理（统一入口）
- 连接列表：SSH / FTP / SFTP / S3 / OSS / COS 六类配置
- 增删改查、测试连接、导入导出（JSON）
- 持久化：DataStore + kotlinx.serialization

### 2. SSH 终端 + SFTP
- 库：JSch（`com.github.mwiede:jsch`）
- SSH 远程终端：PTY shell，自写 VT100 渲染 View（颜色/光标/滚动/常用按键栏）
- 认证：密码 / 密钥
- SFTP：文件浏览、上传下载、新建/重命名/删除、权限修改

### 3. FTP / FTPS
- 库：Apache Commons Net
- 显式/隐式 TLS、主动/被动模式
- 与 SFTP 共用文件浏览器 UI

### 4. 对象存储（统一抽象 `ObjectStorage` 接口，三个后端）
| 后端 | 方案 |
|---|---|
| S3 兼容 | MinIO Java SDK（AWS/MinIO/R2 等通用） |
| 阿里云 OSS | 官方 `aliyun-sdk-oss`；若 Android 兼容有问题，回退手写 REST 签名客户端（HmacSHA1），接口不变 |
| 腾讯云 COS | 官方 `cos-android-sdk` |

功能：桶列表、对象浏览、上传/下载、新建目录、删除。

### 5. 全流量 VPN（核心，NetGuard 式架构，纯 Kotlin，零外部依赖）
```
VpnService(tun) ──► TCP/IP 中继线程 ──► 本地 socket 直连目标
                        │
                        └── 拦截 DNS(53) ──► DnsProxy(127.0.0.1:53)
                                               ├─ hosts 规则引擎
                                               └─ 上游转发：普通 DNS / DoT / DoH
```
- VpnService.Builder：路由 0.0.0.0/0 + ::/0，前台服务（Android 14 用 `vpn` 类型，声明 `FOREGROUND_SERVICE_VPN` 权限）
- TCP：本地代理握手、双向转发（IPv4+IPv6）
- UDP：DNS 拦截，其余透传
- DNS 上游三种：普通 DNS（UDP）、DoT（RFC 7858，SSLSocket）、DoH（RFC 8484，okhttp）
- hosts 引擎：① SAF 导入 hosts 文件；② 手工规则。每条规则"解析到指定 IP"或"屏蔽(0.0.0.0)"，可启停

### 6. SNI 防阻断（分片伪装）
- TCP 中继内识别 TLS 握手包（0x16 0x03 … ClientHello），分片发出：
  - 第 1 片：1~2 字节；后续片：16~64 字节/片
  - 可配：分片模式（只拆包）/ 分片+延时（片间 0~50ms）
- UDP QUIC 初始包同样分片
- 归属 VPN 模块 `TlsFragmentation.kt`

### 8. 日志（排查错误）
- 全局日志记录模块：`data/log/`（Logcat + 内存环形缓冲 + 文件落盘）
- 各模块埋点：VPN 连接/断连、DNS 查询结果、hosts 规则命中、SNI 分片/伪装动作、SSH/FTP/对象存储操作及错误堆栈
- UI：日志页面（列表查看、过滤级别/模块、复制、导出分享、清空）
- 崩溃捕获：Thread.setDefaultUncaughtExceptionHandler 落盘，启动时提示上次崩溃日志

### 9. SNI 伪装（A+B 组合）
- **方案 A（默认）**：改写 ClientHello 的 SNI 为配置的伪装域名，服务器宽松时直接过
- **方案 B（自动升级）**：证书校验站点 → 本地 TLS 终结 + 自签 CA：
  - 手机装自签 CA，回给手机真实域名证书（校验通过）
  - 出站用伪装 SNI 连真实服务器（自定义 TrustManager 按 IP 校验）
  - 剥 ALPN 强制 HTTP/1.1，避免 HTTP/2 复杂度
- 失败回落直连并提示
- 排除名单：名单内 App 直连不伪装
- CA 安装引导页面

## 已知边界
- IP 级封禁需中转服务器，超出本地工具范围
- MITM 对证书固定 App 自动回落直连
- 方案 B 需要用户手动安装自签 CA

## 项目结构
```
toolbox/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/toolbox/app/
│       │   ├── MainActivity.kt / App.kt（导航）
│       │   ├── ui/          —— 页面（home/ssh/ftp/oss/vpn/settings）
│       │   ├── data/        —— 连接配置仓库（DataStore）
│       │   ├── log/         —— 日志模块（环形缓冲 + 文件落盘 + 崩溃捕获）
│       │   ├── ssh/         —— JSch 终端 + SFTP 引擎
│       │   ├── ftp/         —— Commons Net 引擎
│       │   ├── oss/         —— ObjectStorage 接口 + S3/OSS/COS 后端
│       │   ├── vpn/         —— VpnService / TcpIp / DnsProxy / DoH / DoT / Hosts / TlsFragmentation / SniSpoof / Mitm
│       │   └── ui/term      —— 自定义 VT100 终端 View
│       └── res/
└── AGENTS.md
```

## 实施顺序（每步保证可编译）
1. 项目骨架：Gradle 配置、Manifest、主题、首页导航
2. VPN 模块（风险最大先行）：VpnService + TCP/IP 中继 → DNS 代理 → DoH/DoT → hosts → SNI 分片 → SNI 伪装 A → MITM B → VPN 设置页
3. SSH 终端 + SFTP：连接管理 → JSch 引擎 → 终端 View → 文件浏览器
4. FTP/FTPS：引擎 + 复用文件浏览器
5. 对象存储：抽象接口 + S3/OSS/COS 后端 + 浏览器页面
6. 日志模块：环形缓冲 + 文件落盘 + 崩溃捕获 + 日志页面（与 VPN 并行实施，方便后续排查）
7. 收尾：导入导出、设置页、`./gradlew assembleDebug` + `lint` 出包验证
