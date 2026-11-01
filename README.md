# Sullivan MINI

视障人士出行辅助与公益服务平台（毕业设计 MINI 版）。

**当前版本：v1.0.0**（自 0.x 起的首个破坏性大版本：移除 Python 导航端、重构数据库基线，详见
[`17-开发日记-2026-10-26-数据库重构与Python端移除.md`](./docs/final/17-开发日记-2026-10-26-数据库重构与Python端移除.md)）

规格文档见 [`docs/final`](./docs/final/README.md)。**开发前先读 [`00-范围基线与演进路线.md`](./docs/final/00-范围基线与演进路线.md)**，
它定义了 `[MINI]` / `[FULL]` 边界与发布门槛。

## 仓库结构

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `docs/` | 规格文档（**权威**，与代码冲突时以文档为准） | Markdown |
| `server-java/server-java/` | 统一业务后端：账号、权限、绑定、公益、报警、治理 | Java 21 + Spring Boot 3 + Maven |
| `client-android/client-guardian/` | 监护人 / 志愿者 App（「Sullivan」，`com.sullivan.android`） | Java + CameraX（仅扫码）+ Retrofit |
| `client-android/client-protectee/` | 受护人 App（「Sullivan 守护」，`com.sullivan.protectee`）：**端侧障碍检测 + 预警** | Java + CameraX + ONNX Runtime |
| `client-web/` | 四个独立前端：`org-web` / `user-web` / `admin-web` / `protectee-web`（临时测试端）+ `shared/` | Vue 3 + TypeScript + Vite |
| `tools/vision-training/` | 障碍检测**训练流水线**（离线；App 运行不需要 Python） | Python + ultralytics + ONNX |
| `deploy/` | 容器编排与初始化脚本 | Docker Compose |

DDL 快照在 [`docs/final/db/schema.sql`](./docs/final/db/schema.sql)，须与 Flyway 迁移保持同步。

整体架构见 [`06-系统架构设计.md`](./docs/final/06-系统架构设计.md)，技术取舍见
[`13-技术栈选型与架构决策.md`](./docs/final/13-技术栈选型与架构决策.md)。

## 应用与端口

| 应用 | 端口 | 说明 |
|---|---|---|
| `server-java` | **8642**（`SERVER_PORT` 可覆盖） | 业务数据唯一写入方 |
| `client-web/org-web` | 5174 | 公益机构端 |
| `client-web/user-web` | 5175 | 监护人 + 志愿者端 |
| `client-web/admin-web` | 5176 | 管理端 |
| `client-web/protectee-web` | 5177 | 受护人临时 Web 测试端 |
| MySQL / Redis / MinIO | 3306 / 6379 / 9000 | `docker compose` 提供 |

前端 dev server 通过 `/api`、`/ws` 代理到 **8642**，且代理目标在各自 `vite.config.ts` 里**固定写死**
（不读取后端配置）。**改后端端口时必须同步改前端**，否则登录会 500 而 Swagger 直连正常——
这个坑踩过一次，见 [`17-开发日记-2026-08-08.md`](./docs/final/17-开发日记-2026-08-08.md)。

受护人端的导航与障碍检测全部在**端侧** ONNX Runtime 完成，服务端没有 Python 推理进程。
全系统共用同一个 `sullivan` 数据库，不拆 schema。

## 受护人端：障碍检测与预警

```
CameraX 帧 → Yolo26Detector（640×640，end2end NMS-free）
           → ObjectTracker（IoU 关联）+ TtcEstimator（拟合 ln 框高）+ EgoMotionEstimator（自运动补偿）
           → SceneSnapshot（不可变、可 JSON 回放）
           → ObstacleAdvisor（静态几何）+ DynamicAdvisor（靠近/横穿，带滞回）
           → AnnouncementPolicy（节流/去重/插话）→ PromptAnnouncer（TTS + 振动）
```

当前阈值（**注意：这三个数是工程估计，尚未真机标定**，见 11-01 日记 §7.3 与 §8 OB-01）：

| 参数 | 值 | 含义 |
|---|---|---|
| 检测置信度 | `0.28` | 比常规 0.35 低，为的是不漏掉小障碍；噪声由多帧确认与"低分框须贴地"接住 |
| 播报视野 | `6.0 s` | 预计相遇时间超过它 → 「暂时不会撞到」，不播报 |
| 紧急句阈值 | `1.8 s` | 以内压成「停，中央行人」（句子必须比它描述的时间短） |
| 用户步速 | `1.2 m/s` | 有深度时由静止目标反推，缺样本时用此默认值（宁高不低，误差偏向早报） |
| 语速档位 | `0.85 / 1.15 / 1.45` | 默认已比平台默认快 15%，DANGER 再乘 1.25 |

预计相遇时间用统一公式 `t = Z / (v_ego + λ·Z)`（小孔成像 `h = f·H/Z` 求导得来）：
目标静止时退化为"走过去要多久"，用户站定时退化为尺度通道的 `1/λ`。

延伸阅读：

- [`17-开发日记-2026-11-01-障碍预警与碰撞预测.md`](./docs/final/17-开发日记-2026-11-01-障碍预警与碰撞预测.md)：公式推导、判据表、为什么不报"算不出时间"的目标
- [`23-障碍识别准确性与训练方案.md`](./docs/final/23-障碍识别准确性与训练方案.md)：四层指标、数据集链接、评估协议
- [`tools/vision-training/README.md`](./tools/vision-training/README.md)：训练 / 评估 / 导出 / 契约校验

## 本地启动

```bash
cp .env.example .env          # 按需改密码
docker compose up -d          # MySQL / Redis / MinIO
```

**服务端（JDK 21）**：

```bash
cd server-java/server-java
./mvnw spring-boot:run        # 默认 8642；改端口用 SERVER_PORT=xxxx
```

**前端（Node 18+，每个项目各自安装）**：

```bash
cd client-web/admin-web       # org-web / user-web / protectee-web 同理
npm install && npm run dev    # 5174–5177
```

**Android（JDK 17，两个工程各自独立）**：

```powershell
cd client-android\client-protectee
.\gradlew.bat :app:assembleDebug
```

> ⚠️ **服务端要 JDK 21，Android 要 JDK 17，不是同一个 JDK。**
> Android 侧 `JAVA_HOME` 指向 JDK 17；工程路径含中文，靠 `android.overridePathCheck=true`
> 与 `-Dfile.encoding=GBK` 运行，**不要改这两个参数**。

模型资产不入库（`*.onnx` 已忽略），下载地址见
`client-android/client-protectee/app/src/main/assets/README.txt`。

## 测试

| 范围 | 命令 | 现状 |
|---|---|---|
| 服务端 | `cd server-java/server-java && ./mvnw test` | 305 例通过（[`17-开发日记-2026-10-27.md`](./docs/final/17-开发日记-2026-10-27.md)） |
| 受护人端（纯 JVM 决策层） | `cd client-android/client-protectee && ./gradlew.bat :app:testDebugUnitTest` | **268 例通过**（2026-11-01 实测） |
| 前端 | `cd <project> && npm run type-check && npm run build` | 见 [`client-web/README.md`](./client-web/README.md) |
| 模型契约 | `python tools/vision-training/check_export.py <model.onnx>` | 两份资产 `[OK]` |

`track/` 与 `scene/` 只产出测量值，**所有阈值都在 `assist/`**——所以决策逻辑能脱离 Android
在 JVM 上直接单测，这是上表第二行存在的意义。

## 当前状态

业务主链路已可跑通：注册/登录、扫码绑定与授权、监护人代建受护人、活动发布与报名（含群聊）、
实时会话与语音留言、报警与自动呼叫、站内通知、举报治理与组合式处罚。数据库基线为单 `V1__baseline.sql`
加 `V2`–`V8` 增量迁移。

仍挂着的待办（各自有独立文档，按需查）：

| 编号 | 事项 |
|---|---|
| `18` | 优化前端表现 |
| `19` | 安全与性能加固（传输加密、限流、令牌 `jti` 黑名单等） |
| `20` | Android 无障碍：语音操控（FR-10 欠账）、声纹识别 |
| `21` | 系统级推送（当前靠前台服务轮询，进程被杀就收不到） |
| `22` | TURN 中继服务器（跨网 WebRTC 打洞失败时必需） |
| OB-01…05 | 障碍预警的三个阈值真机标定、站定降级、端到端回放评估、训练集补齐、语速实听（见 11-01 日记 §8） |

## 开发约定

- 文档优先：`docs/final` 是唯一的权威描述，代码与文档冲突时先改代码或先改文档，**不能让两者长期不一致**。
- 扩展预留必须登记入 [`16-扩展点登记表.md`](./docs/final/16-扩展点登记表.md)，且「当前被引用」须为否。
- 禁止项见该表 §2，含「不得向公益表追加金额字段」。
- 阈值只在 `assist/` 层；`track/`、`scene/` 保持"只测量、不判断"。
- 每阶段自检见 [`15-开发计划与实施路线.md`](./docs/final/15-开发计划与实施路线.md) §8。
