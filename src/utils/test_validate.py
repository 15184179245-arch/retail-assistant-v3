#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""排雷 N2（2026-09-09）：validate_fields 的 type:dict 此前落进兜底 str(raw)
分支被字符串化，/print/test 拿到字符串 .get() 直接 AttributeError（测试打印全坏）。
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from utils.validate import parse_json_body, validate_request, validate_fields  # noqa: E402


class ValidateDictFieldTest(unittest.TestCase):

    def test_dict_field_keeps_dict(self):
        cleaned, err = validate_fields(
            {"printer": {"ip": "192.0.2.10", "port": 9100}},
            {"printer": {"required": True, "type": dict, "message": "请提供打印机信息"}},
        )
        self.assertIsNone(err)
        self.assertIsInstance(cleaned["printer"], dict)
        self.assertEqual(cleaned["printer"]["ip"], "192.0.2.10")

    def test_dict_field_rejects_non_dict(self):
        cleaned, err = validate_fields(
            {"printer": "not-a-dict"},
            {"printer": {"required": True, "type": dict, "message": "请提供打印机信息"}},
        )
        self.assertIsNone(cleaned)
        self.assertFalse(err["success"])

    def test_full_request_shape_like_print_test(self):
        body = b'{"printer":{"type":"network","ip":"8.8.8.8","port":9100}}'
        cleaned, err = validate_request(body, {"printer": {"required": True, "type": dict, "message": "x"}})
        self.assertIsNone(err)
        self.assertEqual(cleaned["printer"]["ip"], "8.8.8.8")
        self.assertEqual(cleaned["printer"]["port"], 9100)

    def test_parse_json_body_rejects_non_object(self):
        data, err = parse_json_body(b'["a","b"]')
        self.assertIsNone(data)
        self.assertFalse(err["success"])


class ValidateBasicsTest(unittest.TestCase):

    def test_int_float_bool_coercion(self):
        cleaned, err = validate_fields(
            {"qty": "3", "price": "1.5", "force": "yes", "note": " ok "},
            {"qty": {"type": int}, "price": {"type": float}, "force": {"type": bool}, "note": {}},
        )
        self.assertIsNone(err)
        self.assertEqual(cleaned, {"qty": 3, "price": 1.5, "force": True, "note": "ok"})

    def test_list_wraps_scalar(self):
        cleaned, err = validate_fields({"items": "a"}, {"items": {"type": list}})
        self.assertIsNone(err)
        self.assertEqual(cleaned["items"], ["a"])

    def test_missing_required_reports_user_message(self):
        cleaned, err = validate_fields({}, {"name": {"required": True, "message": "请填写名称"}})
        self.assertIsNone(cleaned)
        self.assertIn("名称", err["user_message"])


if __name__ == "__main__":
    unittest.main()
