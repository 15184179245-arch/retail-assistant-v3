# -*- coding: utf-8 -*-
"""src/agent/ — I16 APP 常驻 Agent 的网关侧桥接层。

组成：pending_store（写操作票据库）/ agent_bridge（Hermes 桥+降级）/ fallback（离线确定性路由）。
红线：票据式写操作——AI 结构上无法自确认（MCP 侧没有 confirm 工具）。
"""
