"""文本规范化工具。

上游资源站返回的 vod_remarks 格式五花八门：
  "第12集完结" / "第27集已完结" / "更新至30集" / "正片" / "第3期" ...
其中"第N集(已)完结"表达的是"全剧完结、共 N 集"，但缺"共"字，
统一规范化为"共N集已完结"；其余格式原样保留。
"""

import re

# 仅匹配完整为"第N集(已)完结"的 remarks（首尾锚定，避免误伤"第3期"等）
_EP_FINISHED = re.compile(r"^第\s*(\d+)\s*集(?:已)?完结$")


def normalize_remarks(remarks: str | None) -> str | None:
    if not remarks:
        return remarks
    text = str(remarks).strip()
    m = _EP_FINISHED.match(text)
    if m:
        return f"共{m.group(1)}集已完结"
    return text
