# tools/ —— 本地联调与端到端自检脚本

这些脚本只用于开发期验证，**不参与**四端构建产物（`vite build` 不会打包它们）。

## 为什么需要它们

`npm run type-check` 只能保证类型正确，`npm run build` 只能保证能编译。
它们都发现不了「页面白屏」「窄屏菜单溢出」「中文变英文」「手写字体把 0 渲染成豆腐块」
这类只有真的把页面渲染出来才会暴露的问题。下面的脚本用 Edge/Chrome 的
DevTools Protocol 真的打开页面、点按钮、量布局。

## 前置条件

1. 后端已启动（`server-java`，默认 `127.0.0.1:8642`）。
2. 对应前端已 `npm run dev`（admin 5176 / org 5174 / user 5175 / protectee 5177；
   端口被占用时 Vite 会顺延，请以实际输出为准，并同步改 `e2e-*.json` 里的 `baseUrl`）。
3. Windows 上装了 Edge（脚本里写死了默认路径，换浏览器改 `EDGE` 常量）。

## 用法

### 1. 生成带登录态的配置

本机数据库里的账号密码都是 BCrypt 散列，未知，而改库会污染开发数据。
`mint-token.mjs` 用后端 `.env` 里同一个 `app.jwt.secret` 直接签一个令牌，
走的是后端 `JwtAuthenticationFilter` 完全相同的校验路径，等价于真实登录态。

```bash
# node tools/mint-token.mjs <subject> <principalType> [roles] [perms]
node tools/make-e2e-config.mjs tools/e2e-admin.json tools/e2e-admin.run.json \
  <adminAccountId> ADMIN SUPER_ADMIN \
  ADMIN_MANAGE,ORG_MANAGE,ORG_REVIEW,USER_MANAGE,VOLUNTEER_REVIEW,...
```

> 令牌里只有 `sub` / `principal_type` / `roles` / `permissions`，
> 权限点必须与数据库里该账号真实拥有的权限一致，否则页面会按权限正确隐藏入口，
> 看起来像「页面没渲染」，实际是配置漏了权限。

### 2. 页面渲染 + 窄屏回归

```bash
node tools/e2e-check.mjs tools/e2e-admin.run.json
```

会逐页访问、断言关键选择器与文案、切到 390×844 检查抽屉与横向溢出、
收集运行时 JS 异常，并在 `.shots/` 下留截图与 `*-report.json`。

### 3. 窄屏抽屉交互

```bash
node tools/e2e-drawer.mjs tools/e2e-admin.run.json
```

断言汉堡按钮存在、`aria-expanded` 正确翻转、当前页高亮唯一、点击可跳转且抽屉关闭。

### 4. 弹窗交互

```bash
node tools/e2e-dialog.mjs tools/e2e-org-dialog.run.json
```

需要配置里额外带一个 `page` 字段（目标页面路径）。

### 5. 中文语言包回归

去掉 `app.use(ElementPlus)` 改用 `<el-config-provider>` 后，必须确认分页、
空表格这些内置文案没有退回英文。配置里加 `i18nPages` 数组后运行：

```bash
node tools/e2e-i18n.mjs tools/e2e-admin.i18n.json
```

### 6. 字体字形探测

```bash
node tools/font-probe.mjs http://localhost:5176/stats
```

手写体 `ZCOOL KuaiLe` 的 `0` 是一个细长空心方框，在 30px 以上非常像缺字形的
豆腐块（这正是当初引入 `--font-num` 的原因）。这个脚本用 canvas 量字宽，
判断某个字符到底是字体自带字形还是回退到了系统字体。

## 注意事项

- `*.run.json` / `*.i18n.json` 含真实令牌，**不要提交**（见 `.gitignore`）。
- `.shots/` 是截图与报告的落盘目录，同样不提交。
- 这些脚本假设 `fetch` / `WebSocket` 全局可用（Node 18+ 内置）。
