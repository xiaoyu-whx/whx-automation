#!/bin/sh
# 推送脚本：本机代理不稳定，失败自动重试。
# 用法：sh tools/push.sh [远端分支，默认 main]
set -u
branch="${1:-main}"
i=1
while [ "$i" -le 6 ]; do
    printf '推送 %s（第 %d 次）... ' "$branch" "$i"
    if GIT_TERMINAL_PROMPT=0 git push origin "$branch" 2>/tmp/whx_push.log; then
        echo "成功"
        exit 0
    fi
    echo "失败：$(tail -1 /tmp/whx_push.log | cut -c1-90)"
    i=$((i + 1))
    sleep 4
done
echo "连续 6 次失败，检查代理是否还活着（curl -x http://127.0.0.1:7897 https://github.com）"
exit 1
