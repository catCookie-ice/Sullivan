"""
JWT 校验。只校验 PROTECTEE 角色 + 会话归属。
不涉及行级权限，鉴权面窄 (docs/final/13 §3.3)。
"""
from jose import JWTError, jwt
from app.core.config import settings


class NavigationPrincipal:
    """从 JWT 解析出的导航调用者身份。"""

    def __init__(self, uid: str, role: str, session_id: str | None = None):
        self.uid = uid
        self.role = role
        self.session_id = session_id

    @property
    def is_protectee(self) -> bool:
        return self.role == "PROTECTEE"


def verify_token(token: str) -> NavigationPrincipal | None:
    """校验 JWT，返回调用者身份。失败返回 None。"""
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
        principal = NavigationPrincipal(
            uid=payload.get("sub"),
            role=payload.get("role"),
        )
        if not principal.is_protectee:
            return None
        return principal
    except JWTError:
        return None
