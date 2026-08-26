# protectee-web（受护人端 · 临时测试用）

Sullivan 受护人（PROTECTEE）的临时 Web 测试前端。受护人账号不能自助注册，只能由监护人通过 `user-web` 的「创建受护人」或后端 `POST /api/v1/guardians/protectees` 创建。

> 这是临时测试端，后续如需正式受护人端可在此基础上迭代。端口 `5177`。

## 启动

```bash
cd protectee-web
npm install
npm run dev      # http://localhost:5177
```

后端 `server-java` 需运行并监听 `8642`（dev server 通过 `/api`、`/ws` 代理到该端口）。

## 页面与接口（均已接后端已实现接口）

| 页面 | 路由 | 接口 |
|---|---|---|
| 登录 | `/login` | `POST /auth/login`（principalType=USER）、找回联系方式提醒 |
| 找回密码 | `/forgot-password` | `POST /auth/password-recovery/*` |
| 绑定管理 | `/bindings` | `GET/POST /bindings`、`/bindings/{id}/accept\|reject\|cancel\|terminate` |
| 活动浏览（只读） | `/activities` | `GET /activities`（公开列表，分页） |
| 实时会话 | `/realtime` | `/realtime/sessions` + `/ws/realtime`（需 ACTIVE 绑定） |

## 测试建议流程

1. `user-web`（5175）以监护人登录 → 创建受护人（得到账号 ID）
2. 本端（5177）用受护人账号登录
3. 绑定管理：申请监护人（输入监护人 ID）→ 监护人在 user-web 确认 → 绑定生效
4. 实时会话：与已绑定的监护人互发消息

## 说明

- 视觉沿用三端统一的墨线笔记本手绘涂鸦主题（`src/styles/global.scss` 与 user-web 逐字节一致）。
- 受护人不可用的能力（注册、志愿者兼任、活动报名、报警/位置/公益服务）未在本端展示。
