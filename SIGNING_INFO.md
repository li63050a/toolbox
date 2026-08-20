# 应用签名密钥信息

## ⚠️ 重要：请保存此文件

### Debug 签名（当前使用）
- **密钥库文件**: `~/.android/debug.keystore`
- **密钥库密码**: `android`
- **密钥别名**: `androiddebugkey`
- **密钥密码**: `android`
- **创建日期**: 2026-08-18
- **SHA1**: `64:BB:EA:23:DA:7B:11:B0:98:31:F8:FB:B4:8F:73:06:1C:AD:01:09`
- **SHA256**: `88:64:5D:10:05:C0:B3:2A:14:FC:BB:89:08:DA:06:E7:D3:D9:27:24:88:A6:69:35:62:BE:68:12:1E:10:9A:16`

### 备份方法
```bash
# 复制 debug keystore
cp ~/.android/debug.keystore ~/Desktop/

# 或打包到压缩包
tar -czvf toolbox-debug-signing.tar.gz ~/.android/debug.keystore
```

### 创建正式 Release 签名（推荐）
```bash
keytool -genkeypair -v \
  -dname "CN=Your Name, OU=Dev, O=YourCompany, L=City, ST=State, C=CN" \
  -alias toolbox \
  -keypass yourpassword \
  -keystore toolbox-release.keystore \
  -storepass yourpassword \
  -keysize 2048 \
  -validity 10000
```

### 配置 Release 签名
在 `app/build.gradle.kts` 中添加：
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../toolbox-release.keystore")
            storePassword = "yourpassword"
            keyAlias = "toolbox"
            keyPassword = "yourpassword"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
        }
    }
}
```

## 🔒 安全提示
1. 不要将密钥文件提交到 Git
2. 密钥文件应加密存储在安全位置
3. 建议创建多个备份
4. 考虑使用硬件安全密钥 (YubiKey)

## 📱 应用信息
- **包名**: `com.toolbox.app`
- **应用名称**: Toolbox
- **版本**: 0.0.0.1
