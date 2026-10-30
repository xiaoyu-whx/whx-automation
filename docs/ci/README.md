# CI 说明

`ci.yml` 放在这里而不是 `.github/workflows/`，是因为**推送受限**：

```
! [remote rejected] main -> main
  (refusing to allow a Personal Access Token to create or update workflow
   `.github/workflows/ci.yml` without `workflow` scope)
```

GitHub 不允许只有 `repo` 权限的 token 改动工作流文件（防止恶意代码偷偷加 CI 执行命令）。

## 启用方式（任选其一）

**方式 1：给 token 加 `workflow` 权限**

到 https://github.com/settings/tokens 勾上 `workflow`，然后：

```bash
sh tools/enable_ci.sh
git add .github && git commit -m "ci: 启用工作流" && git push
```

**方式 2：网页里直接建**

GitHub → 仓库 → Add file → Create new file →
文件名填 `.github/workflows/ci.yml`，把本目录 `ci.yml` 的内容粘进去，提交。

## 它做什么

| Job | 内容 |
| --- | --- |
| `android` | JDK 21 + Android SDK，跑 `:core:test :helper:test :app:testDebugUnitTest` 与 `assembleDebug`，汇总用例数并上传报告（release 需要仓库外签名密钥，CI 不构建 release） |
| `tools` | `whx.py` / `vision_cli.py` 语法检查、`push.sh` 语法检查、AXML 字符串池编码器自检 |

## 本地等价验证

CI 里每一步都能在本地跑，命令等价：

```bash
./gradlew :core:test :helper:test :app:testDebugUnitTest assembleDebug
python -m py_compile tools/whx.py tools/vision_cli.py
sh -n tools/push.sh
```

实测（2026-10-31）：59 个用例 0 失败，debug 包构建成功。
