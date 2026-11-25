#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_verify_pipeline.py - 门禁流水线聚合调度器 (verify_pipeline.py) 单元测试套件。

覆盖范围：
1. 子命令执行与超时捕获 (run_command)
2. Gate 1 静态规格与令牌审查流转 (run_gate1)
3. Gate 2 代码规范与防御单测流转 (run_gate2)
4. Gate 3 真机 Bounds 与 SSIM 像素比对流转 (run_gate3)
5. 聚合验收报告生成与 JSON 格式校验 (generate_summary_report)
6. 对账清单状态回写与一致性保护 (update_landing_manifest)
7. 命令行参数驱动与 Unix 退出码判定 (main CLI)
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import verify_pipeline


class TestVerifyPipeline(unittest.TestCase):
    """测试 verify_pipeline.py 各模块核心函数与流转逻辑。"""

    def setUp(self):
        self.test_dir = tempfile.TemporaryDirectory()
        self.temp_path = Path(self.test_dir.name)

    def tearDown(self):
        self.test_dir.cleanup()

    def test_run_command_success(self):
        """测试基础命令执行与正常返回码。"""
        cmd = [sys.executable, "-c", "print('hello pipeline')"]
        code, stdout, stderr, elapsed = verify_pipeline.run_command(cmd)
        self.assertEqual(code, 0)
        self.assertIn("hello pipeline", stdout)
        self.assertGreaterEqual(elapsed, 0.0)

    def test_run_command_failure(self):
        """测试异常命令执行退出码与错误流捕获。"""
        cmd = [sys.executable, "-c", "import sys; sys.stderr.write('error msg'); sys.exit(1)"]
        code, stdout, stderr, elapsed = verify_pipeline.run_command(cmd)
        self.assertEqual(code, 1)
        self.assertIn("error msg", stderr)

    def test_run_gate1_passes(self):
        """验证 Gate 1 执行正常且结构完备。"""
        res = verify_pipeline.run_gate1(root_dir=str(ROOT), full_scan=False)
        self.assertIn("gate_name", res)
        self.assertIn("status", res)
        self.assertIn("checks", res)
        self.assertTrue(res["is_passed"], f"Gate 1 should pass. Violations: {res['violations']}")
        self.assertEqual(res["status"], "PASS")
        self.assertGreaterEqual(len(res["checks"]), 2)

    def test_run_gate2_passes_skip_android(self):
        """验证 Gate 2 在 skip_android 模式下正常通过。"""
        if os.environ.get("VERIFY_PIPELINE_CHILD") == "1":
            # 内部递归子进程中跳过此单测，防止无限递归
            return
        res = verify_pipeline.run_gate2(root_dir=str(ROOT), skip_android=True)
        self.assertIn("gate_name", res)
        self.assertIn("status", res)
        self.assertTrue(res["is_passed"], f"Gate 2 should pass. Violations: {res['violations']}")
        self.assertEqual(res["status"], "PASS")

    def test_run_gate3_passes(self):
        """验证 Gate 3 离线热区与 SSIM 结构比对通过。"""
        res = verify_pipeline.run_gate3(root_dir=str(ROOT), skip_device=True)
        self.assertIn("gate_name", res)
        self.assertIn("status", res)
        self.assertTrue(res["is_passed"], f"Gate 3 should pass. Violations: {res['violations']}")
        self.assertEqual(res["status"], "PASS")

    def test_generate_summary_report(self):
        """验证汇总报告 JSON 生成与结构契约。"""
        dummy_results = {
            "gate1": {
                "gate_name": "Gate 1",
                "status": "PASS",
                "is_passed": True,
                "checks": [{"name": "c1", "is_passed": True, "elapsed_sec": 0.1}],
                "violations": []
            },
            "gate2": {
                "gate_name": "Gate 2",
                "status": "FAIL",
                "is_passed": False,
                "checks": [{"name": "c2", "is_passed": False, "elapsed_sec": 0.2}],
                "violations": [{"gate": "Gate 2", "rule": "TEST_FAIL", "message": "fail msg"}]
            }
        }
        report_file = self.temp_path / "summary.json"
        rep = verify_pipeline.generate_summary_report(dummy_results, str(report_file))

        self.assertTrue(report_file.exists())
        self.assertFalse(rep["is_passed"])
        self.assertEqual(rep["total_violations"], 1)
        self.assertEqual(rep["summary"]["gate1"], "PASS")
        self.assertEqual(rep["summary"]["gate2"], "FAIL")

        # 检查持久化的 JSON 内容有效性
        loaded = json.loads(report_file.read_text(encoding="utf-8"))
        self.assertEqual(loaded["version"], "1.0.0")
        self.assertIn("timestamp", loaded)

    def test_update_landing_manifest_pass_and_fail(self):
        """测试对账清单回写逻辑：成功分支与失败阻断分支。"""
        dummy_manifest = self.temp_path / "landing-manifest.md"
        dummy_manifest.write_text(
            "# 对账表\n> **更新日期**：2026-09-01 · 原始基准\n\n| 页面 | 状态 |\n| --- | --- |\n",
            encoding="utf-8"
        )

        pass_results = {
            "gate1": {"is_passed": True},
            "gate2": {"is_passed": True},
            "gate3": {"is_passed": True}
        }
        fail_results = {
            "gate1": {"is_passed": True},
            "gate2": {"is_passed": False}
        }

        # 失败阻断测试：存在未通过门禁时禁止回写
        ok_fail, msg_fail = verify_pipeline.update_landing_manifest(str(dummy_manifest), fail_results)
        self.assertFalse(ok_fail)
        self.assertIn("not all gates passed", msg_fail)

        # 成功回写测试
        ok_pass, msg_pass = verify_pipeline.update_landing_manifest(str(dummy_manifest), pass_results)
        self.assertTrue(ok_pass)
        updated_text = dummy_manifest.read_text(encoding="utf-8")
        self.assertIn("全链路自动化门禁聚合验证通过", updated_text)

    def test_cli_gate1_isolated(self):
        """测试 CLI 仅运行 Gate 1 参数。"""
        report_file = self.temp_path / "cli_rep.json"
        exit_code = verify_pipeline.main([
            "--gate1",
            "--report", str(report_file)
        ])
        self.assertEqual(exit_code, 0)
        self.assertTrue(report_file.exists())
        data = json.loads(report_file.read_text(encoding="utf-8"))
        self.assertIn("gate1", data["summary"])
        self.assertNotIn("gate2", data["summary"])


if __name__ == "__main__":
    unittest.main()
