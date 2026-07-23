# Web2BlueBuilder

一个面向嵌入式项目演示的 HTML / CSS / JavaScript 一键打包 Android APK 工具。

Web2BlueBuilder 可以把普通网页项目打包成 Android WebView 应用，并通过 JavaScript Bridge 接入 Android 原生能力，例如蓝牙通信、语音识别、TTS 播报、本地配置保存和硬件调试控制台。

> English: A Windows-friendly CLI that packages web projects into Android WebView APKs with native bridge capabilities for embedded-system demos.

## 项目定位

很多嵌入式项目会先做一个网页端控制面板，例如单片机状态监控、机器人地图显示、设备参数配置等。但比赛或现场演示时，评委和用户更希望看到一个可以直接安装的手机 APK。

Web2BlueBuilder 的目标就是把这一步自动化：

```text
网页项目 HTML / CSS / JS
        ↓ 自动注入
Android assets/web
        ↓ WebView 加载
Android APK
        ↓ JavaScript Bridge
蓝牙 / 语音 / TTS / 本地配置 / 调试控制台
```

这样前端页面不用重写成原生 Android，也能快速变成一个具备原生能力的 APK。

## 核心特性

- 一键将静态网页项目打包成 Android APK。
- 自动注入网页资源到 Android `assets/web` 目录。
- 支持通过命令行设置应用名称、包名和输出目录。
- Android WebView 加载本地网页，适合离线现场演示。
- 提供 JavaScript Bridge，方便网页调用 Android 原生能力。
- 支持经典蓝牙 SPP 和 BLE 方向的通信桥接架构。
- 内置硬件调试控制台，方便查看 TX/RX 报文和连接状态。
- 支持语音识别、TTS、云端配置等扩展桥接能力。
- 网页端和 APK 端可以复用同一套界面、协议和业务逻辑。

## 适用场景

- 嵌入式比赛项目展示。
- STM32 / ESP32 / Arduino 等下位机控制面板。
- 蓝牙设备调试工具。
- 机器人地图显示与任务控制 APK。
- 将已有网页控制台快速转换为 Android 应用。
- 需要 WebView + 原生能力桥接的轻量 Hybrid App。

## 项目结构

```text
Web2BlueBuilder/
├── packages/
│   ├── cli/                 # Node.js 命令行构建工具
│   └── android-shell/       # Android WebView 壳工程
├── customer-demo/           # 示例网页项目
├── web2blue.bat             # Windows 启动脚本
├── Web2Blue_PRD.md          # 原始产品设计说明
├── README.md
└── LICENSE
```

## 环境要求

- Windows 10 / Windows 11
- Node.js 18 或更高版本
- Android SDK，建议包含 API 34 和 Build Tools
- JDK 17

说明：本地开发时可以放置自己的 JDK，但公开仓库不会包含 `jdk17/` 这类本地运行时文件。请自行安装 JDK 17，或配置 `JAVA_HOME`。

## 安装依赖

```powershell
cd E:\Web2BlueBuilder\packages\cli
npm install
```

## 快速开始

准备一个网页项目目录，里面至少包含 `index.html`，然后运行：

```powershell
cd E:\Web2BlueBuilder
.\web2blue.bat build --src C:\path\to\your-web-project --name "我的控制器"
```

指定 Android 包名：

```powershell
.\web2blue.bat build --src C:\path\to\your-web-project --name "我的控制器" --package com.example.mycontroller
```

指定 APK 输出目录：

```powershell
.\web2blue.bat build --src C:\path\to\your-web-project --name "我的控制器" --output C:\path\to\apk-output
```

构建完成后，APK 会复制到输出目录。

## 创建示例网页项目

```powershell
cd E:\Web2BlueBuilder\packages\cli
node src\index.js init my-blue-app
```

然后回到项目根目录构建：

```powershell
cd E:\Web2BlueBuilder
.\web2blue.bat build --src .\packages\cli\my-blue-app --name "My Blue App"
```

## Web 与 Android 原生桥接

Web2BlueBuilder 的核心思想不是简单“套壳”，而是让网页保留开发效率，同时通过 Android 原生层补足浏览器做不到或不稳定的能力。

典型链路：

```text
网页按钮 / JS 逻辑
        ↓
BlueApp / Native Bridge
        ↓
Android Java 原生代码
        ↓
蓝牙连接、串口透传、语音识别、TTS、配置保存、调试日志
```

在嵌入式项目中，这种方式可以让网页端和 APK 端复用同一套协议逻辑。例如机器人项目中，网页负责地图显示和任务交互，APK 则额外获得麦克风、TTS、离线语音等原生能力。

## 和普通 WebView 套壳的区别

普通 WebView 套壳通常只是把网页放进 App 里显示，而 Web2BlueBuilder 更强调嵌入式项目需要的工程能力：

- 构建过程自动化，不需要每次手动改 Android 工程。
- 支持应用名、包名、网页资源注入和 APK 输出。
- Android 原生层提供硬件相关能力。
- 调试控制台可以帮助定位单片机通信问题。
- 适合比赛现场快速打包、迭代和演示。

## 开源仓库未包含的内容

为了避免仓库过大和泄露本地配置，本仓库不会提交以下内容：

- `output/` 生成的 APK。
- `_staging/` 临时项目快照。
- `downloads/` 下载包。
- `jdk17/` 本地 JDK。
- `node_modules/`。
- Android `build/` 目录。
- `local.properties`。
- 签名文件、密钥和密码。
- 构建时注入的具体项目网页资源。
- 大体积离线语音模型资源。

如果需要离线语音模型，请根据项目实际需要自行下载并放入对应 assets 目录。

## 安全说明

- 不要在网页文件中硬编码 API Key。
- 不要提交 Android 签名文件、签名密码或 `local.properties`。
- Debug APK 只适合开发和演示，不适合正式发布。
- Release APK 请在本地自行配置签名。

## License

MIT
