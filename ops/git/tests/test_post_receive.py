"""使用本地 HTTP 接收端验证 hook 原始签名及分支筛选，不访问外部服务。"""

import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import threading
import unittest

HOOK = Path(__file__).resolve().parents[1] / "post-receive"
LOADER = importlib.machinery.SourceFileLoader("post_receive", str(HOOK))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
MODULE = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(MODULE)


class HookTest(unittest.TestCase):
    def test_main_delivers_verifiable_raw_body(self):
        received = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.path, self.headers["X-Git-Signature"],
                                 self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(202)
                self.end_headers()

            def log_message(self, *_):
                pass

        with HTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = threading.Thread(target=server.handle_request, daemon=True)
            worker.start()
            MODULE.notify(["a b refs/heads/other", "c d refs/heads/main"], {
                "METRIC_GIT_REPOSITORY_ID": "metrics",
                "METRIC_GIT_WEBHOOK_SECRET": "test-only-secret",
                "METRIC_GIT_WEBHOOK_URL": f"http://127.0.0.1:{server.server_port}/internal/git-sync/events",
            })
            worker.join(timeout=5)
        self.assertEqual(len(received), 1)
        path, signature, body = received[0]
        self.assertEqual(path, "/internal/git-sync/events")
        self.assertEqual(signature, "sha256=" + hmac.new(b"test-only-secret", body, hashlib.sha256).hexdigest())
        self.assertEqual(json.loads(body)["after"], "d")
        self.assertEqual(json.loads(body)["repositoryId"], "metrics")

    def test_other_branch_needs_no_configuration(self):
        MODULE.notify(["a b refs/heads/feature"], {})


if __name__ == "__main__":
    unittest.main()
