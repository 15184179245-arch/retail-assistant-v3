import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.normpath(os.path.join(os.path.dirname(__file__), '..')))
from gateway.handler import GatewayHandler, get_latest_apk_info, render_download_page

class DummyGatewayHandler(GatewayHandler):
    def __init__(self, path, method='GET', body=None):
        self.command = method
        self.path = path
        self.headers = {}
        if body:
            raw = json.dumps(body).encode('utf-8')
            self.headers['Content-Length'] = str(len(raw))
            self.headers['Content-Type'] = 'application/json'
            self.rfile = io.BytesIO(raw)
        else:
            self.rfile = io.BytesIO()
        self.wfile = io.BytesIO()
        self.sent_status = 200
        self.sent_headers = {}

    def send_response(self, code, message=None):
        self.sent_status = code

    def send_header(self, keyword, value):
        self.sent_headers[keyword.lower()] = value

    def end_headers(self):
        pass

class TestAppVersion(unittest.TestCase):
    def test_get_latest_apk_info_structure(self):
        info = get_latest_apk_info()
        self.assertIn('versionCode', info)
        self.assertIn('version_code', info)
        self.assertIn('versionName', info)
        self.assertIn('version_name', info)
        self.assertIn('downloadUrl', info)
        self.assertIn('download_url', info)
        self.assertIn('apkSize', info)
        self.assertIn('apk_size', info)
        self.assertIn('updateLog', info)
        self.assertIn('title', info)
        self.assertGreaterEqual(info['versionCode'], 1)
        self.assertTrue(info['downloadUrl'].endswith('.apk'))
        # 结构性校验：updateLog 非空即可，不锁定文案（文案随每次发版正常变更）
        self.assertTrue(str(info['updateLog']).strip(), 'updateLog 不应为空')

    def test_api_app_version_get(self):
        handler = DummyGatewayHandler('/api/app/version', 'GET')
        handler.do_GET()
        self.assertEqual(handler.sent_status, 200)
        resp = json.loads(handler.wfile.getvalue().decode('utf-8'))
        self.assertTrue(resp.get('success'))
        self.assertIn('versionCode', resp.get('data', {}))

    def test_download_page_renders(self):
        # 手机浏览器下载页：卸载后/装不上时自助重装入口（2026-09-10 推送更新失效修复）
        html = render_download_page()
        self.assertIn('DOCTYPE', html[:40].upper())
        self.assertIn('/retaildemo-v2.apk', html)          # 下载按钮链到 APK 直链
        self.assertIn('下载安装', html)
        self.assertIn('店员助手', html)

    def test_api_download_route(self):
        handler = DummyGatewayHandler('/download', 'GET')
        handler.do_GET()
        self.assertEqual(handler.sent_status, 200)
        body = handler.wfile.getvalue().decode('utf-8')
        self.assertIn('/retaildemo-v2.apk', body)

    def test_api_admin_app_push_update(self):
        body = {
            'version_code': 99,
            'version_name': '9.9.0',
            'title': '测试全员推送',
            'update_log': '新版本功能测试'
        }
        data_dir = os.environ.get('DATA_DIR') or os.path.normpath(os.path.join(os.path.dirname(__file__), '..', '..', 'data'))
        ver_file = os.path.join(data_dir, 'app_version.json')
        backup_content = None
        if os.path.exists(ver_file):
            try:
                with open(ver_file, 'r', encoding='utf-8') as f:
                    backup_content = f.read()
            except Exception: pass

        try:
            handler = DummyGatewayHandler('/api/admin/app/push_update', 'POST', body)
            handler.do_POST()
            self.assertEqual(handler.sent_status, 200)
            resp = json.loads(handler.wfile.getvalue().decode('utf-8'))
            self.assertTrue(resp.get('success'))
            data = resp.get('data', {})
            self.assertEqual(data.get('versionCode'), 99)
            self.assertEqual(data.get('versionName'), '9.9.0')
            self.assertEqual(data.get('title'), '测试全员推送')
        finally:
            if backup_content is not None:
                with open(ver_file, 'w', encoding='utf-8') as f:
                    f.write(backup_content)
            elif os.path.exists(ver_file):
                try: os.remove(ver_file)
                except Exception: pass

if __name__ == '__main__':
    unittest.main()
