-- =============================================================
-- SullivanMINI V8 — 语音/视频/摄像头会话 + 语音留言
--
-- 这一版对应 docs/final/00 §8.1 登记的基线变更：把「语音/视频通话、
-- 监护人查看受护人摄像头」从 [FULL] 提升为 [MINI]，并新增语音留言。
--
-- 改动只有两处，都不新增媒体存储：
--
--   1. realtime_session.session_type 的 CHECK 从「仅 BASIC」扩为
--      BASIC / VOICE / VIDEO / CAMERA / EMERGENCY（16 §1 的 RT-02 由此启用）。
--      LOCATION 仍为 [FULL] 预留，**刻意不加** —— 它的语义（转发位置流）
--      与本次三种媒体会话不同，混进来会让枚举失去区分力。
--
--   2. 新增 voice_message 表，只存留言元数据；音频本体进 MinIO。
--
-- 为什么媒体不入库（N-03 的延续）：
--   语音/视频走 WebRTC，音频视频流根本不经过业务表。服务端只中继信令
--   （offer / answer / ICE candidate），并且**不解析也不落库**这些内容 ——
--   SDP 里有候选 IP，落库等于把用户的网络环境写成永久记录。
--
-- 为什么 voice_message 要单独一张表而不是复用 notification（N-04）：
--   通知是「有没有让用户看到这件事」，留言是「用户留了一段音频」。
--   二者生命周期不同：通知读到就结束了，留言要在收件箱里留很久、
--   要能标记已听、要能删除。合表会让 notification 长出一个它本不该有的状态机。
-- =============================================================

-- ---------- 1. 扩展 realtime_session.session_type 的可达值 ----------
-- MySQL 的 CHECK 约束改名即「先删后加」。这里先 DROP 再 ADD 同一个名字，
-- 因此对「已用新版 schema.sql 建库、CHECK 本就允许新值」的库也能重复执行。
ALTER TABLE realtime_session DROP CHECK ck_realtime_type;

ALTER TABLE realtime_session
  ADD CONSTRAINT ck_realtime_type
  CHECK (session_type IN ('BASIC', 'VOICE', 'VIDEO', 'CAMERA', 'EMERGENCY'));

ALTER TABLE realtime_session
  MODIFY COLUMN session_type VARCHAR(16) NOT NULL DEFAULT 'BASIC'
  COMMENT '会话类型：BASIC/VOICE/VIDEO/CAMERA/EMERGENCY（LOCATION 为 FULL 预留）';

ALTER TABLE realtime_session
  COMMENT = '监护-受护实时会话（长连接消息中继；语音/视频媒体走 WebRTC，媒体不入库）';

-- ---------- 2. voice_message：语音留言元数据 ----------
-- object_key 由服务端生成，客户端永远看不到它，也就无法拼出别人的对象地址。
-- 取回必须走鉴权端点，服务端据此再校验一次「你是不是投递双方之一」。
--
-- deleted_at 与 status='DELETED' 同时存在不是冗余：
-- deleted_at 让 MyBatis-Plus 的逻辑删除自动过滤生效（全库统一写法），
-- status 则让「已删除」在业务状态机上也是合法的可查询值（02 §9.6）。
CREATE TABLE IF NOT EXISTS voice_message (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sender_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送方',
  receiver_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方',
  session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源会话（可选；会话结束后仍保留该值）',
  status VARCHAR(16) NOT NULL DEFAULT 'SENT' COMMENT 'SENT 待收听 / LISTENED 已收听 / DELETED 已删除',
  object_key VARCHAR(255) NOT NULL COMMENT 'MinIO 对象键；客户端不可见，只能经鉴权端点取回',
  duration_ms INT NOT NULL COMMENT '时长（毫秒）；上限由 app.voice-message.max-duration-ms 控制',
  size_bytes BIGINT UNSIGNED NOT NULL COMMENT '音频字节数',
  content_type VARCHAR(64) NOT NULL DEFAULT 'audio/mp4',
  listened_at DATETIME(3) NULL,
  deleted_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY ix_voice_message_receiver (receiver_uid, status, created_at),
  KEY ix_voice_message_sender (sender_uid, created_at),
  CONSTRAINT fk_voice_message_sender FOREIGN KEY (sender_uid) REFERENCES user_account(id),
  CONSTRAINT fk_voice_message_receiver FOREIGN KEY (receiver_uid) REFERENCES user_account(id),
  CONSTRAINT ck_voice_message_status CHECK (status IN ('SENT', 'LISTENED', 'DELETED')),
  CONSTRAINT ck_voice_message_distinct CHECK (sender_uid <> receiver_uid),
  CONSTRAINT ck_voice_message_duration CHECK (duration_ms > 0)
) ENGINE=InnoDB COMMENT='语音留言元数据；音频本体存 MinIO，媒体不入库';
