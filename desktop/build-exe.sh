#!/bin/bash

# PhoneMirror Linux/macOS 打包脚本
# 用于在非 Windows 系统上准备 Windows 打包环境

set -e

echo "=========================================="
echo "   PhoneMirror Windows EXE 打包准备"
echo "=========================================="
echo ""

cd "$(dirname "$0")"

echo "[1/4] 检查环境..."
if ! command -v node &> /dev/null; then
    echo "[错误] 未检测到 Node.js，请先安装 Node.js 18+"
    exit 1
fi

echo "Node.js 版本: $(node -v)"
echo "npm 版本: $(npm -v)"
echo ""

echo "[2/4] 安装依赖..."
npm install
echo ""

echo "[3/4] 准备打包配置..."
echo ""
echo "注意: 在 Linux/macOS 上无法直接生成 Windows EXE"
echo ""
echo "请使用以下方法之一:"
echo ""
echo "方法 1: 在 Windows 电脑上运行 build-windows.bat"
echo "方法 2: 使用 Wine 运行 electron-builder"
echo "方法 3: 使用 GitHub Actions 自动打包"
echo ""

# 创建 GitHub Actions 工作流
mkdir -p .github/workflows

cat > .github/workflows/build-windows.yml << 'YAML'
name: Build Windows EXE

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build-windows:
    runs-on: windows-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Setup Node.js
      uses: actions/setup-node@v3
      with:
        node-version: '18'
        cache: 'npm'
    
    - name: Install dependencies
      run: npm ci
      working-directory: ./desktop
    
    - name: Build Windows EXE
      run: npm run build:win
      working-directory: ./desktop
      env:
        GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    
    - name: Upload artifacts
      uses: actions/upload-artifact@v3
      with:
        name: PhoneMirror-Windows
        path: |
          desktop/dist/*.exe
          desktop/dist/win-unpacked/
YAML

echo "[4/4] 已创建 GitHub Actions 工作流"
echo "   文件: .github/workflows/build-windows.yml"
echo ""

echo "=========================================="
echo "   打包选项:"
echo "=========================================="
echo ""
echo "选项 A: 在 Windows 上直接打包 (推荐)"
echo "   1. 复制整个 phone-mirror 文件夹到 Windows 电脑"
echo "   2. 进入 desktop 目录"
echo "   3. 双击运行 build-windows.bat"
echo ""
echo "选项 B: 使用 Wine (Linux/macOS)"
echo "   1. 安装 Wine: brew install wine (macOS)"
echo "                    sudo apt install wine (Linux)"
echo "   2. 运行: wine cmd /c build-windows.bat"
echo ""
echo "选项 C: GitHub Actions 自动打包"
echo "   1. 将代码推送到 GitHub"
echo "   2. 进入 Actions 页面"
echo "   3. 运行 'Build Windows EXE' 工作流"
echo "   4. 下载生成的 EXE 文件"
echo ""
echo "=========================================="
