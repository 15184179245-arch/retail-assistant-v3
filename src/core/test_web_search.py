# -*- coding: utf-8 -*-
"""sx_web_search 单测（2026-09-27 联网检索）：回包解析/域名提取/端点钉扎/降级。全部离线。"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core import web_search  # noqa: E402


def _grounding_body(answer_text="公牛LM5塑壳断路器售价约300元（来源：taobao.com）", queries=None,
                    chunks=None):
    meta = {"webSearchQueries": queries or ["公牛 LM5 断路器"],
            "groundingChunks": chunks if chunks is not None else [
                {"web": {"uri": "https://item.taobao.com/item.htm?a=1", "title": "公牛塑壳断路器"}},
                {"web": {"uri": "https://www.jd.com/p/123", "title": "京东 公牛"}},
                {"web": {"uri": "https://jd.com/p/456", "title": "京东重复域名"}},
            ]}
    return {"candidates": [{"content": {"parts": [{"text": answer_text}]},
                            "groundingMetadata": meta}]}


class ParseTests(unittest.TestCase):
    def test_接地回包解析(self):
        data = web_search._parse(_grounding_body())
        self.assertIn("300元", data["answer"])
        self.assertEqual(data["queries"], ["公牛 LM5 断路器"])
        domains = [s["domain"] for s in data["sources"]]
        self.assertEqual(domains, ["item.taobao.com", "jd.com"])  # 去重 + www 剥离，子域保留

    def test_来源上限与标题截断(self):
        chunks = [{"web": {"uri": "https://s%d.com/x" % i, "title": "T" * 200}} for i in range(12)]
        data = web_search._parse(_grounding_body(chunks=chunks))
        self.assertEqual(len(data["sources"]), web_search._SOURCES_CAP)
        self.assertEqual(len(data["sources"][0]["title"]), 120)

    def test_空回包(self):
        data = web_search._parse({"candidates": []})
        self.assertEqual(data["answer"], "")
        self.assertEqual(data["sources"], [])


class PinnedUrlTests(unittest.TestCase):
    def test_回环IP直钉(self):
        self.assertIn("127.0.0.1", web_search._resolve_pinned_url(
            "http://127.0.0.1:8317/v1beta/models/m:generateContent"))

    def test_localhost解析后放行(self):
        pinned = web_search._resolve_pinned_url("http://localhost:8317/x")
        self.assertTrue(pinned.startswith("http://"))

    def test_非回环IP拒绝(self):
        with self.assertRaises(ValueError):
            web_search._resolve_pinned_url("http://8.8.8.8/v1beta/x")

    def test_坏scheme拒绝(self):
        with self.assertRaises(ValueError):
            web_search._resolve_pinned_url("ftp://127.0.0.1/x")
        with self.assertRaises(ValueError):
            web_search._resolve_pinned_url("http:///nohost")


class SearchTests(unittest.TestCase):
    def test_空参返hint(self):
        r = web_search.search("  ")
        self.assertIn("hint", r)

    def test_端点校验拒绝不发请求(self):
        with mock.patch.object(web_search, "_config",
                               return_value=("http://8.8.8.8/v1beta/m:generateContent", "k", 5)), \
             mock.patch.object(web_search, "_post_json") as post:
            r = web_search.search("公牛断路器")
        self.assertIn("hint", r)
        post.assert_not_called()

    def test_成功返回解析结果(self):
        with mock.patch.object(web_search, "_config",
                               return_value=("http://127.0.0.1:8317/v1beta/m:generateContent", "k", 5)), \
             mock.patch.object(web_search, "_post_json",
                               return_value=_grounding_body()):
            r = web_search.search("公牛断路器")
        self.assertIn("300元", r["answer"])
        self.assertEqual(r["sources"][0]["domain"], "item.taobao.com")

    def test_请求异常降级不抛(self):
        with mock.patch.object(web_search, "_config",
                               return_value=("http://127.0.0.1:8317/v1beta/m:generateContent", "k", 5)), \
             mock.patch.object(web_search, "_post_json", side_effect=OSError("refused")):
            r = web_search.search("公牛断路器")
        self.assertEqual(r["answer"], "")
        self.assertIn("联网检索暂不可用", r["hint"])


class ReadUrlTests(unittest.TestCase):
    def test_公网IP放行_内网回环全拒(self):
        self.assertEqual(web_search._validate_public_url("http://8.8.8.8/x"),
                         "http://8.8.8.8/x")
        for bad in ("http://127.0.0.1:18767/x", "http://192.0.2.10/x",
                    "http://192.0.2.10/x", "http://169.254.169.254/latest/meta-data",
                    "ftp://8.8.8.8/x", "http:///nohost"):
            with self.assertRaises(ValueError):
                web_search._validate_public_url(bad)

    def test_解析urlContext回包(self):
        out = {"candidates": [{"content": {"parts": [{"text": "页面标题是Example Domain"}]},
                               "urlContextMetadata": {"urlMetadata": [
                                   {"retrievedUrl": "https://example.com/",
                                    "urlRetrievalStatus": "URL_RETRIEVAL_STATUS_SUCCESS"}]}}]}
        data = web_search._parse_read(out)
        self.assertIn("Example", data["answer"])
        self.assertEqual(data["urls"][0]["status"], "URL_RETRIEVAL_STATUS_SUCCESS")

    def test_空参与非法URL降级(self):
        self.assertIn("hint", web_search.read_url(""))
        self.assertIn("hint", web_search.read_url("http://127.0.0.1:18767/secret"))
        self.assertIn("hint", web_search.read_url("ftp://8.8.8.8/x"))

    def test_成功返回摘要与URL列表(self):
        body = {"candidates": [{"content": {"parts": [{"text": "断路器参数：250A 壳架"}]},
                                "urlContextMetadata": {"urlMetadata": [
                                    {"retrievedUrl": "https://item.taobao.com/item.htm",
                                     "urlRetrievalStatus": "URL_RETRIEVAL_STATUS_SUCCESS"}]}}]}
        with mock.patch.object(web_search, "_config",
                               return_value=("http://127.0.0.1:8317/v1beta/m:generateContent", "k", 5)), \
             mock.patch.object(web_search, "_post_json", return_value=body):
            r = web_search.read_url("https://item.taobao.com/item.htm", question="参数")
        self.assertIn("250A", r["answer"])
        self.assertEqual(len(r["urls"]), 1)

    def test_请求异常降级不抛(self):
        with mock.patch.object(web_search, "_config",
                               return_value=("http://127.0.0.1:8317/v1beta/m:generateContent", "k", 5)), \
             mock.patch.object(web_search, "_post_json", side_effect=OSError("refused")):
            r = web_search.read_url("https://example.com/x")
        self.assertEqual(r["answer"], "")
        self.assertIn("网页读取暂不可用", r["hint"])


if __name__ == "__main__":
    unittest.main()
