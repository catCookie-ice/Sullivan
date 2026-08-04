"""SQLAlchemy 模型 — 统一 sullivan 数据库中的导航表"""
from sqlalchemy import Column, String, DateTime, BigInteger, Integer, Text, JSON
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


class NavigationSession(Base):
    __tablename__ = "navigation_session"

    id = Column(String(26), primary_key=True)
    status = Column(String(16), nullable=False, default="ACTIVE")
    created_at = Column(DateTime(3), nullable=False)
    updated_at = Column(DateTime(3), nullable=False)
    deleted_at = Column(DateTime(3), nullable=True)
    version = Column(BigInteger, nullable=False, default=0)

    protectee_uid = Column(String(26), nullable=False, comment="关联 user_account.id，以 JWT 主体校验归属")
    model_version_id = Column(String(26), nullable=True)
    started_at = Column(DateTime(3), nullable=False)
    ended_at = Column(DateTime(3), nullable=True)
    failure_code = Column(String(64), nullable=True)
