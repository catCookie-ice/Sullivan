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

应用启动时先执行 Flyway，再检查数据库是否存在启用的 `SUPER_ADMIN`。如果不存在，就用上述配置创建一个；如果配置缺失，应用会拒绝启动。

验证命令：

```powershell
./mvnw.cmd test
./mvnw.cmd -DskipTests package
./mvnw.cmd spring-boot:run
```

2026-08-04 已使用 JDK 21 和本机 MySQL 9.1 完成真实启动验证，`/actuator/health` 返回 `HTTP 200`。MySQL 9.1 会产生 Flyway 版本支持范围提示；正式环境仍建议使用项目文档约定的 MySQL 8.0 兼容版本。

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
