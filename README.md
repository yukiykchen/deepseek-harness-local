# DSH Local

在 Android 手机上运行内嵌 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的独立 App。

本工程只做 **手机本地** 模式：把 Node.js Runtime 和编译后的 Harness 打进 APK，在 `127.0.0.1:3080` 启动本机 Host。

扫码连接电脑或 SSH 请使用 **DSH Mobile**（`com.example.dsh`）。两个 App 包名不同，可以同时安装。

> 当前项目处于开发和验证阶段，包体较大（主要来自 `payload.zip`）、首次启动需要解压。请不要把它当作生产版本使用。

## 连接方式

| 模式 | Agent 跑在哪 | 传输 | API Key | 会话缓存 |
| --- | --- | --- | --- | --- |
| 手机本地 | 手机内嵌 Harness | 本机 HTTP + SSE | 手机 | `local` |

```text
Android App
  |
  +--> 启动内嵌 Node.js Runtime
          |
          +--> DeepSeek Harness Web Host
                  |
                  +--> 监听 127.0.0.1:3080

Android App -- HTTP RPC --> http://127.0.0.1:3080/api/*
Android App <-- SSE ------ http://127.0.0.1:3080/api/events.mux
```

模型推理仍走 DeepSeek 在线 API，需要网络和 API Key，不是离线大模型 App。

## 前置条件

### 1. 先启动 Shizuku

本机模式依赖 [Shizuku](https://github.com/RikkaApps/Shizuku)。使用前请在手机上安装并启动 Shizuku，确认状态为正在运行。

每次手机重启后，Shizuku 可能需要重新启动。App 不负责自动启动 Shizuku。

### 2. DeepSeek API Key

首次进入后在界面中配置 DeepSeek API Key。Key 写入 App 本地存储，再交给本机 Host。

不要把真实 API Key 写进 Git 或截图。

### 3. Git LFS

`androidApp/src/main/assets/payload.zip` 约 115 MB，使用 Git LFS 存储：

```bash
brew install git-lfs        # macOS
git lfs install
git lfs pull
ls -lh androidApp/src/main/assets/payload.zip
```

如果文件只有一百多字节，说明仍是 LFS 指针，需要再执行 `git lfs pull`。

## 构建

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

`applicationId` 为 `com.example.dsh.local`，桌面名 **DSH Local**。

首次启动会从 `payload.zip` 解压 Node.js、Harness 和依赖，可能较慢。运行时版本由 `androidApp/src/main/assets/dshroot_revision.txt` 控制。

## 工作原理

`DshEngineManager` 首次启动时把 `payload.zip` 解压到 App 私有目录，随后执行类似：

```text
<app-files>/dsh-engine/runtime/bin/node \
  <app-files>/dsh-engine/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  web --host 127.0.0.1 --port 3080
```

## 故障排查

页面一直显示「本地内核启动中」时，依次检查：

1. Shizuku 是否正在运行；
2. 是否是支持的 Android ABI 和版本；
3. APK 是否完整，资源是否解压失败；
4. App 私有目录中的 `dsh-engine.log`；
5. 是否有其他进程占用 `127.0.0.1:3080`；
6. 是否有足够的存储空间和可用内存。

清除 App 数据会强制重新解压运行时，同时删除本地 API Key 和会话：

```bash
adb shell pm clear com.example.dsh.local
```

## 许可证

DeepSeek Harness、Node.js Runtime 及其第三方依赖的许可证以上游项目为准。发布 APK 或重新分发 `payload.zip` 前请确认上游要求。
