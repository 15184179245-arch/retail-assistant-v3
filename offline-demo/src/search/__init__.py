"""search 微服务包（FTS5+jieba+LLM 改写检索）。

``__init__`` 不导出 ``search_server``：服务以脚本方式运行
（``python src/search/search_server.py``），且该模块导入即产生监听副作用；
本包内可独立导入的纯函数模块（如 :mod:`search.sale_status`）供服务与单测共用。
"""
