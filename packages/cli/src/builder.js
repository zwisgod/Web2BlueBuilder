// 文件路径：packages/cli/src/builder.js
// 构建编排器 — CLI 的"总指挥"，按顺序调度所有构建步骤

'use strict';

const path         = require('path');
const fs           = require('fs-extra');
const chalk        = require('chalk');
const injector     = require('./injector');
const configurator = require('./configurator');
const gradleRunner = require('./gradle-runner');
const androidEnv   = require('./android-env');

// ─── 路径常量：CLI 包相对于 android-shell 的位置 ──────────────────
// packages/cli/src/builder.js → ../../android-shell
const ANDROID_SHELL_DIR = path.resolve(__dirname, '../../android-shell');

// ════════════════════════════════════════════════════════════════════
//  build — 主构建入口（由 index.js 的 build 子命令调用）
// ════════════════════════════════════════════════════════════════════

/**
 * 执行完整的打包流程：验证 → 配置 → 注入 → 构建 → 输出。
 *
 * @param {object} options           Commander 解析后的命令行选项
 * @param {string} options.src       用户 HTML 项目目录路径
 * @param {string} options.name      应用显示名称
 * @param {string} [options.package] 应用包名（可选，自动生成）
 * @param {string} options.output    APK 输出目录
 * @param {boolean} options.release  是否构建 Release 包
 */
async function build(options) {
    const startTime = Date.now();

    // ── Step 0：标准化和验证参数 ──────────────────────────────────
    const buildConfig = await validateAndNormalize(options);

    printStep(1, 5, '配置 Android 工程');
    await configurator.configure(ANDROID_SHELL_DIR, {
        appName:     buildConfig.appName,
        packageName: buildConfig.packageName,
    });
    printDone('Android 工程配置完成');

    const envInfo = await androidEnv.prepareAndroidEnv(ANDROID_SHELL_DIR);
    console.log(chalk.gray(`       SDK 目录：${envInfo.sdkDir}`));
    if (envInfo.javaHome) {
        console.log(chalk.gray(`       JAVA_HOME：${envInfo.javaHome}`));
    }
    printDone('Android 环境准备完成');

    // ── Step 2：注入用户 HTML 文件 ────────────────────────────────
    printStep(2, 5, `注入用户 HTML 项目：${buildConfig.srcPath}`);
    const webAssetsDir = path.join(
        ANDROID_SHELL_DIR,
        'app/src/main/assets/web'
    );
    const fileCount = await injector.inject(buildConfig.srcPath, webAssetsDir);
    printDone(`已注入 ${fileCount} 个文件 → ${webAssetsDir}`);

    // ── Step 3：执行 Gradle 构建 ──────────────────────────────────
    printStep(3, 5, `执行 Gradle 构建（${buildConfig.buildType}）`);
    const expectedApkPath = path.join(
        ANDROID_SHELL_DIR,
        `app/build/outputs/apk/${buildConfig.buildType}/app-${buildConfig.buildType}.apk`
    );
    const previousApkStat = await fs.pathExists(expectedApkPath)
        ? await fs.stat(expectedApkPath)
        : null;
    let gradleError = null;
    try {
        await gradleRunner.run(ANDROID_SHELL_DIR, buildConfig.buildType, envInfo);
        printDone('Gradle 构建成功');
    } catch (err) {
        // Gradle 异常退出时先不立刻抛错，检查 APK 文件是否已生成。
        // 若 APK 存在说明构建实际已完成（如守护进程 IPC 关闭导致的误报），继续执行。
        // 若 APK 不存在才视为真正失败。
        gradleError = err;
        console.warn(chalk.yellow(
            `  ⚠  Gradle 退出异常，正在检查 APK 是否已生成...`
        ));
    }

    // ── Step 4：将 APK 复制到输出目录 ────────────────────────────
    printStep(4, 5, '复制 APK 到输出目录');

    // 预先检查 APK 是否存在（无论 Gradle 是否正常退出都检查）
    const apkExists = await fs.pathExists(expectedApkPath);
    const currentApkStat = apkExists ? await fs.stat(expectedApkPath) : null;

    if (!apkExists && gradleError) {
        // APK 不存在且 Gradle 确实报错：重新抛出原始错误
        throw gradleError;
    }
    if (!apkExists) {
        throw new Error(
            `找不到构建产物 APK：${expectedApkPath}\n` +
            `  Gradle 报告成功但 APK 文件不存在，请检查构建配置。`
        );
    }
    if (gradleError) {
        const apkLooksUpdated = !previousApkStat || (
            currentApkStat &&
            (
                currentApkStat.mtimeMs > previousApkStat.mtimeMs ||
                currentApkStat.size !== previousApkStat.size
            )
        );

        if (!apkLooksUpdated) {
            throw new Error(
                `Gradle 退出异常，且 APK 未更新：${expectedApkPath}\n` +
                `  已阻止继续沿用旧包，避免把历史构建误判为本次成功。\n` +
                `  原始错误：${gradleError.message}`
            );
        }

        // APK 存在但 Gradle 有异常退出：给出警告，继续搬运
        console.warn(chalk.yellow(
            `  ⚠  Gradle 退出异常，但 APK 文件已存在，继续执行搬运操作。`
        ));
    }

    const apkPath = await copyApkToOutput(
        ANDROID_SHELL_DIR,
        buildConfig.buildType,
        buildConfig.appName,
        buildConfig.outputDir
    );
    printDone(`APK 已就绪`);

    // ── Step 5：打印最终成功信息 ──────────────────────────────────
    printStep(5, 5, '构建完成');
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

    console.log('\n' + chalk.bgGreen.black(' ✔ 构建成功！') + '\n');
    console.log(chalk.bold('  📦 APK 路径：') + chalk.cyan(apkPath));
    console.log(chalk.bold('  📱 应用名称：') + chalk.white(buildConfig.appName));
    console.log(chalk.bold('  📌 包    名：') + chalk.white(buildConfig.packageName));
    console.log(chalk.bold('  ⏱  耗    时：') + chalk.white(`${elapsed}s`));
    console.log('');
    console.log(chalk.gray('  用 adb install "' + apkPath + '" 安装到设备'));
    console.log('');
}

// ════════════════════════════════════════════════════════════════════
//  initExample — 初始化示例 HTML 项目（web2blue init 命令）
// ════════════════════════════════════════════════════════════════════

/**
 * 在当前目录创建一个带有 BlueApp API 示例的 HTML 项目。
 *
 * @param {string} projectName 项目目录名
 */
async function initExample(projectName) {
    const targetDir = path.resolve(process.cwd(), projectName);

    if (await fs.pathExists(targetDir)) {
        throw new Error(`目录已存在：${targetDir}，请先删除或换一个名称`);
    }

    await fs.ensureDir(targetDir);

    // 写入示例 index.html（含 BlueApp API 使用示范）
    await fs.writeFile(
        path.join(targetDir, 'index.html'),
        EXAMPLE_HTML,
        'utf8'
    );

    // 写入示例配置文件
    await fs.writeJSON(
        path.join(targetDir, 'web2blue.config.json'),
        { name: projectName, package: `com.web2blue.${projectName.toLowerCase()}` },
        { spaces: 2 }
    );

    console.log(chalk.green(`✔ 示例项目已创建：${targetDir}`));
    console.log(chalk.gray(`  cd ${projectName}`));
    console.log(chalk.gray(`  web2blue build --src . --name "${projectName}"`));
}

// ════════════════════════════════════════════════════════════════════
//  私有工具函数
// ════════════════════════════════════════════════════════════════════

/**
 * 验证命令行参数，归一化为内部使用的 buildConfig 对象。
 *
 * @param {object} options 原始 Commander 选项
 * @returns {object} 标准化后的构建配置
 */
async function validateAndNormalize(options) {
    // ── 验证 --src 目录 ───────────────────────────────────────────
    const srcPath = path.resolve(process.cwd(), options.src);

    if (!(await fs.pathExists(srcPath))) {
        throw new Error(`--src 目录不存在：${srcPath}`);
    }

    const srcStat = await fs.stat(srcPath);
    if (!srcStat.isDirectory()) {
        throw new Error(`--src 必须是一个目录，而不是文件：${srcPath}`);
    }

    // 检查 index.html 是否存在（警告，不强制报错，允许 SPA 使用其他入口）
    const indexHtmlPath = path.join(srcPath, 'index.html');
    if (!(await fs.pathExists(indexHtmlPath))) {
        console.warn(chalk.yellow(
            `⚠  警告：在 ${srcPath} 中未找到 index.html，` +
            `请确保你的入口文件名称正确。`
        ));
    }

    // ── 生成/验证包名 ─────────────────────────────────────────────
    const appName    = options.name.trim();
    const packageName = options.package
        ? validatePackageName(options.package)
        : generatePackageName(appName);

    // ── 验证 android-shell 目录 ───────────────────────────────────
    if (!(await fs.pathExists(ANDROID_SHELL_DIR))) {
        throw new Error(
            `Android 壳工程目录不存在：${ANDROID_SHELL_DIR}\n` +
            `  请确保你在 Web2BlueBuilder 项目根目录下运行此命令，` +
            `或者 packages/android-shell 目录完整。`
        );
    }

    // ── 确保输出目录存在 ──────────────────────────────────────────
    const outputDir = path.resolve(process.cwd(), options.output);
    await fs.ensureDir(outputDir);

    const buildType = options.release ? 'release' : 'debug';

    // 打印构建概要
    console.log(chalk.bold('  构建配置：'));
    console.log(chalk.gray(`    源目录：  ${srcPath}`));
    console.log(chalk.gray(`    应用名：  ${appName}`));
    console.log(chalk.gray(`    包  名：  ${packageName}`));
    console.log(chalk.gray(`    构建类型：${buildType}`));
    console.log(chalk.gray(`    输出目录：${outputDir}`));
    console.log('');

    return { srcPath, appName, packageName, outputDir, buildType };
}

/**
 * 将 APK 从 Gradle 构建输出目录复制到用户指定的输出目录。
 * 同时以应用名重命名，使输出文件名更友好。
 *
 * @param {string} shellDir    android-shell 目录
 * @param {string} buildType   'debug' | 'release'
 * @param {string} appName     应用名（用于重命名 APK）
 * @param {string} outputDir   目标输出目录
 * @returns {string} 最终 APK 的绝对路径
 */
async function copyApkToOutput(shellDir, buildType, appName, outputDir) {
    // Gradle 构建产物的标准路径（AGP 8.x）
    const gradleApkPath = path.join(
        shellDir,
        `app/build/outputs/apk/${buildType}/app-${buildType}.apk`
    );

    if (!(await fs.pathExists(gradleApkPath))) {
        throw new Error(
            `找不到构建产物 APK：${gradleApkPath}\n` +
            `  Gradle 构建可能已失败，请查看上方的构建日志。`
        );
    }

    // 将应用名转为安全的文件名（移除特殊字符，空格换成下划线）
    const safeName   = appName.replace(/[^\w\u4e00-\u9fa5]/g, '_');
    const timestamp  = new Date().toISOString().slice(0, 10);  // YYYY-MM-DD
    const apkFileName = `${safeName}_${buildType}_${timestamp}.apk`;
    const destPath    = path.join(outputDir, apkFileName);

    await fs.copy(gradleApkPath, destPath, { overwrite: true });

    return destPath;
}

/**
 * 根据应用名自动生成合法的 Android 包名。
 *
 * 规则：com.web2blue.<清理后的应用名>
 * 例如："LED 控制器" → "com.web2blue.led"
 *       "MyApp"      → "com.web2blue.myapp"
 *
 * @param {string} appName 应用名称
 * @returns {string} 合法的 Android 包名
 */
function generatePackageName(appName) {
    // 转小写，只保留字母数字，其余替换为空
    const suffix = appName
        .toLowerCase()
        .replace(/[\u4e00-\u9fa5]/g, '')    // 移除中文字符
        .replace(/[^a-z0-9]/g, '')           // 移除非字母数字
        .replace(/^[0-9]+/, '')              // 不能以数字开头
        || 'app';                            // 兜底

    return `com.web2blue.${suffix}`;
}

/**
 * 验证用户手动指定的包名格式是否合法。
 *
 * 合法格式：至少两段，每段由字母开头，只包含字母数字下划线。
 * 例如：com.example.myapp ✅    example ❌    123.app ❌
 *
 * @param {string} packageName 待验证的包名
 * @returns {string} 验证通过后返回原包名
 * @throws {Error} 格式非法时抛出
 */
function validatePackageName(packageName) {
    const pattern = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,}$/i;
    if (!pattern.test(packageName)) {
        throw new Error(
            `包名格式非法：${packageName}\n` +
            `  格式要求：至少两段，每段以字母开头，只含字母/数字/下划线\n` +
            `  合法示例：com.example.myapp`
        );
    }
    return packageName;
}

/**
 * 打印带编号的构建步骤标题。
 *
 * @param {number} current 当前步骤序号
 * @param {number} total   总步骤数
 * @param {string} desc    步骤描述
 */
function printStep(current, total, desc) {
    console.log(
        chalk.cyan(`\n  [${current}/${total}]`) +
        chalk.bold(` ${desc}...`)
    );
}

/**
 * 打印步骤完成标记。
 *
 * @param {string} message 完成信息
 */
function printDone(message) {
    console.log(chalk.green(`       ✔ ${message}`));
}

// ════════════════════════════════════════════════════════════════════
//  示例 HTML 内容（web2blue init 生成的模板）
// ════════════════════════════════════════════════════════════════════

const EXAMPLE_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>Web2Blue 示例</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }
    h1 { color: #00d4ff; margin-bottom: 20px; }
    .card { background: #16213e; border-radius: 12px; padding: 16px; margin-bottom: 16px; }
    input { width: 100%; padding: 10px; border-radius: 8px; border: 1px solid #444;
            background: #0f3460; color: #fff; font-size: 14px; margin-bottom: 8px; }
    button { width: 100%; padding: 12px; border-radius: 8px; border: none;
             background: #00d4ff; color: #000; font-weight: bold; font-size: 15px;
             cursor: pointer; margin-bottom: 8px; }
    button.danger { background: #ff6b6b; color: #fff; }
    button.secondary { background: #444; color: #fff; }
    #log { background: #000; border-radius: 8px; padding: 12px; height: 200px;
           overflow-y: auto; font-family: monospace; font-size: 12px; }
    .log-sys { color: #aaa; }
    .log-tx  { color: #66ff66; }
    .log-rx  { color: #ffdd44; }
    #status  { padding: 6px 12px; border-radius: 20px; display: inline-block;
               background: #444; font-size: 13px; margin-bottom: 12px; }
    #status.connected { background: #1a7a1a; color: #66ff66; }
  </style>
</head>
<body>
  <h1>🔵 Web2Blue 示例</h1>

  <div class="card">
    <p id="status">● 未连接</p>
    <input id="mac" type="text" placeholder="蓝牙 MAC 地址，如：AA:BB:CC:DD:EE:FF" />
    <div style="display:flex;gap:8px">
      <button onclick="connectSPP()">连接 SPP（HC-05）</button>
      <button onclick="connectBLE()">连接 BLE</button>
    </div>
    <button class="danger" onclick="BlueApp.disconnect()">断开连接</button>
  </div>

  <div class="card">
    <input id="sendData" type="text" placeholder="要发送的数据，如：AT+LED=1" />
    <button onclick="sendData()">发 送</button>
    <button class="secondary" onclick="BlueApp.showDebugConsole()">🔧 调试控制台</button>
  </div>

  <div class="card">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
      <span style="font-size:13px;color:#aaa">通信日志</span>
      <button class="secondary" style="width:auto;padding:4px 12px;font-size:12px"
              onclick="clearLog()">清除</button>
    </div>
    <div id="log"></div>
  </div>

  <script>
    // ── 初始化 BlueApp 事件监听 ──────────────────────────────────
    // BlueApp 是 Android 注入的全局对象，在 WebView 加载完成后立即可用

    // 监听所有蓝牙事件（统一入口）
    window.BlueApp = window.BlueApp || {};
    window.BlueApp._onEvent = function(event, data) {
      switch (event) {
        case 'connected':
          setStatus(true, data.type + ' 已连接：' + data.mac);
          addLog('sys', '已连接 ' + data.mac + ' [' + data.type + ']');
          break;
        case 'disconnected':
          setStatus(false, '已断开');
          addLog('sys', '断开原因：' + data.reason);
          break;
        case 'data':
          // data.str = 字符串格式，data.hex = HEX 格式
          addLog('rx', '[RX] STR: ' + data.str + '  |  HEX: ' + data.hex);
          break;
        case 'error':
          addLog('sys', '错误 [' + data.code + ']：' + data.message);
          break;
        case 'scan_result':
          addLog('sys', '发现设备：' + data.name + ' (' + data.mac + ') RSSI=' + data.rssi);
          break;
      }
    };

    function connectSPP() {
      var mac = document.getElementById('mac').value.trim();
      if (!mac) { alert('请输入 MAC 地址'); return; }
      addLog('sys', '正在连接 SPP → ' + mac);
      BlueApp.connect(mac, 'SPP');
    }

    function connectBLE() {
      var mac = document.getElementById('mac').value.trim();
      if (!mac) { alert('请输入 MAC 地址'); return; }
      addLog('sys', '正在连接 BLE → ' + mac);
      BlueApp.connect(mac, 'BLE');
    }

    function sendData() {
      var data = document.getElementById('sendData').value;
      if (!data) return;
      addLog('tx', '[TX] ' + data);
      BlueApp.send(data);
    }

    function setStatus(connected, text) {
      var el = document.getElementById('status');
      el.textContent = '● ' + text;
      el.className = connected ? 'connected' : '';
    }

    function addLog(type, text) {
      var log = document.getElementById('log');
      var line = document.createElement('div');
      line.className = 'log-' + type;
      var now = new Date();
      var ts = now.toTimeString().slice(0, 8) + '.' + String(now.getMilliseconds()).padStart(3,'0');
      line.textContent = '[' + ts + '] ' + text;
      log.appendChild(line);
      log.scrollTop = log.scrollHeight;
    }

    function clearLog() {
      document.getElementById('log').innerHTML = '';
    }
  </script>
</body>
</html>
`;

module.exports = { build, initExample };
