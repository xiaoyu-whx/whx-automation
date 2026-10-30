#!/bin/sh
# 把 docs/ci/ci.yml 安装成真正的 GitHub Actions 工作流。
#
# 为什么放在 docs 里：默认的 PAT 只有 repo 权限，GitHub 不允许这种 token
# 创建/更新 .github/workflows/ 下的文件（remote rejected ... without `workflow` scope）。
# 两种启用方式，任选其一：
#   1) 给 token 加 workflow 权限后执行本脚本并提交推送；
#   2) 直接在 GitHub 网页上新建 .github/workflows/ci.yml，把 docs/ci/ci.yml 内容粘进去。
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$root/.github/workflows"
cp "$root/docs/ci/ci.yml" "$root/.github/workflows/ci.yml"
echo "已安装工作流: .github/workflows/ci.yml"
echo "现在可以 git add .github && git commit && git push"
