# Java 业务应用

## 启动

要求 JDK 21，并确认 `JAVA_HOME` 指向 JDK 21。进入 `server-java/server-java` 目录，复制 `.env.example` 为 `.env`，填写：

```properties
DB_USERNAME=root
DB_PASSWORD=你的 MySQL 密码
BOOTSTRAP_ADMIN_LOGIN=最高管理员登录名
BOOTSTRAP_ADMIN_PASSWORD=最高管理员初始密码
BOOTSTRAP_ADMIN_DISPLAY_NAME=最高管理员显示名
```

然后运行：

```powershell
./mvnw.cmd spring-boot:run
```

### 端口与配置

- **服务端口**在 `src/main/resources/application.properties` 中指定（`server.port`，默认 `8080`）。临时换端口直接改该行，或在 `.env` 中设置 `SERVER_PORT` 覆盖。
- **运行环境**由环境变量 `APP_ENV` 区分：`dev`（开发，默认）/ `prod`（生产）。开发环境额外提供 `GET /dev/routes` 查看全部已注册路由；生产环境不注册该接口。
- **完整示例配置**见 `src/main/resources/application.example.properties`（提交入库，不会被自动加载），列出全部可配置项的中文说明与示例。
- `.env` 不入库；本地覆盖数据库密码、JWT 密钥、最高管理员初始化等信息，模板见 `.env.example`。

应用启动时先执行 Flyway，再检查数据库是否存在启用的 `SUPER_ADMIN`。如果不存在，就用上述配置创建一个；如果配置缺失，应用会拒绝启动。

验证命令：

```powershell
./mvnw.cmd test
./mvnw.cmd -DskipTests package
./mvnw.cmd spring-boot:run
```

2026-08-04 已使用 JDK 21 和本机 MySQL 9.1 完成真实启动验证，`/actuator/health` 返回 `HTTP 200`。MySQL 9.1 会产生 Flyway 版本支持范围提示；正式环境仍建议使用项目文档约定的 MySQL 8.0 兼容版本。

## 接口文档（OpenAPI / Swagger）

应用内置 springdoc-openapi，启动后（默认端口 `8080`）：

| 地址 | 说明 |
|---|---|
| `http://localhost:8080/swagger-ui/index.html` | Swagger UI 交互式文档（`/swagger-ui.html` 会自动跳转） |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON 契约 |
| `http://localhost:8080/v3/api-docs.yaml` | OpenAPI YAML 契约 |

每个接口与每个请求/响应参数均带中文说明与示例；需要登录的接口会显示 `bearerAuth` 锁定标记，点击后填入登录接口返回的 `accessToken` 即可调试。

应用启动就绪时会在控制台打印简短横幅（端口、文档地址，dev 环境还会提示路由查看接口）：

```
SullivanMINI Java 业务后端已启动（运行环境：dev）
服务端口    : 8080
健康检查    : http://localhost:8080/actuator/health
Swagger UI  : http://localhost:8080/swagger-ui/index.html
OpenAPI JSON: http://localhost:8080/v3/api-docs
路由列表接口: http://localhost:8080/dev/routes（仅 dev 环境可用）
```

完整的已注册路由列表不在启动时打印，改为开发环境的调试接口 `GET /dev/routes`（仅 `APP_ENV=dev` 时注册，返回 `[{method, path, handler}]` 列表；生产环境该接口不存在）。

## 注册接口

| 接口 | 说明 |
|---|---|
| `POST /api/v1/auth/register/user` | 注册监护人或志愿者 |
| `POST /api/v1/auth/register/organization` | 注册机构，初始状态 `PENDING` |
| `POST /api/v1/auth/register/admin` | 使用一次性注册码注册管理员 |
| `POST /api/v1/guardians/protectees` | 监护人创建受护人并建立永久绑定 |
| `POST /api/v1/guardians/bindings` | 监护人发起其它绑定请求 |
| `POST /api/v1/guardians/bindings/{id}/accept` | 受护人确认绑定 |
| `POST /api/v1/users/me/roles/volunteer` | 监护人兼任志愿者 |
| `POST /api/v1/admin/registration-codes` | 最高管理员或 HR 管理员创建注册码 |

注册码只保存 SHA-256 哈希，成功注册后立即核销。注册码携带的 `initialRoleCode` 决定新管理员的初始权限。

自然人（受护人除外）与机构注册时支持可选字段 `recoveryPhone` / `recoveryEmail`（找回联系方式，半加密两段存储：手机号后四位 / 邮箱 local 前 1/4 段明文，其余 HMAC-SHA256 单向加密，不加密段即密钥）。留空不影响注册，但密码将无法通过手机号/邮箱找回。

## 找回联系方式与找回密码

| 接口 | 说明 | 认证 |
|---|---|---|
| `POST /api/v1/auth/password-recovery/start` | 开始找回：返回可用找回方式与不加密前缀 | 公开 |
| `POST /api/v1/auth/password-recovery/verify` | 补全联系方式并验证（发送验证码与后续重置留空） | 公开 |
| `GET /api/v1/auth/me/recovery` | 查看我的找回联系方式状态与提醒标志 | 已登录 |
| `PUT /api/v1/auth/me/recovery` | 设置/更新找回联系方式（至少填手机号或邮箱一个） | 已登录 |
| `POST /api/v1/auth/me/recovery/remind-later` | “下次再说”：一周后再提醒 | 已登录 |
| `POST /api/v1/auth/me/recovery/dismiss` | “不再提醒” | 已登录 |

登录响应 `TokenResponse` 含 `recoveryReminder`：未设置任何找回联系方式且未关闭提醒（或距上次“下次再说”满一周）时为 `true`，前端据此弹出设置提醒。

## 对象查找接口

| 接口 | 说明 | 权限 |
|---|---|---|
| `GET /api/v1/users/{userId}` | 按唯一标识查找自然人（志愿者/监护人/受护人共用，以 `roles` 区分） | 已登录 |
| `GET /api/v1/organizations/{organizationId}` | 按唯一标识查找公益机构 | 已登录 |
| `GET /api/v1/admins/{adminId}` | 按唯一标识查找管理员 | `ADMIN_MANAGE` 权限（最高管理员或 HR 管理员） |

查找接口统一不返回密码哈希；不存在的 ID 返回 `404`。管理员查找权限门槛遵循 `docs/final/03-角色权限与数据范围.md`。

## 管理端·用户与机构管理（`USER_MANAGE` / `ORG_MANAGE`）

| 接口 | 说明 | 权限 |
|---|---|---|
| `GET /api/v1/admin/users?role=GUARDIAN\|PROTECTEE\|VOLUNTEER` | 分页用户列表，支持关键字搜索 | `USER_MANAGE` |
| `DELETE /api/v1/admin/users/{userId}` | 删除用户（逻辑删除） | `USER_MANAGE` |
| `GET /api/v1/admin/organizations?status=...` | 分页机构列表 | `ORG_MANAGE` |
| `DELETE /api/v1/admin/organizations/{organizationId}` | 删除机构（逻辑删除） | `ORG_MANAGE` |

语义说明：

- **志愿者列表**同时包含兼任志愿者的监护人；每个用户都带 `roles`（当前生效角色）与 `alsoVolunteer`（是否兼任志愿者）字段，监护人列表同样可看出是否兼任志愿者。
- **删除用户**：若目标用户是兼任志愿者的监护人，则仅取消其志愿者兼任（撤销 `VOLUNTEER` 角色），账号保留，响应 `action=VOLUNTEER_ROLE_REVOKED`；其余情况逻辑删除整个账号（`action=ACCOUNT_DELETED`），同时撤销全部角色、逻辑删除资料并终止其监护绑定。
- **逻辑删除**：统一写入 `deleted_at`（MyBatis-Plus `@TableLogic`），删除后登录、查找、列表均不再可见。
- `USER_MANAGE` / `ORG_MANAGE` 授予最高管理员与运营管理员（OPERATIONS）。
