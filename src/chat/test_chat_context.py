"""I6 浮窗商品上下文拼装纯函数单测 + api_agent data=null 容错回归（2026-09-11 现网事故）。"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from chat import chat_server  # noqa: E402
from chat.chat_server import _format_product_context  # noqa: E402


class TestFormatProductContext(unittest.TestCase):
    def test_三字段全拼(self):
        line = _format_product_context({"sku": "192.0.2.10", "name": "膨胀螺丝", "spec": "M6"})
        self.assertIn("膨胀螺丝", line)
        self.assertIn("M6", line)
        self.assertIn("192.0.2.10", line)

    def test_缺规格缺sku只剩名(self):
        line = _format_product_context({"name": "扎带"})
        self.assertEqual("当前商品：扎带", line)

    def test_无名返回空串(self):
        self.assertEqual("", _format_product_context({"sku": "x"}))
        self.assertEqual("", _format_product_context({}))
        self.assertEqual("", _format_product_context(None))
        self.assertEqual("", _format_product_context("膨胀螺丝"))


class TestApiAgentNullData(unittest.TestCase):
    """2026-09-11 现网事故回归：search 内部异常回包 data=null（非缺键），
    .get("data", []) 拿到 None，products[:8] TypeError 把整问打成"没找到相关商品"。"""

    def test_search_knowledge返回data_null_正常出答(self):
        with mock.patch.object(chat_server, "_call_search",
                               return_value={"success": False, "data": None}), \
             mock.patch.object(chat_server, "_call_knowledge",
                               return_value={"success": False, "data": None}), \
             mock.patch.object(chat_server, "recall_memory", return_value={"data": []}), \
             mock.patch.object(chat_server, "_llm_chat_response",
                               return_value="生料带是四氟乙烯带，缠螺纹"), \
             mock.patch.object(chat_server, "get_chat_db",
                               side_effect=RuntimeError("test: no db")):
            r = chat_server.api_agent([{"role": "user", "content": "生料带"}], "s1")
        # 旧代码此处 crash 进 except，答非所问地回"没找到相关商品"；修后正常出答
        self.assertEqual(r["response"], "生料带是四氟乙烯带，缠螺纹")
        self.assertEqual(r["products"], [])
        self.assertEqual(r["knowledge"], [])


if __name__ == "__main__":
    unittest.main()
