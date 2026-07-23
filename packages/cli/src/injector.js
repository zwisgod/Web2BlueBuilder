// 文件路径：packages/cli/src/injector.js
// HTML 注入器 — 将用户的 HTML 项目整体复制到 Android 壳的 assets/web/ 目录

'use strict';

const path  = require('path');
const fs    = require('fs-extra');
const chalk = require('chalk');

// ─── 不需要注入到 APK 的文件/目录（常见开发配置文件，无需打包）─────
const IGNORE_PATTERNS = [
    'node_modules',
    '.git',
    '.gitignore',
    '.DS_Store',
    'Thumbs.db',
    'web2blue.config.json',  // CLI 配置文件，不是 HTML 内容
    '*.log',
    '.env',
    '.env.*',
];

/**
 * 将用户 HTML 项目目录的所有文件，完整覆盖到 Android 壳的 assets/web/ 目录。
 *
 * <p>核心逻辑：
 * 1. 清空 assets/web/ 目录（删除旧文件，防止上次构建的残留文件污染）
 * 2. 使用 fs-extra.copy() 将 srcDir 整个复制到 destDir
 * 3. 统计并返回注入的文件数量
 *
 * <p>为什么要先清空？
 * 假设上次打包的是一个含 20 个文件的项目，这次打包的项目只有 10 个文件，
 * 如果不清空，旧的 10 个文件会残留在 APK 里，造成体积膨胀和混乱。
 *
 * @param {string} srcDir  用户 HTML 项目的源目录（--src 参数）
 * @param {string} destDir Android 壳 assets/web/ 的绝对路径
 * @returns {Promise<number>} 成功注入的文件数量
 */
async function inject(srcDir, destDir) {
    // ── 前置校验 ──────────────────────────────────────────────────
    if (!(await fs.pathExists(srcDir))) {
        throw new Error(`源目录不存在：${srcDir}`);
    }

    // ── Step 1：清空目标目录（保留目录本身，只删内容）────────────
    console.log(chalk.gray(`       清空旧内容：${destDir}`));
    await fs.emptyDir(destDir);

    // ── Step 2：执行复制，过滤不需要的文件 ────────────────────────
    let fileCount = 0;

    await fs.copy(srcDir, destDir, {
        overwrite: true,       // 同名文件强制覆盖
        errorOnExist: false,   // 存在时不报错（overwrite=true 时此项无意义，但明确声明）
        preserveTimestamps: false, // 不保留原始时间戳，使用构建时间

        /**
         * 过滤器：返回 true 表示"复制此项"，返回 false 表示"跳过此项"。
         *
         * @param {string} src  源文件/目录的绝对路径
         * @returns {boolean}
         */
        filter: (src) => {
            const basename = path.basename(src);

            // 检查是否匹配任意忽略模式
            for (const pattern of IGNORE_PATTERNS) {
                if (pattern.includes('*')) {
                    // 通配符匹配：*.log 匹配所有 .log 结尾的文件
                    const ext = pattern.replace('*', '');
                    if (basename.endsWith(ext)) {
                        console.log(chalk.gray(`       跳过：${path.relative(srcDir, src)}`));
                        return false;
                    }
                } else {
                    // 精确名称匹配
                    if (basename === pattern) {
                        console.log(chalk.gray(`       跳过：${path.relative(srcDir, src)}`));
                        return false;
                    }
                }
            }

            // 统计文件数量（不统计目录）
            try {
                // 注意：filter 在 copy 前调用，此时 src 是源路径，可以 stat
                const stat = fs.statSync(src);
                if (stat.isFile()) {
                    fileCount++;
                    // 打印被复制的文件（相对路径，避免超长输出）
                    const rel = path.relative(srcDir, src);
                    console.log(chalk.gray(`       复制：${rel}`));
                }
            } catch (e) {
                // stat 失败时不阻止复制，只是无法计数
            }

            return true;  // 默认：复制此文件
        },
    });

    // ── Step 3：校验 index.html 是否已成功注入 ────────────────────
    const indexInDest = path.join(destDir, 'index.html');
    if (!(await fs.pathExists(indexInDest))) {
        console.warn(chalk.yellow(
            `\n  ⚠  警告：assets/web/index.html 不存在！\n` +
            `     WebView 将无法找到入口页面。请确认你的 HTML 入口文件名为 index.html。`
        ));
    }

    return fileCount;
}

module.exports = { inject };
