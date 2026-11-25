"""校验本地参数拒绝、平台错误以及构建受理状态未知时的恢复语义。"""

import json

import httpx
import pytest
from metric_platform_mcp.server import Gateway
from test_stdio import BUILD, BUILD_VIEW, SUBMIT_ARGS, TARGET_ARGS, TOKEN


@pytest.mark.parametrize("missing", ["environment_id", "expected_config_version", "commit_sha",
                                     "deployment_policy", "idempotency_key"])
async def test_invalid_build_does_not_make_http_request(missing):
    args = {key: value for key, value in SUBMIT_ARGS.items() if key != missing}
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(pytest.fail)) as http:
        result = await Gateway(http).call("submit_build", args)
        assert result.isError and result.structuredContent["error"]["code"] == "INVALID_REQUEST"


async def test_build_timeout_preserves_original_parameters_on_retry():
    requests = []

    def respond(request):
        requests.append((request.url, json.loads(request.content)))
        if len(requests) == 1:
            raise httpx.ReadTimeout("response lost")
        return httpx.Response(202, json=BUILD_VIEW)

    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(respond)) as http:
        gateway = Gateway(http)
        failed = await gateway.call("submit_build", SUBMIT_ARGS)
        error = failed.structuredContent["error"]
        assert failed.isError and (error["code"], error["retryable"]) == ("BUILD_ACCEPTANCE_UNKNOWN", True)
        accepted = await gateway.call("submit_build", SUBMIT_ARGS)
        assert accepted.structuredContent["buildId"] == BUILD and requests[0] == requests[1]


@pytest.mark.parametrize("code", ["SERVICE_UNAVAILABLE", "INVALID_SERVICE_RESPONSE"])
async def test_uncertain_upstream_submission_preserves_recovery(code):
    response = httpx.Response(502, json={"code": code, "detail": "Failed upstream", "retryable": False})
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(lambda _: response)) as http:
        result = await Gateway(http).call("submit_build", SUBMIT_ARGS)
        error = result.structuredContent["error"]
        assert result.isError and error["code"] == "BUILD_ACCEPTANCE_UNKNOWN" and error["retryable"]


async def test_transport_failures_and_problem_diagnostics():
    def fail(_):
        raise httpx.ConnectError(TOKEN)

    replies = iter([httpx.Response(200, json={}), httpx.Response(202, json={}),
                    httpx.Response(403, json={"detail": "Access denied", "status": 403}),
                    httpx.Response(422, json={"code": "INVALID_DIMENSION_OPTION", "phase": "QUERY",
                                   "detail": f"Rejected Bearer {TOKEN}", "buildId": None, "retryable": False})])
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(fail)) as http:
        result = await Gateway(http).call("get_current_deployment", TARGET_ARGS)
        assert result.isError and result.structuredContent["error"]["code"] == "SERVICE_UNREACHABLE"
        assert TOKEN not in result.content[0].text
    async with httpx.AsyncClient(base_url="http://test", headers={"Authorization": f"Bearer {TOKEN}"},
                                 transport=httpx.MockTransport(lambda _: next(replies))) as http:
        gateway = Gateway(http)
        unsafe = {**TARGET_ARGS, "repository": f"https://user:{TOKEN}@example.invalid/repo"}
        for tool, args, code, retry in [
            ("get_current_deployment", unsafe, "INVALID_REQUEST", False),
            ("get_current_deployment", TARGET_ARGS, "HTTP_ERROR", False),
            ("submit_build", SUBMIT_ARGS, "BUILD_ACCEPTANCE_UNKNOWN", True),
            ("submit_build", SUBMIT_ARGS, "FORBIDDEN", False),
            ("get_build", {"build_id": BUILD}, "INVALID_DIMENSION_OPTION", False),
            ("query_metrics", {"build_id": BUILD, "metric_resource_ids": ["revenue"], "limit": 0},
             "INVALID_REQUEST", False),
        ]:
            result = await gateway.call(tool, args)
            error = result.structuredContent["error"]
            assert result.isError and (error["code"], error["retryable"]) == (code, retry)
            if "build_id" in args:
                assert error["buildId"] == BUILD
            assert TOKEN not in result.content[0].text
