# -*- coding: utf-8 -*-
"""src/agent/test_a2ui.py — 生成式 UI 模板层单测（纯离线，不连服务、不碰库）。

重点锁四件事：数据只来自 ops 的返回值、报文不带样式、catalog 白名单、行数上限。
"""
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from agent import a2ui  # noqa: E402


def _rows(n=8):
    return [{"name": "单芯铜塑线%d" % i, "spec": "BV10", "stock_qty": 0.01 * i,
             "unit": "捆", "sell_price": 1100.0, "barcode": "100501002%d" % i,
             "cost_price": 583.0, "images": "/x/1.jpg", "location": "A1-2-3" if i % 2 else ""}
            for i in range(n)]


class LowStockCard(unittest.TestCase):

    def setUp(self):
        self.msgs = a2ui.card_for("low_stock", {"data": _rows()})

    def test_消息顺序与种类(self):
        kinds = [next(k for k in m if k != "version") for m in self.msgs]
        self.assertEqual(kinds, ["createSurface", "updateDataModel", "updateDataModel",
                                 "updateDataModel", "updateComponents"])

    def test_行数上限与低库存标题精简(self):
        comps = self.msgs[-1]["updateComponents"]["components"]
        tbl = [c for c in comps if c["component"] == "Table"][0]
        self.assertEqual(tbl["rows"], {"path": "/rows"})  # 行是绑定，不内联数据
        data = self.msgs[3]["updateDataModel"]
        self.assertEqual(data["path"], "/rows")
        self.assertEqual(len(data["value"]), a2ui.MAX_ROWS)
        self.assertEqual("库存预警", self.msgs[1]["updateDataModel"]["value"])
        self.assertEqual(8, self.msgs[2]["updateDataModel"]["value"]["count"])

    def test_低库存卡固定四列给售价留足空间(self):
        comps = self.msgs[-1]["updateComponents"]["components"]
        cols = [c for c in comps if c["component"] == "Table"][0]["columns"]
        self.assertEqual(["name", "spec", "stock_qty", "sell_price"], [c["key"] for c in cols])

    def test_报文不带样式键(self):
        blob = a2ui.json.dumps(self.msgs, ensure_ascii=False)
        for k in ("color", "fontSize", "lineHeight", "padding", "borderRadius",
                  "background", "#FF", "#ff"):
            self.assertNotIn(k, blob)

    def test_组件全在catalog内(self):
        comps = self.msgs[-1]["updateComponents"]["components"]
        names = {c["component"] for c in comps}
        self.assertTrue(names <= {"Card", "Column", "Row", "Text", "StatGrid",
                                  "Table", "NavCard"}, names)

    def test_动作只有导航没有写语义(self):
        blob = a2ui.json.dumps(self.msgs, ensure_ascii=False)
        self.assertNotIn("confirm", blob)
        self.assertNotIn("exec", blob)
        comps = self.msgs[-1]["updateComponents"]["components"]
        nav = [c for c in comps if c["component"] == "NavCard"][0]
        for a in nav["actions"]:
            self.assertEqual(a["event"]["name"], "navigate")

    def test_只带模板要用的字段(self):
        row = self.msgs[3]["updateDataModel"]["value"][0]
        self.assertNotIn("cost_price", row)   # 成本价不下发手机
        self.assertNotIn("images", row)
        self.assertIn("barcode", row)         # 行点击要用它定位商品

    def test_无条码行回落编号仍可点进详情(self):
        # 2026-09-20 实机发现：低库存 50 行里 17 行没条码，旧的"全有码才挂 rowAction"
        # 让整张卡失去行点击（连 hint 都没了）。脏数据只容错不改（铁律 12）。
        rows = _rows(3)
        rows[1]["barcode"] = ""
        rows[1]["sku"] = "100.01.00.0632"
        msgs = a2ui.card_for("low_stock", {"data": rows, "total": 5241})
        comps = msgs[-1]["updateComponents"]["components"]
        tbl = [c for c in comps if c["component"] == "Table"][0]
        self.assertEqual("product", tbl["rowAction"]["event"]["context"]["route"])
        self.assertIn("hint", [c for c in comps if c["id"] == "col"][0]["children"])
        payload = [m for m in msgs if "updateDataModel" in m
                   and m["updateDataModel"]["path"] == "/rows"][0]["updateDataModel"]["value"]
        self.assertEqual("1005010020", payload[0]["barcode"])       # 有条码用条码
        self.assertEqual("100.01.00.0632", payload[1]["barcode"])   # 无条码回落上游编号

    def test_脏数据不崩(self):
        rows = _rows(2)
        rows[0]["stock_qty"] = None
        rows[1]["sell_price"] = "abc"
        rows[0].pop("spec", None)
        msgs = a2ui.card_for("low_stock", {"data": rows})
        self.assertTrue(msgs)

    def test_总数必须来自接口total_不许拿分页行数冒充(self):
        # 2026-09-19 事故回归：low_stock 返回 total=5241 而 data 只有 50 行，
        # 早先卡片用 len(rows) 当总数，对店员报出「共 50 条」的假数。
        rows = _rows(50)
        msgs = a2ui.card_for("low_stock", {"data": rows, "total": 5241})
        title = [m for m in msgs if "updateDataModel" in m
                 and m["updateDataModel"]["path"] == "/title"][0]["updateDataModel"]["value"]
        self.assertEqual("库存预警", title)
        stat = [m for m in msgs if "updateDataModel" in m
                and m["updateDataModel"]["path"] == "/stat"][0]["updateDataModel"]["value"]
        self.assertEqual(5241, stat["count"])

    def test_StatGrid绑定路径必须是字符串(self):
        comps = self.msgs[-1]["updateComponents"]["components"]
        stats = [c for c in comps if c["component"] == "StatGrid"][0]["items"]
        self.assertTrue(all(isinstance(item["value"].get("path"), str) for item in stats))

    def test_KPI标签必须标明计算范围(self):
        # 只能在已加载的行上算的指标，标签要写清「本页」，不许冒充全局
        blob = a2ui.json.dumps(a2ui.card_for("low_stock", {"data": _rows(9), "total": 5241}),
                               ensure_ascii=False)
        self.assertIn("本页未定价", blob)
        self.assertIn("本页未绑货位", blob)

    def test_空数据不画卡(self):
        self.assertIsNone(a2ui.card_for("low_stock", {"data": []}))
        self.assertIsNone(a2ui.card_for("low_stock", None))

    def test_未登记形状的op不出卡(self):
        self.assertIsNone(a2ui.card_for("some_unknown_op", None))

    def test_通用构建器接管未写模板的op(self):
        # 步骤②：debts 没有 bespoke 模板，也照样出卡（列宽按内容测、金额右对齐）
        msgs = a2ui.card_for("debts", {"top": [
            {"name": "张三", "amount": 1280.5, "barcode": "B1"},
            {"name": "李四", "amount": 88.0, "barcode": "B2"}]})
        self.assertIsNotNone(msgs)
        comps = msgs[-1]["updateComponents"]["components"]
        kids = [c for c in comps if c["id"] == "col"][0]["children"]
        self.assertIn("hint", kids)
        self.assertLess(kids.index("tbl"), kids.index("hint"))  # 提示在表格之后
        cols = [c for c in comps if c["component"] == "Table"][0]["columns"]
        self.assertEqual("end", [c for c in cols if c["key"] == "amount"][0]["align"])
        self.assertEqual("money", [c for c in cols if c["key"] == "amount"][0]["format"])


class Guard(unittest.TestCase):

    def _msgs_with(self, comp):
        return [{"updateComponents": {"components": [comp]}}]

    def test_catalog外组件被拒(self):
        with self.assertRaises(ValueError):
            a2ui._guard(self._msgs_with({"id": "x", "component": "Iframe"}))

    def test_夹带样式被拒(self):
        with self.assertRaises(ValueError):
            a2ui._guard(self._msgs_with({"id": "x", "component": "Text", "color": "#F00"}))

    def test_写语义被拒(self):
        with self.assertRaises(ValueError):
            a2ui._guard(self._msgs_with({"id": "x", "component": "NavCard",
                                         "actions": [{"event": {"name": "confirm"}}]}))


class Frames(unittest.TestCase):

    def test_serve工具名映射到op(self):
        # 映射层：认得出已登记的 op（含 hermes 加的命名空间前缀）
        self.assertEqual(a2ui.op_for_tool("sx_low_stock"), "low_stock")
        self.assertEqual(a2ui.op_for_tool("mcp__retaildemo__sx_low_stock"), "low_stock")
        self.assertEqual(a2ui.op_for_tool("sx_search_low_stock"), "low_stock")
        self.assertEqual(a2ui.op_for_tool("sx_search"), "search")
        self.assertIsNone(a2ui.op_for_tool("sx_todo_create"))  # 无此 op（只有 todos）
        self.assertIsNone(a2ui.op_for_tool("execute_code"))
        self.assertIsNone(a2ui.op_for_tool(""))

    def test_需要参数的op不许被自动触发(self):
        # 触发层：认得出 ≠ 可以空参重跑。search 没有查询词会拿回垃圾结果再画成卡。
        self.assertIn("low_stock", a2ui.SAFE_OPS)
        for op in ("search", "quick_pricing", "knowledge_search", "identify", "reco"):
            self.assertNotIn(op, a2ui.SAFE_OPS, op)
        self.assertEqual(a2ui.frames_for_ops(["search"], lambda o, a: {"ok": True, "data": {}}), [])

    def test_认不出的工具名不许调op(self):
        calls = []

        def fake_run(op, args):
            calls.append(op)
            return {"ok": True, "data": {"data": _rows()}}

        frames = a2ui.frames_for_tools(["sx_search", "read_file", "sx_todo_create"], fake_run)
        self.assertEqual((calls, frames), ([], []))

    def test_带命名空间的工具名也能出帧(self):
        def fake_run(op, args):
            self.assertEqual(op, "low_stock")
            return {"ok": True, "data": {"data": _rows()}}

        frames = a2ui.frames_for_tools(["sx_search_low_stock"], fake_run)
        self.assertEqual([f["type"] for f in frames], ["ui"])

    def test_op成功则出ui帧(self):
        def fake_run(op, args):
            return {"ok": True, "data": {"data": _rows()}}

        frames = a2ui.frames_for_ops(["low_stock"], fake_run)
        self.assertEqual([f["type"] for f in frames], ["ui"])

    def test_op失败或无数据就不发帧(self):
        self.assertEqual(a2ui.frames_for_ops(["low_stock"], lambda o, a: {"ok": False}), [])
        self.assertEqual(
            a2ui.frames_for_ops(["low_stock"], lambda o, a: {"ok": True, "data": {"data": []}}),
            [])

    def test_取数抛异常不许把流打死(self):
        """卡片是增强、回答是主路：这里任何异常都只能吞成"少一张卡"。"""
        def boom(op, args):
            raise RuntimeError("db locked")

        self.assertEqual(a2ui.frames_for_ops(["low_stock"], boom), [])

    def test_同op去重且每轮有上限(self):
        def fake_run(op, args):
            return {"ok": True, "data": {"data": _rows()}}

        frames = a2ui.frames_for_ops(["low_stock", "low_stock"], fake_run)
        self.assertEqual(len(frames), 1)
        self.assertLessEqual(a2ui.MAX_CARDS_PER_TURN, 2)

    def test_帧可json序列化(self):
        def fake_run(op, args):
            return {"ok": True, "data": {"data": _rows()}}
        for f in a2ui.frames_for_ops(["low_stock"], fake_run):
            a2ui.json.dumps(f, ensure_ascii=False)



class CatalogGuard(unittest.TestCase):
    """闸门现在照 a2ui_catalog.json 校验：结构、枚举、路由、上限、禁样式。"""

    def _c(self, comp):
        return a2ui._guard([{"updateComponents": {"components": [comp]}}])

    def test_未知组件被拒(self):
        with self.assertRaises(ValueError):
            self._c({"id": "x", "component": "Iframe", "child": "y"})

    def test_未知字段被拒(self):
        with self.assertRaises(ValueError):
            self._c({"id": "x", "component": "Text", "text": "hi", "color": "#F00"})

    def test_缺必填被拒(self):
        with self.assertRaises(ValueError):
            self._c({"id": "x", "component": "Text"})

    def test_枚举越界被拒(self):
        with self.assertRaises(ValueError):
            self._c({"id": "x", "component": "Text", "text": "hi", "variant": "h9"})

    def test_未登记路由被拒(self):
        with self.assertRaises(ValueError):
            a2ui._check_action({"name": "navigate", "context": {"route": "settings"}}, "nav")

    def test_写语义动作被拒(self):
        with self.assertRaises(ValueError):
            a2ui._check_action({"name": "confirm", "context": {"route": "low_stock"}}, "nav")

    def test_路由缺参数被拒(self):
        with self.assertRaises(ValueError):
            a2ui._check_action({"name": "navigate", "context": {"route": "product"}}, "tbl")

    def test_路由带参数放行(self):
        a2ui._check_action({"name": "navigate",
                           "context": {"route": "product", "barcode": {"path": "barcode"}}}, "tbl")

    def test_Row超上限被拒(self):
        with self.assertRaises(ValueError):
            self._c({"id": "r", "component": "Row", "children": ["a", "b", "c", "d"]})

    def test_Column纵向堆叠不受Row上限约束(self):
        self._c({"id": "c", "component": "Column", "children": ["a", "b", "c", "d", "e"]})


class CrossLanguageParity(unittest.TestCase):
    """单一真源不许漂移：Python catalog 的组件名与行数上限，必须等于 APP 渲染层里写死的那份。"""

    KT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "..", "android-v2", "app", "src", "main", "java",
                      "com", "retaildemo", "v2", "ui", "agent", "a2ui", "A2uiParser.kt")

    def test_组件白名单两侧一致(self):
        import re
        kt = io.open(self.KT, encoding="utf-8").read()
        m = re.search(r"CATALOG\s*=\s*setOf\(([^)]*)\)", kt)
        self.assertIsNotNone(m, "A2uiParser.kt 里找不到 CATALOG 定义，检查是否改名")
        kotlin = set(re.findall(r'"([A-Za-z]+)"', m.group(1)))
        self.assertEqual(set(a2ui.CATALOG["components"]), kotlin,
                         "catalog JSON 与 Kotlin 渲染层白名单不一致")

    def test_行数上限两侧一致(self):
        import re
        kt = io.open(self.KT, encoding="utf-8").read()
        kt_parser_rows = re.search(r"MAX_ROWS\s*=\s*(\d+)", kt)
        render = os.path.join(os.path.dirname(self.KT), "A2uiRender.kt")
        rt = io.open(render, encoding="utf-8").read()
        kt_render_rows = re.search(r"MAX_ROWS\s*=\s*(\d+)", rt)
        self.assertIsNotNone(kt_parser_rows or kt_render_rows, "Kotlin 侧找不到 MAX_ROWS")
        found = int((kt_parser_rows or kt_render_rows).group(1))
        self.assertEqual(a2ui.MAX_ROWS, found)



if __name__ == "__main__":
    unittest.main()
