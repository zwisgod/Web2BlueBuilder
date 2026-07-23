// 文件路径：packages/cli/src/gradle-runner.js
// Gradle 构建执行器 — 调用 gradlew 并实时流式打印构建进度

'use strict';

const path         = require('path');
const os           = require('os');
const fs           = require('fs-extra');
const { spawn }    = require('child_process');
const chalk        = require('chalk');

// ─── Gradle 进度关键词着色规则 ────────────────────────────────────
// 按优先级顺序匹配，匹配到第一个就停止。
const LOG_RULES = [
    // 构建失败（最高优先级，红色加粗）
    { test: /BUILD FAILED/i,              style: chalk.red.bold,    prefix: '✖ ' },
    // 编译错误 / 错误行
    { test: /\berror\b/i,                 style: chalk.red,         prefix: '  ✖ ' },
    { test: /^.*\.java:\d+: error/i,      style: chalk.red,         prefix: '  ✖ ' },
    // 警告
    { test: /\bwarning\b/i,               style: chalk.yellow,      prefix: '  ⚠ ' },
    // 构建成功
    { test: /BUILD SUCCESSFUL/i,          style: chalk.green.bold,  prefix: '✔ ' },
    // 任务执行行（Task :app:xxx）
    { test: /^> Task :app:/,              style: chalk.cyan,        prefix: '  » ' },
    // Gradle 初始化 / 配置阶段
    { test: /^> Configure project/,       style: chalk.gray,        prefix: '  · ' },
    { test: /^Starting a Gradle Daemon/,  style: chalk.gray,        prefix: '  · ' },
    // 下载依赖（提示开发者需要网络）
    { test: /^Download /i,                style: chalk.magenta,     prefix: '  ↓ ' },
    // 默认：普通灰色
    { test: /./,                          style: chalk.gray,        prefix: '    ' },
];

/**
 * 在 android-shell 目录中执行 Gradle 构建，并实时流式输出日志。
 *
 * <p>跨平台处理：
 * - Windows：使用 gradlew.bat（cmd /c gradlew.bat）
 * - Linux/macOS：使用 ./gradlew（需要 chmod +x 权限）
 *
 * <p>实时日志策略：
 * 使用 child_process.spawn()（而非 exec/execSync），
 * 监听 stdout/stderr 的 'data' 事件，每收到一块数据就立即打印，
 * 开发者无需等待整个构建完成才能看到进度。
 *
 * <p>Gradle 守护进程说明：
 * 第一次构建时 Gradle 会启动守护进程（约 10~30s），
 * 后续构建复用已有进程，速度快很多（约 5~10s）。
 * 这是正常现象，不是卡死。
 *
 * @param {string} shellDir    android-shell 目录的绝对路径
 * @param {string} buildType   'debug' | 'release'
 * @param {object} [envInfo]   预解析的 Android/JDK 环境信息
 * @returns {Promise<void>}    构建成功时 resolve，失败时 reject（含错误信息）
 */
async function run(shellDir, buildType, envInfo = {}) {
    // ── 前置检查：验证 gradlew 文件是否存在 ─────────────────────
    await ensureGradleWrapper(shellDir);

    // ── 确定 Gradle 任务名 ────────────────────────────────────────
    // assembleDebug 生成 debug APK（默认调试签名，无需配置）
    // assembleRelease 生成 release APK（需要配置签名，否则会报错）
    const gradleTask = buildType === 'release'
        ? 'assembleRelease'
        : 'assembleDebug';

    // ── 确定跨平台命令和参数 ──────────────────────────────────────
    const isWindows   = process.platform === 'win32';
    const gradlewFile = isWindows ? 'gradlew.bat' : './gradlew';

    // Gradle 命令行参数说明：
    //   --console=plain   禁用 Gradle 的 ANSI 进度条（避免终端乱码）
    //   --no-daemon       不使用守护进程（CI 环境推荐，本地可去掉提速）
    //   --stacktrace      构建失败时打印完整堆栈（方便调试）
    //   -q / --quiet      静默模式，只打印错误（可选，这里不用，要实时日志）
    const gradleArgs = [
        gradleTask,
        '--console=plain',
        '--stacktrace',
    ];

    let command, args;
    if (isWindows) {
        // Windows：通过 cmd.exe 执行 .bat 文件
        command = process.env.ComSpec || path.join(
            process.env.SystemRoot || 'C:\\Windows',
            'System32',
            'cmd.exe'
        );
        args    = ['/c', gradlewFile, ...gradleArgs];
    } else {
        // Linux/macOS：直接执行 gradlew shell 脚本
        command = gradlewFile;
        args    = gradleArgs;
        // 确保 gradlew 有执行权限
        await ensureExecutable(path.join(shellDir, 'gradlew'));
    }

    console.log(chalk.gray(
        `       执行：${command} ${args.join(' ')}`
    ));
    console.log(chalk.gray(
        `       工作目录：${shellDir}`
    ));
    console.log(chalk.gray(
        `       (Gradle 首次启动需 10~30s 下载依赖，请耐心等待...)\n`
    ));

    // ── 启动子进程 ────────────────────────────────────────────────
    return new Promise((resolve, reject) => {
        const child = spawn(command, args, {
            cwd:   shellDir,            // 在 android-shell 目录执行
            stdio: ['ignore', 'pipe', 'pipe'],  // stdin 不需要，stdout/stderr 管道接收
            shell: false,               // 不通过 shell 执行，避免路径转义问题
            // 继承父进程的环境变量（包含 JAVA_HOME、ANDROID_HOME 等）
            env: buildEnv(envInfo),
        });

        // ── 日志输出缓冲区（处理不完整行）────────────────────────
        // Gradle 的输出不一定按行到达，可能一次 data 事件包含多行，
        // 也可能一行被分成多个 data 事件。使用行缓冲处理。
        let stdoutBuffer = '';
        let stderrBuffer = '';

        // 累积完整的 stdout 内容，用于 close 事件中检测 BUILD SUCCESSFUL。
        // 某些环境（如 Windows cmd /c）Gradle 会在打印 BUILD SUCCESSFUL 之后
        // 因守护进程通信关闭等原因以非零码退出，此时构建实际已成功。
        let collectedStdout = '';

        child.stdout.on('data', (chunk) => {
            const text = chunk.toString('utf8');
            collectedStdout += text;
            stdoutBuffer   += text;
            stdoutBuffer = flushLines(stdoutBuffer, printGradleLine);
        });

        child.stderr.on('data', (chunk) => {
            stderrBuffer += chunk.toString('utf8');
            // stderr 同样用着色规则处理（很多 Gradle 日志走 stderr）
            stderrBuffer = flushLines(stderrBuffer, printGradleLine);
        });

        child.on('error', (err) => {
            // spawn 本身出错（命令不存在、权限不足等）
            reject(new Error(
                `无法启动 Gradle 进程：${err.message}\n` +
                `  请确认：\n` +
                `  1. 已安装 JDK 11+，且 JAVA_HOME 环境变量已设置\n` +
                `  2. ${isWindows ? 'gradlew.bat' : 'gradlew'} 文件存在于 ${shellDir}`
            ));
        });

        child.on('close', (exitCode, signal) => {
            // 打印缓冲区中残余的最后一行（无换行符结尾的情况）
            if (stdoutBuffer.trim()) printGradleLine(stdoutBuffer.trim());
            if (stderrBuffer.trim()) printGradleLine(stderrBuffer.trim());

            // 在 stdout 中检测 "BUILD SUCCESSFUL" 字样。
            // 某些 Windows 环境下 Gradle 守护进程在构建完成后因 IPC 通道关闭
            // 等原因返回非零退出码，但构建产物（APK）已经生成，实际为成功。
            const buildSuccessful = collectedStdout.includes('BUILD SUCCESSFUL');

            if (exitCode === 0) {
                // Gradle 正常退出（exit code 0 = 成功）
                resolve();
            } else if (buildSuccessful) {
                // 非零退出但 stdout 已包含 BUILD SUCCESSFUL：视为成功
                console.warn(chalk.yellow(
                    `  ⚠  Gradle 退出码为 ${exitCode}，但已检测到 BUILD SUCCESSFUL，` +
                    `视为构建成功，继续执行后续步骤。`
                ));
                resolve();
            } else if (signal) {
                // 进程被信号杀死（如 SIGKILL / SIGTERM）
                reject(new Error(
                    `Gradle 进程被强制终止（信号：${signal}）\n` +
                    `  这通常发生在内存不足时，请检查系统内存是否充足。`
                ));
            } else {
                // Gradle 非零退出且无 BUILD SUCCESSFUL：真正的构建失败
                reject(new Error(
                    `Gradle 构建失败（exit code: ${exitCode}）\n` +
                    `  请仔细查看上方的红色错误日志定位问题。\n` +
                    `  常见原因：\n` +
                    `  · 缺少 JAVA_HOME 或 ANDROID_HOME 环境变量\n` +
                    `  · Android SDK 未安装或版本不匹配\n` +
                    `  · Java 源码编译错误（检查 Java 文件语法）\n` +
                    `  · Gradle 依赖下载失败（检查网络连接）`
                ));
            }
        });
    });
}

// ════════════════════════════════════════════════════════════════════
//  私有工具函数
// ════════════════════════════════════════════════════════════════════

/**
 * 行缓冲处理：从缓冲区中提取完整行并逐行调用 callback，
 * 返回剩余的不完整行（等待下次数据到来后继续拼接）。
 *
 * @param {string}   buffer   当前缓冲区内容
 * @param {Function} callback 处理完整行的函数
 * @returns {string} 未处理的剩余内容
 */
function flushLines(buffer, callback) {
    const lines = buffer.split('\n');
    // 最后一个元素是不完整的行（或空字符串），留在缓冲区
    const incomplete = lines.pop();
    lines.forEach(line => {
        const trimmed = line.replace(/\r$/, '');  // 移除 Windows 行尾 \r
        if (trimmed.length > 0) callback(trimmed);
    });
    return incomplete || '';
}

/**
 * 根据日志内容应用着色规则并打印到终端。
 *
 * @param {string} line Gradle 输出的单行文本
 */
function printGradleLine(line) {
    for (const rule of LOG_RULES) {
        if (rule.test.test(line)) {
            // 截断超长行，防止终端换行导致进度条样式混乱
            const maxLen = process.stdout.columns ? process.stdout.columns - 8 : 120;
            const display = line.length > maxLen
                ? line.slice(0, maxLen - 3) + '...'
                : line;
            console.log(rule.style(rule.prefix + display));
            return;
        }
    }
}

/**
 * 验证 gradlew / gradlew.bat 是否存在，提供明确的安装指引。
 *
 * @param {string} shellDir android-shell 目录
 */
async function ensureGradleWrapper(shellDir) {
    const isWindows   = process.platform === 'win32';
    const gradlewName = isWindows ? 'gradlew.bat' : 'gradlew';
    const gradlewPath = path.join(shellDir, gradlewName);

    if (!(await fs.pathExists(gradlewPath))) {
        throw new Error(
            `找不到 Gradle Wrapper：${gradlewPath}\n\n` +
            `  请在 ${shellDir} 目录中生成 Gradle Wrapper：\n` +
            `    cd "${shellDir}"\n` +
            `    gradle wrapper --gradle-version 8.4\n\n` +
            `  前提：已安装 Gradle（https://gradle.org/install/）\n` +
            `  或：用 Android Studio 打开工程后，会自动生成 gradlew。`
        );
    }

    // 同时检查 gradle/wrapper/gradle-wrapper.jar 是否存在
    const wrapperJar = path.join(shellDir, 'gradle/wrapper/gradle-wrapper.jar');
    if (!(await fs.pathExists(wrapperJar))) {
        throw new Error(
            `找不到 gradle-wrapper.jar：${wrapperJar}\n` +
            `  Gradle Wrapper 文件不完整，请重新运行 "gradle wrapper" 生成。`
        );
    }
}

/**
 * 在 Linux/macOS 上确保 gradlew 脚本有执行权限（chmod +x）。
 * Windows 不需要此操作。
 *
 * @param {string} gradlewPath gradlew 文件的绝对路径
 */
async function ensureExecutable(gradlewPath) {
    if (process.platform === 'win32') return;
    try {
        await fs.chmod(gradlewPath, '755');
    } catch (e) {
        // chmod 失败通常不是致命错误（例如文件系统不支持），
        // 只是给出警告，让 spawn 尝试执行，如果真的没权限会在 spawn 时报错
        console.warn(chalk.yellow(
            `  ⚠  无法设置 gradlew 执行权限：${e.message}\n` +
            `     如果构建失败，请手动运行：chmod +x "${gradlewPath}"`
        ));
    }
}

/**
 * 构建传递给子进程的环境变量。
 *
 * 继承父进程所有环境变量，额外注入/覆盖 Gradle 相关配置。
 *
 * @param {object} envInfo 环境信息
 * @returns {object} 环境变量对象
 */
function buildEnv(envInfo = {}) {
    const userHome = process.env.USERPROFILE || process.env.HOME || os.homedir();
    const env = {
        ...process.env,
        // 禁用 Gradle 的 Kotlin DSL 警告（减少噪音）
        GRADLE_OPTS: (process.env.GRADLE_OPTS || '') +
            ' -Dorg.gradle.warning.mode=none',
        // 增大 Gradle JVM 堆内存（默认 512MB 对大型项目可能不够）
        // 如果系统内存充足，可以调大，如 -Xmx4g
        JAVA_OPTS: process.env.JAVA_OPTS || '-Xmx2g -Xms512m',
    };

    if (envInfo.sdkDir) {
        env.ANDROID_SDK_ROOT = envInfo.sdkDir;
        env.ANDROID_HOME = envInfo.sdkDir;
    }

    env.GRADLE_USER_HOME = process.env.GRADLE_USER_HOME || path.join(userHome, '.gradle');

    // AGP 8.x 不允许同时传入多个 Android 用户目录变量，只保留推荐的 ANDROID_USER_HOME。
    const androidUserHome = process.env.ANDROID_USER_HOME
        || process.env.ANDROID_PREFS_ROOT
        || path.join(process.env.ANDROID_SDK_HOME || userHome, '.android');

    delete env.ANDROID_SDK_HOME;
    delete env.ANDROID_PREFS_ROOT;
    env.ANDROID_USER_HOME = androidUserHome;

    if (envInfo.javaHome) {
        env.JAVA_HOME = envInfo.javaHome;
        const javaBin = path.join(envInfo.javaHome, 'bin');
        env.Path = env.Path
            ? `${javaBin}${path.delimiter}${env.Path}`
            : javaBin;
    }

    fs.ensureDirSync(env.GRADLE_USER_HOME);
    fs.ensureDirSync(env.ANDROID_USER_HOME);

    return env;
}

module.exports = { run };
