"""
引导文本规则引擎 (docs/final/13 §3.5)

纯规则实现，不用 LLM。理由：
1. [FULL] 隔离要求
2. P95 <= 500 ms 延迟不可控
3. 安全关键路径需可穷举测试
"""
import yaml
from typing import Optional
from app.spatial.analyzer import SpatialInfo


class GuideEngine:
    """规则引擎 — YAML 规则表 + 优先级仲裁"""

    def __init__(self, rules_path: str = "app/guidance/rules.yaml"):
        with open(rules_path) as f:
            self.rules = yaml.safe_load(f)

    def generate(self, spatial_info: list[SpatialInfo],
                 previous_risk: Optional[str] = None) -> tuple[str, str]:
        """
        生成引导文本和风险级别。
        返回 (risk_level, guide_text)
        """
        # TODO(M8): 规则匹配 → 优先级仲裁 → 重复抑制 → 生成文本
        return "UNKNOWN", "无法确认前方情况，请停下并确认环境"

    def _suppress_repeat(self, current: str, previous: str, risk_level: str) -> bool:
        """相同普通提示至少间隔 2 秒 (FR-08)"""
        return False
