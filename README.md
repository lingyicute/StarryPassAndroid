# 星愿令 StarryPass for Android

> **规范一个字节不变，原语 100% 源自经审计的标准密码学库。**  
> 在网页版 StarryPass v2 / v2.1 派生出的每一个密码，在本 Android 客户端上都 **逐字节完全一致**。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPLv3-blue.svg)](./LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026--35)-3DDC84.svg)](#)
[![UI: Material You (M3)](https://img.shields.io/badge/UI-Material%20You%20(Monet)-6750A4.svg)](#)

---

## 📦 获取

可在 GitHub **Releases** 页面获取发行版本与自动构建的 **Nightly** 版本（每次推送至 `main` 分支均由 GitHub Actions 自动运行全量兼容性单测并发布未签名的 `app-release-unsigned.apk`）。

> [!IMPORTANT]
> 对于 Nightly 构建版本（`app-release-unsigned.apk`），项目不内置托管任何签名密钥，请自行对 APK 实施签名后再安装。

- **APK 权限清单**：仅 `USE_BIOMETRIC` / `USE_FINGERPRINT`，**零 `INTERNET` 网络权限、零读写存储权限**。

---

## 🔐 密码学架构 & 兼容性

### 1. 密码学原语替换对照表

| 组件 | 网页版 v2（PoC） | 网页版 v2.1（Web 生产级） | **本仓库（Android 生产级）** | 审计与行业采用背景 |
| --- | --- | --- | --- | --- |
| **Argon2id v1.3**（RFC 9106） | 手写 JS + 手写 WASM | `@noble/hashes 2.4.0` | **Bouncy Castle `1.86`**（`Argon2BytesGenerator`） | 经 NCC Group（OSTIF）独立密码学审计、具 NIST FIPS 140-3 认证血统；Spring Security、KeePassDX、Proton、AOSP 采用 |
| **HKDF-SHA-256**（RFC 5869） | 手写循环 | Web Crypto `deriveBits` | **Bouncy Castle `1.86`**（`HKDFBytesGenerator`） | 标准状态化 RFC 5869 流式实现（单元测试中额外使用 **Google Tink `1.24.0`** 交叉验证） |
| **SHA-256**（FIPS 180-4） | 手写实现 | Web Crypto `digest` | **Android JCA** `MessageDigest("SHA-256")` | Android 底层由 Google **BoringSSL（Conscrypt）** 原生驱动 |
| **HMAC-SHA-256**（RFC 2104） | 手写实现 | Web Crypto `sign` | **Android JCA** `Mac("HmacSHA256")` + `MessageDigest.isEqual` | BoringSSL 驱动 + 常量时间（Constant-Time）摘要比对 |
| **会话真随机数（CSPRNG）** | `getRandomValues` | `crypto.getRandomValues` | **Android JCA** `java.security.SecureRandom` | BoringSSL / Linux 内核 `getrandom(2)` 熵源 |
| **硬件主密码托管（可选）** | 无 | 无 | **Android Keystore**（`AES-256-GCM` TEE / StrongBox）+ `BiometricPrompt.CryptoObject` | 硬件隔离不可导出密钥，每次解密强制 Class 3 强生物识别（指纹或 3D 面容）逐次授权 |

> [!tip]
> 为何不选用 `libsodium` 高层 API 或 `argon2kt`？
>
> StarryPass v2 在每次 Argon2id 派生时均传入了 RFC 9106 **关联数据（Associated Data `X`）** `StarryPass-v2:argon2id-ad:v1` 且固定并行度 `p = 4`，并在开机自检中校验同时带 `secret (K)` 与 `ad (X)` 的 RFC 9106 §5.3 KAT 向量。市面上部分简化封装（如 `libsodium` 的 `crypto_pwhash` 固定 `p = 1` 且不支持 `K` / `X`、`argon2kt` JNI 未暴露 `ad` / `secret` 参数）**会导致派生结果与网页版不一致**。Bouncy Castle `Argon2Parameters.Builder` 完整实现了 RFC 9106 全部参数（`withSalt` / `withSecret` / `withAdditional`）及敏感缓冲清零（`clear()`）。

### 2. 派生规格

1. **非对称规范化**：
   - `master`（主密码）：**绝不 `trim`**（保留首尾空格），仅做 Unicode `NFC` 归一化并编码为 UTF-8；
   - `code`（站点代号）：**先按 ECMA-262 空白字符集（含 `\uFEFF`、`\u3000`、`\u00A0`）`trim`**，再做 Unicode `NFC` 归一化，且 **严格区分大小写**；
   - 未配对的 UTF-16 代理项按网页版 `TextEncoder` 替换为 `U+FFFD`。旧版 Android 可能将这类异常输入编码为 `?`；如果曾使用此类输入，请在升级前使用旧版恢复密码并迁移。
2. **域分离常量（含末尾 NUL `\x00` 字节精确对齐）**：
   - `DOMAIN_SALT` = `"StarryPass-v2:salt\x00"`（19 字节）
   - `DOMAIN_AD` = `"StarryPass-v2:argon2id-ad:v1"`（28 字节，无 `\x00`）
   - `DOMAIN_EXTRACT` = `"StarryPass-v2:hkdf-extract:v1"`（29 字节，无 `\x00`）
   - `DOMAIN_EXPAND` = `"StarryPass-v2:hkdf-expand:v1\x00"`（29 字节）
   - `DOMAIN_CACHE` = `"StarryPass-v2:session-cache-tag:v1\x00"`（35 字节）
3. **单槽会话缓存 + 协程互斥队列（`Mutex`）**：
   - 使用会话随机 Nonce 计算 `HMAC-SHA-256` 标签缓存 32 字节会话密钥 `sk`（不驻留主密码明文字节）；当 `(master, code, tier)` 不变而修改轮换号、长度或字符集时 **`< 1 ms` 命中缓存**，无需重跑 Argon2id。
   - 全部 `derive` 与 `wipeSession` 通过单一协程 `Mutex` 严格串行化，杜绝并发擦除竞态。
4. **零模偏差拒绝采样 + Fisher-Yates 确定性兜底**：
   - 按 `limit = 256 - (256 % n)` 对单字节流无偏采样，最多 128 轮类别完备性验证，未命中时执行与网页版完全一致的必选字符类注入 + Fisher-Yates 洗牌兜底。
5. **开机密码学自检（FIPS 140-3 风格 KAT）**：
   - 首次派生前自动执行 **RFC 9106 §5.3 Argon2id KAT** 与 **RFC 5869 Test Case 1 HMAC / HKDF KAT**，不通过则拒绝派生。

### 3. 兼容性测试集

网页版仓库中的测试文件已原封不动纳入 `app/src/test/resources/` 并由 JUnit 自动验证：

- ✅ **网页版测试向量 `vectors.json`（7 / 7 条）**：含中英文口令、首尾空格代号、NFC / NFD 等价对（`café` vs `cafe\u0301`）、PIN 模式、256 MiB `paranoid` 档
- ✅ **网页版黄金测试集 `golden.json`（44 / 44 条）**：覆盖连字（`ﬁ`）、Emoji（`🦄🔒`）、阿拉伯语 RTL、全角字符、超长输入、`counter` / `length` 越界截断与非数字回退、未知 `mode` / `tier` 默认回退、`EMPTY_INPUT` 错误路径
- ✅ **跨库交叉校验**：Bouncy Castle `HkdfStream` 多块输出与 Google Tink `Hkdf.computeHkdf` 对比

---

## 🎨 Material You 设计与 Android 原生特性

- **原生 Material You（Material 3）动态取色与平滑过渡**：
  - Android 12+ 默认开启 **Monet 动态壁纸取色**（`dynamicLightColorScheme` / `dynamicDarkColorScheme`），Android 8 ~ 11 及关闭动态取色时回退至星愿蓝 M3 调色板（`#415F91` / `#AAC7FF`）；
  - 全量 36 个色彩槽位支持平滑渐变过渡动画，支持 **跟随系统 / 浅色 / 深色** 切换、微光氛围径向渐变背景及 Android 13+ **Material You 主题单色桌面图标（Monochrome Adaptive Icon）**；
  - 保留网页版快捷长度档位（`16` / `20` / `24`，PIN 模式自动切 `6` 位）的同时，提供 **4–64 位自定义滑块**、密码字母与数字及符号主题色语法高亮、一键遮罩开关；
  - 完整支持 **简体中文 / English** 双语资源及 Android 13+ 应用内独立语言设置（`localeConfig`）。
- **多层 Android 系统安全加固**：
  - **内核级零网络隔离**：`AndroidManifest.xml` 不声明 `INTERNET` 权限，Linux 内核强制阻断任何套接字联网可能；
  - **防截屏与多任务遮罩**：默认开启 `WindowManager.LayoutParams.FLAG_SECURE`；
  - **敏感剪贴板保护与自动清空**：写入剪贴板时附带 Android 13+ `ClipDescription.EXTRA_IS_SENSITIVE` 隐藏系统剪贴板浮窗明文，并支持倒计时自动抹除剪贴板；
  - **退后台自动抹除**：启用此设置时，`ProcessLifecycleOwner` 监听整个应用退至后台事件（旋转屏幕不误清），先清除界面中的秘密、取消在途操作并使迟到的生物识别回调失效；Bouncy Castle 的同步 Argon2 无法立即中断，须等待当前计算结束后才能清除会话密钥和 HMAC 标签。JVM 字符串无法保证物理覆盖。
- **可选站点策略预设记忆（零主密码存储）**：
  - 用户可保持 100% 纯无状态使用，也可将常用的「代号 + 别名 + 长度 + 字符集 + 轮换序号 + 档位 + 匹配域名或包名」保存为本地预设（结构上不包含主密码字段），支持某站泄露时 **一键轮换 `+1`**。
- **可选 Android Keystore 硬件生物识别保险箱**：
  - 默认关闭（严格零存储）；开启后使用 `AndroidKeyStore` 硬件不可导出 `AES-256-GCM` 密钥加密主密码，`setInvalidatedByBiometricEnrollment(true)` 防止新录入生物识别特征越权；
  - 启用和每次解锁前均检查 `KeyInfo`：Android 12 及以上要求 TEE 或 StrongBox，旧版本要求密钥位于安全硬件中。软件密钥、未知级别或无法验证时不会启用或解锁；已有保险箱不会因此自动删除，可在设置中手动撤销；
  - 基于 `BiometricPrompt` + `BIOMETRIC_STRONG` + `CryptoObject`，原生支持所有 **指纹识别** 以及通过 Google Android CDD Class 3 安全认证的 **3D 面部识别 / 虹膜识别**（注：普通前置单摄 2D 人脸属于 Class 2 弱生物识别，Android 硬件 Keystore 底层禁止其解锁加密密钥 `CryptoObject`，以确保真硬件级密码学隔离）。
- **系统级自动填充服务（Android Autofill Service + 键盘内联建议）**：
  - 支持在浏览器（自动提取 `webDomain`）与第三方 App（自动提取包名并匹配站点预设）密码框直接唤起悬浮 Material You 面板，可在面板内自由切换预设或调整策略参数，配合生物识别一键完成现场 Argon2id 派生并填入目标输入框，全程不经过系统剪贴板。

### 诚实的安全边界

在 JVM / Android ART 运行时上：

1. 本项目对自身持有的全部 `ByteArray` / `CharArray`（规范化 UTF-8 密码字节、盐、HKDF 块、会话密钥 `sk`、Bouncy Castle `Argon2Parameters` 克隆缓冲）均在 `finally` 中显式执行清零（`fill(0)`）。
2. 由于 Jetpack Compose 文本输入组件（`OutlinedTextField`）与 Android `AutofillValue.forText` 底层基于不可变的 `java.lang.String`，且 ART 垃圾回收器可能在堆整理时移动对象副本，应用层无法触及虚拟机或输入法内部的临时字符串驻留副本 —— 这是所有基于现代高级托管运行时与标准密码学库的软件共同的诚实安全边界。

---

## 🛠️ 构建与测试指南

环境要求：**JDK 17+** 与 **Android SDK（API 35）**。

```bash
# 1. 运行全量兼容性单元测试（7 条网页版向量 + 44 条黄金用例 + RFC KAT 自检 + Tink 交叉验证）
./gradlew testDebugUnitTest

# 2. 构建 Debug 与 R8 压缩优化的 Release 安装包
./gradlew assembleDebug assembleRelease
```

---

## 📄 许可
  
Copyright (C) 2024-2026 lingyicute

本项目以 **GNU Affero General Public License v3.0（AGPL-3.0-or-later）** 协议开源，详见 [LICENSE](./LICENSE)；第三方开源组件声明详见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。
