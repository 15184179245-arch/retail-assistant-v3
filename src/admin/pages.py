# -*- coding: utf-8 -*-
"""I5 网页后台·治理页（服务端渲染，纯 stdlib，无前端框架）。

GET /admin/gov 渲染本页：同义词词典 / 查询日志 / 问了没货TOP / 串货台账 / 待补全队列 / 别名留痕。
数据由 admin.governance 只读函数提供；同义词新增走 POST /api/admin/gov/synonym。
"""
import html

from admin import governance


def _esc(s):
    return html.escape(str(s if s is not None else ""))


def render_gov_page() -> str:
    synonyms = governance.synonym_groups(100)
    query_log = governance.query_log(60)
    zero_top = governance.zero_hit_top(15)
    strade = governance.strade_ledger()
    custom = governance.pending_custom_queue()
    alias = governance.alias_recent(30)

    def rows(items, fields):
        if not items:
            return '<tr><td colspan="%d" style="color:#999">暂无数据</td></tr>' % len(fields)
        out = []
        for it in items:
            tds = "".join("<td>%s</td>" % _esc(it.get(f)) for f in fields)
            out.append("<tr>%s</tr>" % tds)
        return "".join(out)

    syn_rows = "".join(
        "<tr><td>%s</td><td>%s</td><td>%s</td></tr>" % (
            _esc(s["canonical"]), _esc("、".join(s["synonyms"])), _esc(s.get("category") or ""),
        )
        for s in synonyms
    ) or "<tr><td colspan=3>暂无</td></tr>"

    log_rows = "".join(
        "<tr><td>%s</td><td>%s</td><td>%s</td></tr>" % (
            _esc(r["keyword"]), r["result_count"], r["timestamp"],
        )
        for r in query_log
    ) or "<tr><td colspan=3>暂无</td></tr>"

    zero_rows = "".join(
        "<tr><td>%s</td><td>%s</td></tr>" % (_esc(z["keyword"]), z["count"])
        for z in zero_top
    ) or "<tr><td colspan=2>暂无</td></tr>"

    strade_rows = "".join(
        "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
            _esc(s["temp_code"]), _esc(s["name"]), _esc(s["sell_price"]),
            _esc(s["reimburse_status"]), _esc(s["created_at"]),
        )
        for s in strade
    ) or "<tr><td colspan=5>暂无</td></tr>"

    custom_rows = "".join(
        "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
            _esc(c["sku"]), _esc(c["name"]), _esc(c["pending_barcode"]), _esc(c["updated_at"]),
        )
        for c in custom
    ) or "<tr><td colspan=4>暂无</td></tr>"

    alias_rows = "".join(
        "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
            _esc(a["barcode"]), _esc(a["sku"]), _esc(a["source"]), _esc(a["created_at"]),
        )
        for a in alias
    ) or "<tr><td colspan=4>暂无</td></tr>"

    style = (
        "body{font-family:system-ui,sans-serif;margin:20px;color:#222}"
        "h2{margin:24px 0 8px}table{border-collapse:collapse;width:100%;margin-bottom:8px}"
        "td,th{border:1px solid #ddd;padding:6px 8px;font-size:13px;text-align:left}"
        "th{background:#f5f5f5}input{padding:6px;margin-right:6px;width:220px}"
        "button{padding:6px 14px}"
    )

    return (
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>治理队列</title>"
        "<style>" + style + "</style></head><body>"
        "<h1>治理队列 · 同义词 / 查询日志 / 串货 / 待补全</h1>"
        "<p>口径：查询日志与问了没货只统计已出库检索日志（usage_stats）；串货/待补全来自 APP 镜像（单向，只读）。"
        "返回 <a href='/dashboard'>经营大盘</a></p>"

        "<h2>同义词词典（确认即生效，检索即时可用）</h2>"
        "<form method='post' action='/api/admin/gov/synonym'>"
        "标准词 <input name='canonical' required> "
        "口语词（逗号分隔） <input name='synonyms' required> "
        "<button type='submit'>新增</button></form>"
        "<table><tr><th>标准词</th><th>同义词</th><th>类目</th></tr>" + syn_rows + "</table>"

        "<h2>查询日志（最近 60 条检索）</h2>"
        "<table><tr><th>关键词</th><th>结果数</th><th>时间</th></tr>" + log_rows + "</table>"

        "<h2>问了没货 TOP15（零命中重复词，学习队列原料）</h2>"
        "<table><tr><th>关键词</th><th>零命中次数</th></tr>" + zero_rows + "</table>"

        "<h2>串货台账（APP 镜像）</h2>"
        "<table><tr><th>临时码</th><th>品名</th><th>售价</th><th>报销状态</th><th>时间</th></tr>"
        + strade_rows + "</table>"

        "<h2>待补全队列（商品信息本地补录）</h2>"
        "<table><tr><th>SKU</th><th>品名</th><th>待绑条码</th><th>更新时间</th></tr>"
        + custom_rows + "</table>"

        "<h2>别名映射留痕（最近 30）</h2>"
        "<table><tr><th>条码</th><th>SKU</th><th>来源</th><th>时间</th></tr>" + alias_rows + "</table>"

        "</body></html>"
    )
