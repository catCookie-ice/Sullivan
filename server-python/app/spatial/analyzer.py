"""
空间分析 (FR-08)

方位角、相对距离估算 (bbox 下沿 + 地平面假设)、运动趋势
精度有限，满足"简短可执行"辅助提示即可。
"""
import numpy as np


class SpatialInfo:
    """空间分析结果"""
    def __init__(self, azimuth: str, distance_m: float | None, motion_hint: str | None):
        self.azimuth = azimuth  # "前方偏左" / "正前方" / "右侧"
        self.distance_m = distance_m
        self.motion_hint = motion_hint


def analyze(masks: np.ndarray, boxes: list, class_ids: list) -> list[SpatialInfo]:
    """
    分析障碍物空间关系。
    - 方位: 根据 bbox 中心在画面水平位置
    - 距离: bbox 下沿 + 地平面假设
    - 运动: 帧间跟踪 (M3 实现)
    """
    # TODO(M3): spatial analysis implementation
    return []
