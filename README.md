# Web2BlueBuilder

Web2BlueBuilder is a Windows-friendly CLI tool that packages a plain HTML/CSS/JavaScript project into an Android APK with a native WebView shell and hardware-oriented bridge APIs.

It was originally built for embedded-system demos where a web dashboard needs to become a phone app quickly, while still being able to call Android native capabilities such as Bluetooth, speech recognition, text-to-speech, and local configuration storage.

## Features

- Package static web projects into Android APKs.
- Inject web assets into the Android `assets/web` directory automatically.
- Configure Android app name and application ID from CLI options.
- Provide native JavaScript bridge hooks for Android-side capabilities.
- Support classic Bluetooth SPP and BLE-oriented bridge architecture.
- Include a native debug console for hardware communication troubleshooting.
- Support speech recognition / TTS bridge extensions for voice interaction scenarios.
- Reuse the same web UI logic in browser-like development and Android WebView deployment.

## Project Structure

```text
Web2BlueBuilder/
├── packages/
│   ├── cli/                 # Node.js command-line builder
│   └── android-shell/       # Android WebView shell project
├── customer-demo/           # Small demo web project
├── web2blue.bat             # Windows launcher
├── Web2Blue_PRD.md          # Original product design notes
└── README.md
```

The repository intentionally excludes generated files and local dependencies such as:

- `output/` generated APK files
- `_staging/` temporary customer/project snapshots
- `downloads/` downloaded third-party archives
- `jdk17/` local JDK runtime
- `node_modules/`
- Android `build/` directories
- `packages/android-shell/app/src/main/assets/web/`, which is generated from `--src`
- large optional speech model assets
- `local.properties` and signing keys

## Requirements

- Windows 10/11
- Node.js 18 or later
- Android SDK with API 34 and Build Tools installed
- JDK 17 available through `JAVA_HOME`

The local development copy may use a bundled JDK, but the public repository does not include JDK binaries. Install JDK 17 separately or configure `JAVA_HOME`.

## Install Dependencies

```powershell
cd E:\Web2BlueBuilder\packages\cli
npm install
```

## Build an APK

Prepare a web project folder that contains an `index.html`, then run:

```powershell
cd E:\Web2BlueBuilder
.\web2blue.bat build --src C:\path\to\your-web-project --name "My Controller"
```

Optional package name:

```powershell
.\web2blue.bat build --src C:\path\to\your-web-project --name "My Controller" --package com.example.mycontroller
```

Optional output directory:

```powershell
.\web2blue.bat build --src C:\path\to\your-web-project --name "My Controller" --output C:\path\to\apk-output
```

The generated APK will be copied to the configured output directory.

## Create a Demo Web Project

```powershell
cd E:\Web2BlueBuilder\packages\cli
node src\index.js init my-blue-app
```

Then build it:

```powershell
cd E:\Web2BlueBuilder
.\web2blue.bat build --src .\packages\cli\my-blue-app --name "My Blue App"
```

## Native Bridge Concept

The Android shell loads local web assets in a WebView. Native Java code exposes bridge objects to JavaScript, so the web UI can request platform capabilities without rewriting the whole app as a native Android project.

Typical flow:

```text
HTML / JS / CSS
    ↓ copied into Android assets/web
Android WebView
    ↓ JavaScript Bridge
Native Android capabilities
    ↓
Bluetooth / speech recognition / TTS / secure config / debug console
```

For embedded projects, this means the same web dashboard can be reused as:

- a browser-side control panel during development;
- an APK for phone-based field demos;
- a protocol debugging surface for MCU communication.

## Security Notes

- Do not hard-code API keys in web files before publishing.
- Do not commit `local.properties`, keystores, or signing passwords.
- Debug APKs are for development and demo use only.
- Release APK signing should be configured locally by the user.

## License

MIT
