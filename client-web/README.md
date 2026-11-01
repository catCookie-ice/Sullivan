# Sullivan 前端项目

`client-web` 下是**四个**独立、自包含的 Vue 3 前端项目（各含独立 `package.json`，各自安装依赖），
外加一个 `shared/` 源码目录。

| 目录 | 面向角色 | dev 端口 | 说明 |
|---|---|---|---|
| `org-web/` | 公益机构 | 5174 | 机构资料、服务、活动与报名管理 |
| `user-web/` | 监护人 + 志愿者 | 5175 | 绑定管理、代建受护人、兼任志愿者、活动报名、实时会话 |
| `admin-web/` | 管理员 | 5176 | 用户/机构管理、注册码、运营统计、举报治理与处罚 |
| `protectee-web/` | 受护人（**临时测试端**） | 5177 | 绑定管理、活动浏览（只读）、实时会话；正式的受护人端是 Android |
| `shared/` | — | — | `keep-auto` / `nav` / `notifications`，通过 `@shared` alias 引入 |

`shared/` **不是** npm workspace 包：四个项目各自 `npm install`，`shared/` 里的 bare import
（`vue`、`element-plus` 等）由各自 `vite.config.ts` 显式 alias 到**本项目的** `node_modules`，
避免四份依赖版本错位。加新共享代码时，四个项目的 alias 已经指向同一目录，不需要额外配置。

## 启动

后端 `server-java` 监听 `8642`，四个前端 dev server 通过 `/api`、`/ws` 代理到后端
（代理目标已在各 `vite.config.ts` 中**固定写死为 8642**，不读取后端配置）。以管理端为例：

```bash
cd admin-web
npm install
npm run dev      # http://localhost:5176
```

其余三个同理（`cd org-web` / `user-web` / `protectee-web`，端口 5174 / 5175 / 5177）。

> 改后端端口时**必须同步改这四个 `vite.config.ts`**。不同步的症状是"前端登录 500、
> Swagger 直连正常"，这个坑踩过一次，见 `docs/final/17-开发日记-2026-08-08.md`。

## 登录

- 每个项目登录页固定本端 `principalType`：机构端 `ORGANIZATION`、监护/志愿者端 `USER`、
  管理端 `ADMIN`、受护人测试端 `USER`（受护人账号不能自助注册，只能由监护人代建）。
- 登录后令牌与角色信息存于 `localStorage`，请求封装 `src/api/request.ts` 自动附加
  `Authorization: Bearer <token>`，401 自动登出。
- 管理端页面权限由登录返回的 `permissions` 判断（如 `USER_MANAGE` / `ORG_MANAGE` /
  `ABUSE_REVIEW` / `ADMIN_GOVERN`），路由守卫按权限显隐并拦截直达。

## 页面与接口

各端页面已随后端迭代补齐（活动发布与报名、群聊、实时会话与语音留言、站内通知、
举报治理与处罚等），逐项清单与设计取舍见 `docs/final/17-开发日记-*.md`——前端不做
重复的接口文档，接口以 `docs/final/api/openapi.yaml` 为准。尚未实现的页面以
`el-empty` 占位，随后端迭代填充。

## 验证

```bash
cd <project>
npm run type-check
npm run build
```
