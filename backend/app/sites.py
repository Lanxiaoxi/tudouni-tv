"""数据源配置（苹果 CMS V10 格式）。

移植自前端 js/customer_site.js 的 CUSTOMER_SITES + js/config.js 的 testSource。
后端聚合时使用本列表；前端 customer_site.js 仍用于设置面板展示与勾选。
第三方源可能随时失效，失效时删除对应项即可。
"""

SITES: dict[str, dict] = {
    "jinying":  {"api": "http://jyzyapi.com/provide/vod",              "name": "金鹰资源", "adult": False},
    "guangsu":  {"api": "http://api.guangsuapi.com/api.php/provide/vod", "name": "光速资源", "adult": False},
    "uku":      {"api": "http://api.ukuapi.com/api.php/provide/vod",     "name": "U酷资源", "adult": False},
    "baidu":    {"api": "https://api.apibdzy.com/api.php/provide/vod/",  "name": "百度资源", "adult": False},
    "wujin":    {"api": "https://p2100.net/api.php/provide/vod/",        "name": "无尽资源", "adult": False},
    "subo":     {"api": "https://api.wujinapi.com/api.php/provide/vod/", "name": "速博资源", "adult": False},
    "modu":     {"api": "https://caiji.moduapi.cc/api.php/provide/vod/", "name": "魔都资源", "adult": False},
    "zuidazy":  {"api": "http://zuidazy.me/api.php/provide/vod/",        "name": "最大资源", "adult": False},
    "huohu":    {"api": "https://hhzyapi.com/api.php/provide/vod/",      "name": "火狐资源", "adult": False},
    "dadi":     {"api": "https://dadiapi.com/feifei2",                   "name": "大地资源", "adult": False},
    # 保留前端 config.js 里的测试源（adult 标记，聚合时默认排除）
    "testSource": {"api": "https://www.example.com/api.php/provide/vod", "name": "空内容测试源", "adult": True},
}

# 苹果 CMS 接口参数（与前端 js/config.js API_CONFIG 对齐）
SEARCH_PATH = "?ac=videolist&wd="
LIST_PATH = "?ac=videolist&pg="


def parse_sources(source: str | None, api_url: str | None) -> list[tuple[str, dict]]:
    """解析 source 参数为 [(key, site), ...]。

    - source=custom 时必带 api_url，返回 [(custom, {api: api_url, ...})]
    - source=key1,key2 返回对应内置源（无效 key 忽略）
    - 不传 source 返回全部普通源（排除 adult）
    """
    if source and source.startswith("custom"):
        if not api_url:
            raise ValueError("自定义源必须提供 api_url 参数")
        return [("custom", {"api": api_url, "name": "Custom", "adult": False})]
    if source:
        keys = [k for k in source.split(",") if k in SITES]
        return [(k, SITES[k]) for k in keys]
    return [(k, v) for k, v in SITES.items() if not v["adult"]]
