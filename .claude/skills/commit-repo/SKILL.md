---
name: commit-repo
description: 将当前工作区改动提交到本地仓库并推送远程。用于用户要求"提交代码 / 更新仓库 / push"时。规则：中文一句话简述更新、不添加 AI 协作署名、提交前脱敏检查。
---

# 提交仓库规范

用户要求提交/推送代码时，严格按以下三条执行。

## 1. 中文简述更新

- 提交信息用**简体中文**，沿用仓库既有风格：`type(scope): 一句话摘要`。
  - type 取 `feat` / `fix` / `docs` / `refactor` / `chore`；scope 取 `server` / `web` / `android` / `db` / `docs` 等，跨端改动可省略 scope。
- 大轮次改动在摘要行下空一行写正文要点（每行不超过 72 字，用 `-` 列表），概括功能面、迁移版本、测试结论（如「全量 305 例通过」）。
- 摘要只写"做了什么"，不写过程叙事。

## 2. 不添加 AI 协作署名

- **禁止**在提交信息中出现任何 AI 相关署名或尾注，包括但不限于：
  - `Co-authored-by: ...`
  - `Generated with ...` / `🤖` emoji
  - 「由 AI 生成」「AI 辅助」等说明
- 提交信息只描述代码改动本身，作者就是 git 配置的用户本人。

## 3. 脱敏检查（提交前必做）

按顺序核对，任何一项不过就先处理再提交：

1. **真实口令扫描**：对将提交的文件全仓扫描已知真实口令/密钥字符串
   （本仓库当前为 dev 库口令，见 `server-java/server-java/.env`，该文件本身已被忽略）。
   命中即停止，改为占位符或移出仓库。
2. **敏感文件不入库**（核对 `.gitignore` 覆盖）：
   - `**/.env`（只提交 `.env.example`，其中必须全是 `change_me_*` 占位符）
   - `client-web/tools/e2e-admin.json`（真实 superadmin 密码）
   - `0PASS.txt` 等名字带 PASS/密码/secret/credential/token 的本地便签
   - `*.pem` / `*.key` / `*.jks` / `*.keystore` / `local.properties`
3. **配置文件引用化**：`application.properties`、`docker-compose.yml` 等只能出现
   `${ENV_VAR}` 占位引用，不得内联真实值。
4. 文档（README、开发日记）不得含真实口令、内网地址凭据、个人信息。

## 4. 操作流程

```powershell
git status --porcelain          # 1. 浏览全部改动，识别敏感文件
git remote -v                   # 2. 确认远程（origin = github.com/catCookie-ice/Sullivan.git）
# …执行上方脱敏检查…
git add -A                      # 3. 暂存（确认 ignore 已挡住敏感文件）
git commit -m "…"               # 4. 中文提交信息，无 AI 署名
git push origin                 # 5. 推送当前分支
```

- 推送前用 `git log --oneline -5` 确认提交信息格式与历史一致。
- 若推送被拒绝（远程有新提交），先 `git pull --rebase` 解决后再推。
