from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置，从环境变量读取。"""

    # 服务
    host: str = "0.0.0.0"
    port: int = 8000

    # 数据库
    db_url: str = "mysql+pymysql://sullivan:change_me_app@127.0.0.1:3306/sullivan"

    # JWT
    jwt_secret: str = "change_me_to_a_long_random_string_at_least_32_bytes"

    # 推理
    model_path: str = "/models/yolov8n-seg.onnx"
    device: str = "cuda"
    precision: str = "fp16"

    # 帧约束
    max_frame_bytes: int = 512 * 1024  # 512 KB
    max_frame_width: int = 1280
    max_frame_height: int = 960
    target_fps: int = 10

    # 会话
    session_idle_timeout: int = 120  # 120s 无帧 → EXPIRED

    # 采样
    sample_rate_normal: float = 0.01  # 正常帧 1%
    sample_rate_danger: float = 1.0  # DANGER/FAILED 100%

    model_config = {"env_prefix": "NAV_", "env_file": ".env"}


settings = Settings()
