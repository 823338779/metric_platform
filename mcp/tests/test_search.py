"""目录精确查找保留固定构建与类型，并在结果到达模型前核对资源身份。"""

import httpx
import pytest
from metric_platform_mcp.server import Gateway
from test_stdio import BUILD, METRIC_ID, SEARCH, summary


@pytest.mark.parametrize("selectors", [{}, {"resource_id": METRIC_ID, "keyword": "销售额"}])
async def test_requires_one_search_selector(selectors):
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(pytest.fail)) as http:
        result = await Gateway(http).call("search_catalog", {"build_id": BUILD, **selectors})
        assert result.isError and result.structuredContent["error"]["code"] == "INVALID_REQUEST"


async def test_exact_search_preserves_filters_and_upstream_diagnostics():
    resource = "model.shop.sales"
    row = {**summary(resource, "sales"), "resourceType": "model"}
    responses = iter(
        [
            httpx.Response(200, json={"items": [row]}),
            httpx.Response(404, json={"code": "RESOURCE_NOT_FOUND", "detail": "No such model."}),
        ]
    )

    def respond(req):
        assert req.url.path == SEARCH
        assert dict(req.url.params) == {"resourceId": resource, "resourceType": "model", "limit": "3"}
        return next(responses)

    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(respond)) as http:
        args = {"build_id": BUILD, "resource_id": resource, "resource_type": "model", "limit": 3}
        gateway = Gateway(http)
        result = await gateway.call("search_catalog", args)
        assert not result.isError and result.structuredContent == {"items": [row]}
        missing = await gateway.call("search_catalog", args)
        assert missing.isError and missing.structuredContent["error"]["code"] == "RESOURCE_NOT_FOUND"
        assert missing.structuredContent["error"]["message"] == "No such model."


METRIC = summary(METRIC_ID, "revenue")


@pytest.mark.parametrize(
    "page",
    [
        {"items": []},
        {"items": [METRIC, METRIC]},
        {"items": [{**METRIC, "resourceId": "metric.other.revenue"}]},
        {"items": [{**METRIC, "resourceType": "model"}]},
        {"items": [METRIC], "nextCursor": "unexpected"},
    ],
)
@pytest.mark.parametrize("tool", ["search_catalog", "get_available_dimensions"])
async def test_exact_search_rejects_mismatched_success(tool, page):
    def respond(req):
        assert req.url.path == SEARCH
        return httpx.Response(200, json=page)

    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(respond)) as http:
        args = {"resource_id": METRIC_ID} if tool == "search_catalog" else {"metric_resource_ids": [METRIC_ID]}
        result = await Gateway(http).call(tool, {"build_id": BUILD, **args})
        assert result.isError and result.structuredContent["error"]["code"] == "HTTP_ERROR"


async def test_granted_requires_json_boolean():
    choices = {
        "buildId": BUILD,
        "metrics": ["revenue"],
        "dimensions": [
            {
                "optionId": "A",
                "displayName": "渠道",
                "dimensionType": "categorical",
                "granularities": [],
                "operators": ["="],
                "granted": "false",
            }
        ],
    }
    responses = iter([httpx.Response(200, json={"items": [METRIC]}), httpx.Response(200, json=choices)])
    async with httpx.AsyncClient(
        base_url="http://test", transport=httpx.MockTransport(lambda req: next(responses))
    ) as http:
        result = await Gateway(http).call(
            "get_available_dimensions", {"build_id": BUILD, "metric_resource_ids": [METRIC_ID]}
        )
        assert result.isError and result.structuredContent["error"]["code"] == "HTTP_ERROR"
