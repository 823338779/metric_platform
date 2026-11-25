"""通过真实 stdio 子进程检查客户端契约，业务端使用按请求顺序校验的平台 HTTP 夹具。"""

import json
import os
import subprocess
import sys
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.parse import parse_qsl, urlsplit

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

BUILD = "00000000-0000-0000-0000-000000000001"
ENVIRONMENT = "00000000-0000-0000-0000-000000000002"
SECOND_BUILD = "00000000-0000-0000-0000-000000000003"
KEY = "00000000-0000-0000-0000-000000000004"
REPOSITORY = "https://example.invalid/demo"
TOKEN = "fixture-only"
METRIC_ID = "metric.package.revenue"
OTHER_ID = "metric.package.cost"
BUILD_PATH = f"/api/dbt/builds/{BUILD}"
SEARCH = BUILD_PATH + "/catalog/search"
CURRENT = "/api/dbt/deployments/current"
TARGET = {"repository": REPOSITORY, "environmentId": ENVIRONMENT, "branchName": "main"}
TARGET_ARGS = {"repository": REPOSITORY, "environment_id": ENVIRONMENT, "branch_name": "main"}
SUBMIT_ARGS = {**TARGET_ARGS, "commit_sha": "a" * 40, "deployment_policy": "ON_SUCCESS",
               "idempotency_key": KEY, "expected_config_version": 2}
BUILD_VIEW = {
    "buildId": BUILD, "repository": REPOSITORY, "branchName": "main", "requestedCommitSha": "a" * 40,
    "commitSha": "a" * 40, "toolchainVersion": "fixture", "buildStatus": "SUCCEEDED", "phase": "COMPLETE",
    "createdAt": "2024-01-01T00:00:00Z", "updatedAt": "2024-01-02T00:00:00Z", "catalogAvailable": True,
    "initialDeployment": {**TARGET, "buildId": BUILD, "deploymentStatus": "FAILED", "generation": 1,
                          "createdAt": "2024-01-01T00:00:00Z"},
    "execution": {"environmentId": ENVIRONMENT, "connection": {"type": "duckdb", "instanceId": "local",
                  "path": "/tmp/fixture.duckdb"}, "credentialRef": "fixture", "catalog": "warehouse",
                  "schema": "analytics", "businessTimezone": "Asia/Shanghai", "threads": 1},
}


@pytest.fixture
def platform():
    # 每一项都必须匹配授权头、固定路径、参数、请求体和顺序，提前请求全量目录也会失败。
    pending = deque()
    failures = []

    class Handler(BaseHTTPRequestHandler):
        def handle_request(self):
            url = urlsplit(self.path)
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            observed = (self.command, url.path, dict(parse_qsl(url.query)), json.loads(body) if body else None)
            try:
                expected, status, payload = pending.popleft()
                assert self.headers.get("Authorization") == f"Bearer {TOKEN}"
                assert observed == expected
            except (AssertionError, IndexError) as error:
                failures.append((observed, str(error)))
                status, payload = 500, {"title": "unexpected test request"}
            self.send_response(status)
            self.send_header("Content-Type", "application/json" if status < 400 else "application/problem+json")
            self.end_headers()
            self.wfile.write(json.dumps(payload).encode())

        do_GET = do_POST = handle_request

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()

    def expect(method, path, payload, *, params=None, body=None, status=200):
        pending.append(((method, path, params or {}, body), status, payload))

    try:
        yield f"http://127.0.0.1:{server.server_port}", expect
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
        assert not failures, failures
        assert not pending, pending


def summary(uid, name):
    return {"resourceId": uid, "name": name, "resourceType": "metric", "description": "", "queryNameUnique": True}


@pytest.mark.asyncio
async def test_seven_tools_through_platform(platform):
    url, expect = platform
    launch = StdioServerParameters(command=sys.executable, args=["-m", "metric_platform_mcp"],
                                   env={"METRIC_PLATFORM_URL": url, "METRIC_PLATFORM_TOKEN": TOKEN})
    async with stdio_client(launch) as streams, ClientSession(*streams) as session:
        await session.initialize()
        tools = {tool.name: tool for tool in (await session.list_tools()).tools}
        fields = {"get_current_deployment": ("activeBuildId", "undeployed"),
                  "search_catalog": ("resourceId", "nextCursor"), "get_resource": ("resource", "source"),
                  "get_available_dimensions": ("optionId", "granularities", "operators"),
                  "query_metrics": ("columns", "rows", "normalizedTimeRange", "resultTruncated"),
                  "submit_build": ("buildId", "get_build", "BUILD_ACCEPTANCE_UNKNOWN"),
                  "get_build": ("buildStatus", "deploymentStatus", "queryAvailability")}
        assert tools.keys() == fields.keys()
        for name, names in fields.items():
            assert all(field in tools[name].description for field in names)
            assert all(prop.get("description") for prop in tools[name].inputSchema["properties"].values())
        assert tools["submit_build"].inputSchema["properties"]["idempotency_key"]["format"] == "uuid"

        async def call(name, args):
            result = await session.call_tool(name, args)
            assert not result.isError, result.content
            assert json.loads(result.content[0].text) == result.structuredContent
            return result.structuredContent

        # 部署尝试与当前生效指针彼此独立；构建只接受平台环境版本，复用幂等键。
        expect("GET", CURRENT, {**TARGET, "activeBuildId": None, "latestAttempt": BUILD_VIEW["initialDeployment"]},
               params=TARGET)
        assert (await call("get_current_deployment", TARGET_ARGS))["status"] == "undeployed"
        for _ in range(2):
            expect("POST", f"/api/dbt/environments/{ENVIRONMENT}/builds", BUILD_VIEW, status=202, body={
                "repository": REPOSITORY, "branchName": "main", "commitSha": "a" * 40,
                "deploymentPolicy": "ON_SUCCESS", "idempotencyKey": KEY, "expectedConfigVersion": 2})
            assert (await call("submit_build", SUBMIT_ARGS))["buildId"] == BUILD
        expect("GET", BUILD_PATH, BUILD_VIEW)
        details = await call("get_build", {"build_id": BUILD})
        assert details["buildStatus"] == "SUCCEEDED" and details["initialDeployment"]["deploymentStatus"] == "FAILED"
        assert details["queryAvailability"] == "unknown"

        # 搜索走分页，支持大目录；单次分析中的后续调用始终固定在同一构建。
        search = {"keyword": "销售额", "limit": "1", "resourceType": "metric"}
        args = {"build_id": BUILD, "keyword": "销售额", "limit": 1, "resource_type": "metric"}
        expect("GET", SEARCH, {"items": [summary(OTHER_ID, "cost")], "nextCursor": "next"}, params=search)
        page = await call("search_catalog", args)
        expect("GET", SEARCH, {"items": [summary(METRIC_ID, "revenue")]}, params={**search, "cursor": "next"})
        uid = (await call("search_catalog", {**args, "cursor": page["nextCursor"]}))["items"][0]["resourceId"]
        expect("GET", BUILD_PATH + "/resources/" + uid, {"buildId": BUILD, "resourceId": uid,
               "resource": {"name": "revenue"}, "source": None})
        assert (await call("get_resource", {"build_id": BUILD, "resource_id": uid}))["resource"]["name"] == "revenue"

        def exact(resource_id, name, unique=True):
            item = {**summary(resource_id, name), "queryNameUnique": unique}
            expect("GET", SEARCH, {"items": [item]}, params={"resourceId": resource_id, "resourceType": "metric"})

        selected = {"build_id": BUILD, "metric_resource_ids": [uid, OTHER_ID]}
        exact(uid, "revenue")
        exact(OTHER_ID, "cost")
        metric_body = {"metrics": ["revenue", "cost"]}
        choices = {"buildId": BUILD, **metric_body, "dimensions": [{"optionId": "channel", "displayName": "渠道",
                   "dimensionType": "categorical", "granularities": [], "operators": ["="], "granted": False}]}
        expect("POST", BUILD_PATH + "/available-dimensions", choices, body=metric_body)
        assert await call("get_available_dimensions", selected) == choices

        groups = [{"name": "channel", "bindings": {uid: {"optionId": "A"}, OTHER_ID: {"optionId": "B"}}}]
        query = {**selected, "group_by": groups, "order_by": [{"fieldId": uid, "direction": "DESC"}]}
        body = {**metric_body, "mode": "QUERY", "groupBy": [{"name": "channel", "bindings": {
                "revenue": {"optionId": "A"}, "cost": {"optionId": "B"}}}],
                "filters": [], "orderBy": [{"fieldId": "revenue", "direction": "DESC"}], "limit": 1000}
        table = {"buildId": BUILD, "commitSha": "a" * 40, "mode": "QUERY", "businessTimezone": "Asia/Shanghai",
                 "normalizedTimeRange": {"startTime": None, "endTime": None}, "resultTruncated": True,
                 "columns": [{"name": "amount", "type": "number"}], "rows": [["12.3400"], [None]]}
        for response in (table, {**table, "rows": [], "resultTruncated": False}):
            exact(uid, "revenue")
            exact(OTHER_ID, "cost")
            expect("POST", BUILD_PATH + "/queries", response, body=body)
            assert await call("query_metrics", query) == response

        # 重名不执行查询；部署切换后旧构建的授权和可用性错误均透传。
        exact(uid, "revenue", unique=False)
        ambiguous = await session.call_tool("query_metrics", query)
        assert ambiguous.isError and ambiguous.structuredContent["error"]["code"] == "AMBIGUOUS_METRIC"
        expect("GET", CURRENT, {**TARGET, "activeBuildId": SECOND_BUILD}, params=TARGET)
        assert (await call("get_current_deployment", TARGET_ARGS))["activeBuildId"] == SECOND_BUILD
        for status, code in [(403, "ACCESS_DENIED"), (410, "ARTIFACT_UNAVAILABLE")]:
            exact(uid, "revenue")
            exact(OTHER_ID, "cost")
            expect("POST", BUILD_PATH + "/queries", {"title": "Rejected", "status": status,
                   "detail": "test denial", "code": code, "buildId": BUILD, "retryable": False}, body=body, status=status)
            result = await session.call_tool("query_metrics", query)
            assert result.isError and result.structuredContent["error"]["code"] == code
            assert result.structuredContent["error"]["buildId"] == BUILD


@pytest.mark.skipif(not os.environ.get("MCP_INSPECTOR_ENTRY"), reason="需要本机 MCP Inspector CLI")
@pytest.mark.asyncio
async def test_inspector_and_python_agree_on_active_build(platform, tmp_path):
    url, expect = platform
    env = {"METRIC_PLATFORM_URL": url, "METRIC_PLATFORM_TOKEN": TOKEN}
    launch = StdioServerParameters(command=sys.executable, args=["-m", "metric_platform_mcp"], env=env)
    deployment = {**TARGET, "activeBuildId": BUILD}
    expect("GET", CURRENT, deployment, params=TARGET)
    async with stdio_client(launch) as streams, ClientSession(*streams) as session:
        await session.initialize()
        schemas = {tool.name: tool.inputSchema for tool in (await session.list_tools()).tools}
        python_result = await session.call_tool("get_current_deployment", TARGET_ARGS)
        assert python_result.structuredContent["activeBuildId"] == BUILD

    # Inspector 自行启动同一模块，对比两种客户端实际发现的 schema 与生效构建。
    config = tmp_path / "inspector.json"
    config.write_text(json.dumps({"mcpServers": {"platform": {
        "command": sys.executable, "args": launch.args, "env": env}}}), encoding="utf-8")
    cli = ["node", os.environ["MCP_INSPECTOR_ENTRY"], "--cli", "--config", str(config),
           "--server", "platform", "--method"]

    def inspect(*args):
        result = subprocess.run([*cli, *args], capture_output=True, text=True, encoding="utf-8", timeout=60)
        assert result.returncode == 0, result.stderr
        return json.loads(result.stdout)

    listed = inspect("tools/list")["tools"]
    assert {tool["name"]: tool["inputSchema"] for tool in listed} == schemas
    expect("GET", CURRENT, deployment, params=TARGET)
    current = inspect("tools/call", "--tool-name", "get_current_deployment",
                      "--tool-arg", *(f"{key}={value}" for key, value in TARGET_ARGS.items()))
    assert current["structuredContent"]["activeBuildId"] == BUILD
