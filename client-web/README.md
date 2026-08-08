# Sullivan 前端项目

`client-web` 下包含三个独立、自包含的 Vue 3 前端项目（各含独立 `package.json`，公共代码按项目复制，无共享包）：

| 目录 | 面向角色 | dev 端口 | 说明 |
|---|---|---|---|
| `org-web/` | 公益机构 | 5174 | 机构资料、服务/申请/活动/报名管理 |
| `user-web/` | 监护人 + 志愿者 | 5175 | 绑定管理、创建受护人、兼任志愿者、活动报名等 |
| `admin-web/` | 管理员 | 5176 | 用户管理、机构管理、注册码、运营统计 |

## 启动

后端 `server-java` 监听 `8642`，三个前端 dev server 通过 `/api` 代理到后端（代理目标已在各 `vite.config.ts` 中**固定写死为 8642**）。以管理端为例：

```bash
cd admin-web
npm install
npm run dev      # http://localhost:5176
```

其余两个同理（`cd org-web` / `cd user-web`，端口 5174 / 5175）。

## 登录

- 每个项目登录页固定本端 `principalType`：机构端 `ORGANIZATION`、监护/志愿者端 `USER`、管理端 `ADMIN`。
- 登录后令牌与角色信息存于 `localStorage`，请求封装 `src/api/request.ts` 自动附加 `Authorization: Bearer <token>`，401 自动登出。
- 管理端页面权限由登录返回的 `permissions` 判断（如 `USER_MANAGE` / `ORG_MANAGE`）。

## 已实现页面与后端接口

各端已接后端已实现的接口；其余页面为占位（`el-empty`），随后端迭代填充：

- **org-web**：登录、机构资料（`GET /organizations/{id}`）。
- **user-web**：登录、创建受护人（`POST /guardians/protectees`）、发起绑定（`POST /guardians/bindings`）、接受绑定（`POST /guardians/bindings/{id}/accept`）、兼任志愿者（`POST /users/me/roles/volunteer`）、按 ID 查用户（`GET /users/{id}`）。
- **admin-web**：登录、用户列表/删除（`GET|DELETE /admin/users`，`USER_MANAGE`）、机构列表/删除（`GET|DELETE /admin/organizations`，`ORG_MANAGE`）、创建注册码（`POST /admin/registration-codes`）。

## 验证

```bash
cd <project>
npm run type-check
npm run build
```
