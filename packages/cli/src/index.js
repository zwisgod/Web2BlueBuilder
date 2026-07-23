#!/usr/bin/env node
// 文件路径：packages/cli/src/index.js
// CLI 入口 — 注册所有命令和全局选项

'use strict';

const { Command } = require('commander');
const chalk       = require('chalk');
const path        = require('path');
const builder     = require('./builder');

// ─── 读取 CLI 自身的 package.json，用于版本号显示 ─────────────────
const pkg = require('../package.json');

// ════════════════════════════════════════════════════════════════════
//  ASCII 品牌 Banner
// ════════════════════════════════════════════════════════════════════

function printBanner() {
    console.log(chalk.cyan(`
 ██╗    ██╗███████╗██████╗ ██████╗ ██████╗ ██╗     ██╗   ██╗███████╗
 ██║    ██║██╔════╝██╔══██╗╚════██╗██╔══██╗██║     ██║   ██║██╔════╝
 ██║ █╗ ██║█████╗  ██████╔╝ █████╔╝██████╔╝██║     ██║   ██║█████╗
 ██║███╗██║██╔══╝  ██╔══██╗██╔═══╝ ██╔══██╗██║     ██║   ██║██╔══╝
 ╚███╔███╔╝███████╗██████╔╝███████╗██████╔╝███████╗╚██████╔╝███████╗
  ╚══╝╚══╝ ╚══════╝╚═════╝ ╚══════╝╚═════╝ ╚══════╝ ╚═════╝ ╚══════╝
    `));
    console.log(chalk.gray(`  HTML → Android 蓝牙 APK 一键生成器  v${pkg.version}`));
    console.log(chalk.gray('  ─────────────────────────────────────────────────────\n'));
}

// ════════════════════════════════════════════════════════════════════
//  Commander 程序定义
// ════════════════════════════════════════════════════════════════════

const program = new Command();

program
    .name('web2blue')
    .description('将纯 HTML/JS/CSS 项目一键打包为具备蓝牙能力的 Android APK')
    .version(pkg.version, '-v, --version', '显示当前版本号');

// ─── build 子命令 ──────────────────────────────────────────────────
program
    .command('build')
    .description('将指定的 HTML 项目打包为 APK')
    .requiredOption(
        '--src <path>',
        'HTML 项目目录路径（必填），该目录下需包含 index.html'
    )
    .requiredOption(
        '--name <name>',
        '应用名称（必填），将显示在 Android 桌面图标下方，如："LED 控制器"'
    )
    .option(
        '--package <packageName>',
        '应用包名（可选），如 com.example.ledcontroller。\n' +
        '若不指定，将根据 --name 自动生成，如 com.web2blue.ledcontroller'
    )
    .option(
        '--output <path>',
        '输出目录（可选），默认为项目根目录的 output/ 文件夹',
        path.resolve(__dirname, '../../../output')
    )
    .option(
        '--release',
        '构建 Release 包（默认 Debug 包，Release 需配置签名）',
        false
    )
    .action(async (options) => {
        printBanner();
        try {
            await builder.build(options);
        } catch (err) {
            // 顶层错误捕获，防止未处理的 Promise rejection 导致进程异常退出
            console.error(chalk.red('\n✖  构建过程发生未预期的错误：'));
            console.error(chalk.red(err.message));
            if (process.env.DEBUG) {
                console.error(err.stack);  // DEBUG 模式下打印完整堆栈
            }
            process.exit(1);  // 非零退出码，方便 CI/CD 系统检测构建失败
        }
    });

// ─── init 子命令（初始化示例项目，帮助新用户快速上手）──────────────
program
    .command('init [projectName]')
    .description('在当前目录创建一个示例 HTML 蓝牙项目')
    .action(async (projectName) => {
        printBanner();
        const name = projectName || 'my-blue-app';
        const { initExample } = require('./builder');
        try {
            await initExample(name);
        } catch (err) {
            console.error(chalk.red('✖  初始化失败：' + err.message));
            process.exit(1);
        }
    });

// ─── 未知命令处理 ──────────────────────────────────────────────────
program.on('command:*', (operands) => {
    console.error(chalk.red(`✖  未知命令：${operands[0]}`));
    console.log(chalk.gray('  运行 web2blue --help 查看可用命令'));
    process.exit(1);
});

// ─── 无参数时显示帮助信息 ──────────────────────────────────────────
if (process.argv.length <= 2) {
    printBanner();
    program.help();  // 打印帮助后自动 process.exit(0)
}

// ─── 解析命令行参数 ────────────────────────────────────────────────
program.parseAsync(process.argv).catch((err) => {
    console.error(chalk.red('✖  参数解析错误：' + err.message));
    process.exit(1);
});
