"""熊掌柜（service.example.invalid）→ wujin.db 商品同步。

模块划分：

- :mod:`xzg_client`  只读 API 客户端（认证 / token 缓存 / 分页拉取）
- :mod:`xzg_mapper`  字段映射与脏数据检测（纯函数）
- :mod:`xzg_sync`    差异计算与落库（默认 dry-run）

业务背景与 API 契约见 ``research/xiongzhanggui-data-source-2026-09-03.md``。
"""
