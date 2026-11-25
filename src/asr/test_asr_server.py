"""I17 asr 服务纯函数单测：WAV 包头/解析往返、出网白名单、响应文本提取。"""
import json
import os
import struct
import sys
import unittest
from unittest import mock

sys.path.insert(0, ".")
from src.asr import asr_server


class WrapWavTest(unittest.TestCase):

    def test_裸pcm包头后可被自家解析读回(self):
        pcm = struct.pack("<5h", 1, -2, 3, -4, 5)
        wav = asr_server.wrap_pcm_to_wav(pcm, 16000)
        self.assertEqual(wav[:4], b"RIFF")
        self.assertEqual(asr_server._read_wav(wav), [1, -2, 3, -4, 5])

    def test_非wav返回none(self):
        self.assertIsNone(asr_server._read_wav(b"OggS" + b"\x00" * 64))
        self.assertIsNone(asr_server._read_wav(b"RI" + b"\x00" * 10))

    def test_奇数data块不越界(self):
        pcm = struct.pack("<3h", 7, 8, 9)
        wav = asr_server.wrap_pcm_to_wav(pcm)
        self.assertEqual(len(asr_server._read_wav(wav)), 3)


class ApiBaseGateTest(unittest.TestCase):

    def test_白名单https域名通过(self):
        url = "https://api.xiaomimimo.com/v1/chat/completions"
        with mock.patch.object(asr_server.socket, "getaddrinfo",
                               return_value=[("", "", "", "", ("93.184.216.34", 443))]):
            self.assertTrue(asr_server._validate_api_base(url))

    def test_私网与环回解析被拒(self):
        url = "https://api.xiaomimimo.com/v1/chat/completions"
        for bad_ip in ("127.0.0.1", "192.0.2.10", "192.0.2.10",
                       "169.254.169.254", "::1", "fe80::1"):
            with mock.patch.object(asr_server.socket, "getaddrinfo",
                                   return_value=[("", "", "", "", (bad_ip, 443))]):
                self.assertFalse(asr_server._validate_api_base(url), bad_ip)

    def test_非https或陌生域名直接拒(self):
        self.assertFalse(asr_server._validate_api_base("http://api.xiaomimimo.com/v1"))
        self.assertFalse(asr_server._validate_api_base("https://evil.example.com/v1"))
        self.assertFalse(asr_server._validate_api_base("not a url"))


class ExtractTextTest(unittest.TestCase):

    def test_openai结构取content(self):
        resp = {"choices": [{"message": {"content": " 自攻螺丝 "}}]}
        self.assertEqual(asr_server._extract_text(resp), "自攻螺丝")

    def test_结构异常返回空(self):
        self.assertEqual(asr_server._extract_text({}), "")
        self.assertEqual(asr_server._extract_text({"choices": []}), "")


class MimoGateTest(unittest.TestCase):

    def test_无key不出网直接返回none(self):
        old = asr_server.MIMO_API_KEY
        try:
            asr_server.MIMO_API_KEY = ""
            self.assertIsNone(asr_server.mimo_recognize(b"RIFFxxxx"))
        finally:
            asr_server.MIMO_API_KEY = old


class BuildMessagesTest(unittest.TestCase):
    """2026-09-10 准确率优化：领域提示 + 音频的 messages 构造。"""

    def test_结构_system提示在前_音频在后(self):
        wav = asr_server.wrap_pcm_to_wav(b"\x01\x00\x02\x00")
        msgs = asr_server.build_mimo_messages(wav, "zh")
        self.assertEqual(len(msgs), 2)
        self.assertEqual(msgs[0]["role"], "system")
        self.assertIn("五金店", msgs[0]["content"])
        self.assertIn("M8", msgs[0]["content"])          # 型号原样保留的规则在提示里
        self.assertEqual(msgs[1]["role"], "user")
        audio = msgs[1]["content"][0]["input_audio"]["data"]
        self.assertTrue(audio.startswith("data:audio/wav;base64,"))

    def test_语言参数不进messages_走extra_body(self):
        # language 由调用方的 extra_body 透传（保持与 MiMo asr_options 对接），messages 不含
        wav = asr_server.wrap_pcm_to_wav(b"\x01\x00")
        msgs = asr_server.build_mimo_messages(wav, "en")
        self.assertNotIn("language", json.dumps(msgs, ensure_ascii=False))


class LoadHotwordsTest(unittest.TestCase):
    """全量热词表加载：文件读取拼 prompt、缺失回退内置。"""

    def test_文件存在_读取正文(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False,
                                         encoding="utf-8") as f:
            f.write("# 注释行\n螺栓 螺母 合页\n")
            path = f.name
        try:
            words = asr_server.load_hotwords(path)
            self.assertNotIn("#", words)
            self.assertIn("合页", words)
            # domain_prompt = 固定规则 + 词表
            self.assertIn("转写引擎", asr_server.domain_prompt())
        finally:
            os.unlink(path)

    def test_文件缺失_回退内置简版(self):
        words = asr_server.load_hotwords("Z:/no/such/file.txt")
        self.assertIn("角磨机", words)  # ASR_FALLBACK_WORDS 内置词
        self.assertEqual(words, asr_server.ASR_FALLBACK_WORDS)


class CleanForSearchTest(unittest.TestCase):
    """2026-09-11 老板反馈「识别后都带句号」：搜索友好清洗。"""

    def test_尾部句号删除(self):
        self.assertEqual(asr_server._clean_for_search("膨胀螺栓。"), "膨胀螺栓")
        self.assertEqual(asr_server._clean_for_search("自攻丝..."), "自攻丝")
        self.assertEqual(asr_server._clean_for_search("水管接头！"), "水管接头")

    def test_数字间句号保留为小数点(self):
        self.assertEqual(asr_server._clean_for_search("套筒12。5mm。"), "套筒12.5mm")
        self.assertEqual(asr_server._clean_for_search("3.5寸。"), "3.5寸")

    def test_中间标点变词边界_空白收紧(self):
        self.assertEqual(asr_server._clean_for_search("电钻，冲击钻。"), "电钻 冲击钻")
        self.assertEqual(asr_server._clean_for_search("Monday。Today 是礼拜二。"), "Monday Today 是礼拜二")

    def test_括号引号等非句读保留(self):
        self.assertEqual(asr_server._clean_for_search("(12角)套筒"), "(12角)套筒")


class ZhNumeralsTest(unittest.TestCase):
    """2026-09-29 老板反馈「语音数字识别出来都是中文大写不利于条码搜索」：
    中文数字串 → 阿拉伯数字（条码/货号可搜），词守卫防「五金→5金」类误伤。"""

    def test_条码逐位读法_中文小写含点(self):
        self.assertEqual(
            asr_server.zh_numerals_to_digits("零零一点九九点幺九五七"),
            "001.99.1957")

    def test_条码大写数字串(self):
        self.assertEqual(asr_server.zh_numerals_to_digits("壹玖玖壹玖伍柒"), "1991957")
        self.assertEqual(asr_server.zh_numerals_to_digits("洞洞壹贰叁"), "00123")

    def test_混排阿拉伯与单字幺(self):
        self.assertEqual(asr_server.zh_numerals_to_digits("199幺957"), "1991957")

    def test_带点规格转小数(self):
        self.assertEqual(asr_server.zh_numerals_to_digits("二点五平方"), "2.5平方")
        self.assertEqual(asr_server.zh_numerals_to_digits("三点五"), "3.5")

    def test_进位读法按数值转(self):
        self.assertEqual(asr_server.zh_numerals_to_digits("二十五毫米"), "25毫米")
        self.assertEqual(asr_server.zh_numerals_to_digits("三百"), "300")
        self.assertEqual(asr_server.zh_numerals_to_digits("壹佰贰拾叁"), "123")
        self.assertEqual(asr_server.zh_numerals_to_digits("三万五千"), "35000")

    def test_歧义读法不转(self):
        for w in ("四十五六", "三万五", "一百五", "十点五", "三点"):
            self.assertEqual(asr_server.zh_numerals_to_digits(w), w, w)

    def test_店名词汇防误伤(self):
        for w in ("五金", "三通", "万用表", "百叶窗", "两用扳手",
                  "千斤顶", "不三不四", "墙洞", "五金工具三通接头"):
            self.assertEqual(asr_server.zh_numerals_to_digits(w), w, w)

    def test_前邻阿拉伯数字的进位字不转(self):
        self.assertEqual(asr_server.zh_numerals_to_digits("3千瓦"), "3千瓦")

    def test_单字数字保守不转_幺〇例外(self):
        self.assertEqual(asr_server.zh_numerals_to_digits("五号"), "五号")
        self.assertEqual(asr_server.zh_numerals_to_digits("199幺957"), "1991957")

    def test_并入搜索清洗链路(self):
        self.assertEqual(
            asr_server._clean_for_search("零零一点九九点幺九五七。"),
            "001.99.1957")
        self.assertEqual(asr_server._clean_for_search("001点99。"), "001.99")
        # 回归：既有清洗行为不变
        self.assertEqual(asr_server._clean_for_search("膨胀螺栓。"), "膨胀螺栓")
        self.assertEqual(asr_server._clean_for_search("套筒12。5mm。"), "套筒12.5mm")

    def test_提示词含报数转阿拉伯数字规则(self):
        self.assertIn("阿拉伯数字", asr_server.ASR_PROMPT_RULES)
        self.assertIn("001.99.1957", asr_server.ASR_PROMPT_RULES)


if __name__ == "__main__":
    unittest.main()
