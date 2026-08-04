# Sullivan MINI

视障人士出行辅助与公益服务平台（毕业设计 MINI 版）。

规格文档见 [`docs/final`](./docs/final/README.md)。**开发前先读 `00-范围基线与演进路线.md`**，它定义了 `[MINI]` / `[FULL]` 边界与发布门槛。

## 仓库结构

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `docs/` | 规格文档（权威） | Markdown |
| `server-java/` | 统一业务后端：账号、权限、公益、报警、管理 | Java 21 + Spring Boot 3 |
| `server-python/` | 导航推理与空间分析 | Python 3.11 + FastAPI |
| `sullivan-vision/` | 离线模型评测与 ONNX 导出 | PyTorch + Ultralytics |
| `client-android/` | 受护人/监护人/志愿者 App | Kotlin + Compose |
| `client-web/` | 四角色管理端 | Vue 3 + TypeScript |
| `deploy/` | 容器编排与初始化脚本 | Docker Compose |

整体架构见 `docs/final/06-系统架构设计.md`，技术取舍见 `docs/final/13-技术栈选型与架构决策.md`。

## 应用与端口

| 应用 | 端口 | 数据库 |
|---|---|---|
| server-java | 8080 | `sullivan` |
| server-python | 8000 | `sullivan` |
| client-web (dev) | 5173 | — |

Java 是业务数据唯一写入方，Python 只读写导航域数据；两端使用同一 `sullivan` 数据库，不再拆分服务 schema。

## 本地启动

```bash
cp .env.example .env          # 按需改密码
docker compose up -d          # MySQL / Redis / MinIO
```

然后分别启动各端，见各目录下的 README。

## 当前状态

架构重设计阶段（`15` 的 M1）。Java 已完成注册、角色关系、管理员注册码和最高管理员启动初始化骨架；其余业务模块仍按计划开发。

M1 待办见 `docs/final/15-开发计划与实施路线.md` §3。其中数据库统一迁移仍阻塞后续业务开发：

1. 合并四个旧迁移目录，并执行 `14` §2 的 DDL 修正
2. 将接口请求/响应字段补充到 `docs/final/api/openapi.yaml`

## 开发约定

- 扩展预留必须登记入 `docs/final/16-扩展点登记表.md`，且"当前被引用"须为否。
- 禁止项见该表 §2，14 条，含"不得向公益表追加金额字段"。
- 每阶段自检见 `15` §8。
