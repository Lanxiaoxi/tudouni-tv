"""SSRF 防护：解析目标 URL 并校验其解析出的 IP 是否为内网/保留地址。

修复了旧 server.mjs 的 IPv6 绕过（`[::1]` 带方括号不匹配黑名单的问题）：
统一把 hostname 解析成 IP 列表后逐个用 ipaddress 判定，IPv4-mapped IPv6
（如 ::ffff:127.0.0.1）也会被显式还原校验。
"""

import asyncio
import ipaddress
import socket
from urllib.parse import urlparse

from fastapi import HTTPException


def _is_blocked_ip(ip_str: str) -> bool:
    host = ip_str.split("%")[0]  # 去掉 IPv6 链路本地 zone 后缀
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return True  # 解析不了的一律视为危险

    if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_reserved \
            or ip.is_multicast or ip.is_unspecified:
        return True
    # IPv4 映射的 IPv6（::ffff:127.0.0.1）还原后按 IPv4 判定
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        return _is_blocked_ip(str(ip.ipv4_mapped))
    return False


async def validate_target_url(url_str: str) -> str:
    parsed = urlparse(url_str)
    if parsed.scheme not in ("http", "https"):
        raise HTTPException(400, "仅允许 http/https 协议")
    host = parsed.hostname
    if not host:
        raise HTTPException(400, "无效的 URL")

    # hostname 本身是 IP 字面量
    try:
        ip = ipaddress.ip_address(host)
        if _is_blocked_ip(str(ip)):
            raise HTTPException(400, "目标地址不允许访问")
        return url_str
    except ValueError:
        pass  # 是域名，继续

    # 域名：解析出全部地址，逐个校验（防 DNS 返回多个地址时漏网）
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        infos = await asyncio.to_thread(socket.getaddrinfo, host, port)
    except socket.gaierror:
        raise HTTPException(400, "目标域名解析失败")
    for info in infos:
        if _is_blocked_ip(info[4][0]):
            raise HTTPException(400, "目标地址为内网/保留地址")
    return url_str
