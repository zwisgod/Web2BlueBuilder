// 文件路径：packages/cli/src/configurator.js
// 工程配置器 — 将用户的应用名和包名写入 Android 工程模板文件

'use strict';

const path  = require('path');
const fs    = require('fs-extra');
const chalk = require('chalk');

/**
 * 配置 Android 壳工程，将用户指定的应用名和包名写入以下两个文件：
 *   1. app/build.gradle                   → applicationId
 *   2. app/src/main/res/values/strings.xml → app_name 字符串资源
 *
 * 注意：AndroidManifest.xml 的 package 属性已在 AGP 8.2 迁移中移除。
 *   - namespace 固定为 'com.web2blue.shell'（Java 源码包名，不可更改）
 *   - applicationId 是用户可自定义的 APK 唯一标识符（在 build.gradle 中替换）
 *   - 两者区别：namespace 决定 R 类包名和 Manifest 短类名解析；
 *              applicationId 决定 APK 在设备和市场的唯一 ID。
 *
 * @param {string} shellDir  android-shell 的绝对路径
 * @param {object} config    配置对象
 * @param {string} config.appName     用户应用名称
 * @param {string} config.packageName 用户包名（写入 applicationId）
 */
async function configure(shellDir, config) {
    const { appName, packageName } = config;

    console.log(chalk.gray(`       应用名：${appName}`));
    console.log(chalk.gray(`       包  名：${packageName}`));

    // 并行处理两个文件（互相独立，无依赖关系）
    await Promise.all([
        configureBuildGradle(shellDir, packageName),
        configureStrings(shellDir, appName),
    ]);
}

// ════════════════════════════════════════════════════════════════════
//  私有：各文件配置函数
// ════════════════════════════════════════════════════════════════════

/**
 * 配置 app/build.gradle：替换 applicationId 值。
 *
 * AGP 8.2+ 架构说明：
 *   - namespace：Java 源码包名，固定为 com.web2blue.shell，不替换。
 *   - applicationId：APK 唯一标识，由用户通过 --package 参数指定，此处替换。
 *
 * Target line format: applicationId "com.example.app"
 * Gradle supports both single and double quotes.
 *
 * @param {string} shellDir    android-shell 根目录
 * @param {string} packageName 新包名
 */
async function configureBuildGradle(shellDir, packageName) {
    const filePath = path.join(shellDir, 'app/build.gradle');

    const content = await readFileSafe(filePath, 'app/build.gradle');

    // Regex: match applicationId "..." or applicationId '...'
    // Allows arbitrary leading whitespace (indentation)
    const updated = content.replace(
        /(^\s*applicationId\s+)["'][^"']*["']/m,
        `$1"${packageName}"`
    );

    if (updated === content) {
        console.warn(chalk.yellow(
            `  ⚠  app/build.gradle 中未找到 applicationId，请检查模板文件格式`
        ));
    }

    await fs.writeFile(filePath, updated, 'utf8');
    console.log(chalk.gray(`       ✔ build.gradle → applicationId "${packageName}"`));
}

/**
 * 配置 res/values/strings.xml：替换 app_name 字符串资源值。
 *
 * Target line format: <string name="app_name">AppName</string>
 *
 * @param {string} shellDir android-shell 根目录
 * @param {string} appName  新应用名称
 */
async function configureStrings(shellDir, appName) {
    const filePath = path.join(
        shellDir,
        'app/src/main/res/values/strings.xml'
    );

    const content = await readFileSafe(filePath, 'res/values/strings.xml');

    // Regex: match <string name="app_name">any content</string>
    // Non-greedy [^<]* prevents spanning across multiple string tags
    const updated = content.replace(
        /(<string\s+name="app_name">)[^<]*(<\/string>)/,
        `$1${escapeXml(appName)}$2`
    );

    if (updated === content) {
        console.warn(chalk.yellow(
            `  ⚠  strings.xml 中未找到 app_name 字符串资源，请检查模板文件格式`
        ));
    }

    await fs.writeFile(filePath, updated, 'utf8');
    console.log(chalk.gray(`       ✔ strings.xml → app_name = "${appName}"`));
}

// ════════════════════════════════════════════════════════════════════
//  私有工具函数
// ════════════════════════════════════════════════════════════════════

/**
 * Safely read file content; throw a clear error if the file does not exist.
 *
 * @param {string} filePath    Absolute file path
 * @param {string} displayName Human-readable name for error messages
 * @returns {Promise<string>} File content (UTF-8)
 */
async function readFileSafe(filePath, displayName) {
    if (!(await fs.pathExists(filePath))) {
        throw new Error(
            `模板文件不存在：${filePath}\n` +
            `  请确保 android-shell 工程目录结构完整。\n` +
            `  缺失文件：${displayName}`
        );
    }
    return fs.readFile(filePath, 'utf8');
}

/**
 * Escape XML special characters to prevent app name from breaking XML structure.
 *
 * Example: "A&B<C>" → "A&amp;B&lt;C&gt;"
 *
 * @param {string} str Raw string
 * @returns {string} XML-safe escaped string
 */
function escapeXml(str) {
    return str
        .replace(/&/g,  '&amp;')
        .replace(/</g,  '&lt;')
        .replace(/>/g,  '&gt;')
        .replace(/"/g,  '&quot;')
        .replace(/'/g,  '&apos;');
}

module.exports = { configure };
