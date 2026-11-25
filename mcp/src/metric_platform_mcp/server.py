"""Client-neutral stdio tools for the public Metric Platform API."""

import asyncio
import inspect
import json
import os
from datetime import datetime
from typing import Annotated, Literal, Self
from urllib.parse import quote
from uuid import UUID

import httpx
from mcp import types
from mcp.server import Server
from mcp.server.stdio import stdio_server
from pydantic import AfterValidator, BaseModel, ConfigDict, Field, ValidationError, model_validator

from .client import QUERY_TIMEOUT, ToolFailure, request
from .contracts import (
    AvailableDimensionsView,
    BoundDimension,
    BuildView,
    CatalogPage,
    DeploymentTargetView,
    JsonObject,
    MetricQueryResult,
    ResourceView,
    SelectedDimension,
    SelectedFilter,
    SelectedOrder,
    valid_repository,
    validate_branch,
)

API = "/api/dbt"
BUILDS = f"{API}/builds"
DEPLOYMENTS = f"{API}/deployments/current"
ENVIRONMENTS = f"{API}/environments"


class Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CurrentDeployment(Arguments):
    """用户要分析当前部署时先调用一次；用户已指定构建 ID 时直接使用该构建。
    status=active 时，把 activeBuildId 作为后续目录、维度和查询工具的 build_id，记录 activeCommitSha。
    status=undeployed 表示此目标尚无生效版本；可请用户指定构建 ID。latestAttempt 只描述最近一次部署尝试。
    """

    repository: Annotated[str, AfterValidator(valid_repository)] = Field(
        min_length=1, max_length=2048, description="从用户指定的项目或部署配置取得 Git 远端 URL，使用无凭据地址"
    )
    environment_id: UUID = Field(description="目标部署环境的 UUID，取自用户或调用方环境配置")
    branch_name: Annotated[str, AfterValidator(validate_branch)] = Field(
        description="该部署目标的 Git 短分支名，如 main，从用户或部署配置取得"
    )


class BuildIdentity(Arguments):
    build_id: UUID = Field(
        description="用户指定的构建 UUID、get_current_deployment 的 activeBuildId，"
        "或 submit_build 返回的 buildId；同一次分析始终沿用该值"
    )


RESOURCE_ID_PATTERN = r"^[a-z_]+\.[^/\\?#]+$"


class SearchCatalog(BuildIdentity):
    """固定构建后，用关键词搜索资源，或用完整 resource_id 精确查找。items 含 resourceId、名称、标签和说明。
    关键词无匹配时 items 为空；nextCursor 非空时可续页。多个候选符合时，用 get_resource 对比定义。
    将选中指标的 resourceId 交给 get_available_dimensions 或 query_metrics。
    """

    keyword: str | None = Field(
        default=None, min_length=1, description="按关键词匹配资源 ID、名称、标签或说明。与 resource_id 二选一"
    )
    resource_id: str | None = Field(
        default=None, pattern=RESOURCE_ID_PATTERN, description="精确查找已知的完整资源 ID。与 keyword 二选一"
    )
    resource_type: (
        Literal["source", "seed", "snapshot", "model", "semantic_model", "metric", "saved_query", "exposure"] | None
    ) = Field(default=None, description="查指标时选 metric；省略则搜索所有业务资源类型")
    limit: int = Field(default=50, ge=1, le=200, description="每页最多返回的候选数，服务可按响应大小提前分页")
    cursor: str | None = Field(
        default=None, description="首轮省略；续页原样传回上页 nextCursor，沿用原构建、关键词和类型"
    )

    @model_validator(mode="after")
    def one_selector(self) -> Self:
        if (self.keyword is None) == (self.resource_id is None):
            raise ValueError("provide exactly one of keyword or resource_id")
        return self


class GetResource(BuildIdentity):
    """搜索后需要核对指标口径或解释候选差异时调用。resource 是该版本的原生节点，包含定义和公开属性；
    source 在可用时提供源码路径和正文。用定义向用户澄清，后续查询沿用所选指标的完整 resourceId。
    """

    resource_id: str = Field(
        pattern=RESOURCE_ID_PATTERN, description="同一构建 search_catalog 返回的 items[].resourceId"
    )


class MetricSelection(BuildIdentity):
    """指标确定后，需要分组或过滤时调用。按 dimensions[].displayName 和 name 找到用户所指维度，
    将该项的 optionId 用于 query_metrics，粒度从 granularities 选，过滤操作符从 operators 选。
    granted 表示当前账号是否获准查询该维度。更换构建或指标组合后重新获取选项。
    没有所需维度时向用户澄清。
    """

    metric_resource_ids: list[Annotated[str, Field(min_length=1)]] = Field(
        min_length=1,
        max_length=100,
        description="同一构建中由 search_catalog 选定的完整指标 resourceId 列表，如 metric.api_review.revenue",
    )


class ResourceBoundDimension(BoundDimension):
    bindings: dict[str, SelectedDimension] = Field(
        min_length=1, max_length=100, description="每个已选指标资源 ID 对应的单指标维度选项"
    )


class QueryMetrics(MetricSelection):
    """指标和分析口径确定后执行查询。处理“上月”等相对时间时，先从 get_build 的 execution.businessTimezone 确定日期范围。
    按 columns 的名称、类型及顺序读取 rows 各单元格，保留 null；成功且 rows 为空表示没有匹配数据。
    报告 buildId、commitSha、businessTimezone 和 normalizedTimeRange；resultTruncated 为真时说明仅展示部分行。
    isError 为真时按 error.code 和 message 说明失败；切换构建前请用户重新选择。
    """

    group_by: list[SelectedDimension | ResourceBoundDimension] = Field(
        default_factory=list,
        max_length=100,
        description="按指标组合的维度 optionId 分组，可选该维度支持的 grain。"
        "需按不同路径对齐多个指标时，分别查询各单指标维度，用 bindings 将每个指标资源 ID 映射到其单指标选项。",
    )
    filters: list[SelectedFilter] = Field(
        default_factory=list,
        max_length=100,
        description="optionId 和 operator 取自指标组合的维度选项；"
        "value 取自用户筛选条件。IN 传 1–100 个标量，其他操作符传单个标量。",
    )
    order_by: list[SelectedOrder] = Field(
        default_factory=list, max_length=100, description="fieldId 为所选指标资源 ID、维度 optionId 或复合分组列名"
    )
    start_time: datetime | None = Field(default=None, description="起始时间；有偏移量时转换到构建业务时区")
    end_time: datetime | None = Field(default=None, description="结束时间；无偏移量时按构建业务时区解释")
    limit: int = Field(
        default=1000, ge=1, le=10000, description="本次查询最多返回的行数，结合 resultTruncated 判断是否截断"
    )


class SubmitBuild(Arguments):
    """用户要求从指定提交构建时调用。返回 buildId 表示服务已受理，用 get_build 跟踪构建及关联部署。
    遇到 BUILD_ACCEPTANCE_UNKNOWN，服务可能已受理，恢复时重用所有原始参数和同一个幂等键。
    """

    repository: Annotated[str, AfterValidator(valid_repository)] = Field(
        min_length=1, max_length=2048, description="从待构建项目取得无凭据的 Git 远端 URL"
    )
    branch_name: Annotated[str, AfterValidator(validate_branch)] = Field(description="用户指定的源分支短名，如 main")
    commit_sha: str = Field(pattern=r"^[0-9a-f]{40}$", description="从用户或仓库取得此次指定提交的完整小写 SHA")
    environment_id: UUID = Field(description="平台中目标环境的 UUID")
    expected_config_version: int = Field(ge=1, description="用户确认的目标环境配置版本")
    deployment_policy: Literal["NONE", "ON_SUCCESS"] = Field(
        description="按用户意图显式选择：NONE 仅构建，ON_SUCCESS 在构建成功后尝试部署"
    )
    idempotency_key: UUID = Field(description="每个新构建使用新的全局唯一 UUID，重试时复用")


class GetBuild(BuildIdentity):
    """submit_build 受理后或用户指定构建时调用。buildStatus 和 phase 表示构建进展，失败原因见 error。
    initialDeployment 为空表示没有关联的部署尝试；否则查看 deploymentStatus 和 reason，仅 DEPLOYED 表示该次部署成功。
    catalogAvailable 表示目录可读；queryAvailability=unknown 时以实际查询结果为准。
    解释相对日期可读取 execution.businessTimezone；要确认此刻生效的版本，用 get_current_deployment。
    """


TOOLS: dict[str, type[Arguments]] = {
    "get_current_deployment": CurrentDeployment,
    "search_catalog": SearchCatalog,
    "get_resource": GetResource,
    "get_available_dimensions": MetricSelection,
    "query_metrics": QueryMetrics,
    "submit_build": SubmitBuild,
    "get_build": GetBuild,
}


class Gateway:
    def __init__(self, http: httpx.AsyncClient) -> None:
        self.http = http

    async def search(self, build: str, params: JsonObject) -> JsonObject:
        result = await request(
            self.http, "GET", f"{BUILDS}/{build}/catalog/search", CatalogPage, params=params, build_id=build
        )
        uid = params.get("resourceId")
        if uid is None:
            return result
        # 完整 ID 的首段是 dbt 资源类型；精确查询即使未指定类型也必须返回对应节点。
        expected_type = uid.partition(".")[0]
        items = result["items"]
        if (
            len(items) != 1
            or items[0]["resourceId"] != uid
            or items[0]["resourceType"] != expected_type
            or params.get("resourceType", expected_type) != expected_type
            or result.get("nextCursor")
        ):
            raise ToolFailure(
                {
                    "code": "HTTP_ERROR",
                    "message": "Invalid exact resource lookup response.",
                    "retryable": False,
                    "buildId": build,
                }
            )
        return result

    async def metrics(self, args: MetricSelection) -> JsonObject:
        build = str(args.build_id)
        names: dict[str, str] = {}
        for uid in dict.fromkeys(args.metric_resource_ids):
            result = await self.search(build, {"resourceId": uid, "resourceType": "metric"})
            item = result["items"][0]
            if item.get("queryNameUnique") is not True or item["name"] in names.values():
                raise ToolFailure(
                    {
                        "code": "AMBIGUOUS_METRIC",
                        "message": "Metric query name is not unique.",
                        "retryable": False,
                        "buildId": build,
                    }
                )
            names[uid] = item["name"]
        body: JsonObject = {"metrics": list(names.values())}
        path = f"{BUILDS}/{build}/available-dimensions"
        if isinstance(args, QueryMetrics):
            path = f"{BUILDS}/{build}/queries"
            body.update(
                args.model_dump(
                    mode="json",
                    by_alias=True,
                    exclude_none=True,
                    exclude={"metric_resource_ids", "build_id", "start_time", "end_time", "group_by", "order_by"},
                )
            )
            groups = []
            for group in args.group_by:
                entry = group.model_dump(mode="json", by_alias=True, exclude_none=True)
                if isinstance(group, BoundDimension):
                    if set(group.bindings) != names.keys():
                        raise ToolFailure(
                            {
                                "code": "INVALID_REQUEST",
                                "message": "Bindings must cover selected IDs.",
                                "retryable": False,
                                "buildId": build,
                            }
                        )
                    entry["bindings"] = {names[key]: value for key, value in entry["bindings"].items()}
                groups.append(entry)
            body.update(
                mode="QUERY",
                groupBy=groups,
                orderBy=[
                    {"fieldId": names.get(order.field_id, order.field_id), "direction": order.direction}
                    for order in args.order_by
                ],
            )
            if args.start_time:
                body["startTime"] = args.start_time.isoformat()
            if args.end_time:
                body["endTime"] = args.end_time.isoformat()
        model = MetricQueryResult if isinstance(args, QueryMetrics) else AvailableDimensionsView
        return await request(self.http, "POST", path, model, body=body, build_id=build, timeout=QUERY_TIMEOUT)

    async def execute(self, args: Arguments) -> JsonObject:
        if isinstance(args, SubmitBuild):
            body = {
                "repository": args.repository,
                "branchName": args.branch_name,
                "commitSha": args.commit_sha,
                "expectedConfigVersion": args.expected_config_version,
                "deploymentPolicy": args.deployment_policy,
                "idempotencyKey": str(args.idempotency_key),
            }
            path = f"{ENVIRONMENTS}/{args.environment_id}/builds"
            return await request(self.http, "POST", path, BuildView, body=body, submitting=True)
        if isinstance(args, GetBuild):
            result = await request(
                self.http, "GET", f"{BUILDS}/{args.build_id}", BuildView, build_id=str(args.build_id)
            )
            result["queryAvailability"] = "unknown"
            return result
        if isinstance(args, MetricSelection):
            return await self.metrics(args)
        if isinstance(args, SearchCatalog):
            params = {
                "keyword": args.keyword,
                "resourceId": args.resource_id,
                "limit": args.limit,
                "resourceType": args.resource_type,
                "cursor": args.cursor,
            }
            return await self.search(str(args.build_id), {k: v for k, v in params.items() if v is not None})
        if isinstance(args, GetResource):
            path = f"{BUILDS}/{args.build_id}/resources/{quote(args.resource_id, safe='')}"
            result = await request(self.http, "GET", path, ResourceView, build_id=str(args.build_id))
            if result["resourceId"] != args.resource_id:
                raise ToolFailure(
                    {
                        "code": "HTTP_ERROR",
                        "message": "Unexpected resource identity.",
                        "retryable": False,
                        "buildId": str(args.build_id),
                    }
                )
            return result
        if isinstance(args, CurrentDeployment):
            result = await request(
                self.http,
                "GET",
                DEPLOYMENTS,
                DeploymentTargetView,
                params={
                    "repository": args.repository,
                    "environmentId": str(args.environment_id),
                    "branchName": args.branch_name,
                },
            )
            result["status"] = "active" if result["activeBuildId"] else "undeployed"
            return result
        raise ValueError("unknown tool")

    async def call(self, name: str, arguments: JsonObject) -> types.CallToolResult:
        failed = False
        try:
            model = TOOLS.get(name)
            if model is None:
                raise ToolFailure({"code": "UNKNOWN_TOOL", "message": "Unknown tool.", "retryable": False})
            data = await self.execute(model.model_validate(arguments))
        except ValidationError:
            failed = True
            data = {"error": {"code": "INVALID_REQUEST", "message": "Invalid tool arguments.", "retryable": False}}
            try:
                data["error"]["buildId"] = str(UUID(str(arguments.get("build_id"))))
            except ValueError:
                pass
        except ToolFailure as exc:
            failed = True
            data = {"error": exc.error}
        return types.CallToolResult(
            isError=failed,
            structuredContent=data,
            content=[types.TextContent(type="text", text=json.dumps(data, ensure_ascii=False, separators=(",", ":")))],
        )


async def serve() -> None:
    url = os.environ.get("METRIC_PLATFORM_URL")
    if not url:
        raise SystemExit("METRIC_PLATFORM_URL is required")
    token = os.environ.get("METRIC_PLATFORM_TOKEN")
    if not token or not token.strip():
        raise SystemExit("METRIC_PLATFORM_TOKEN is required")
    headers = {"Authorization": f"Bearer {token}"}
    async with httpx.AsyncClient(base_url=url, headers=headers, timeout=120) as http:
        gateway = Gateway(http)
        server = Server("metric-platform-mcp")

        @server.list_tools()
        async def list_tools() -> list[types.Tool]:
            return [
                types.Tool(name=name, description=inspect.getdoc(model), inputSchema=model.model_json_schema())
                for name, model in TOOLS.items()
            ]

        server.call_tool(validate_input=False)(gateway.call)
        async with stdio_server() as (reader, writer):
            await server.run(reader, writer, server.create_initialization_options())


def run() -> None:
    asyncio.run(serve())


if __name__ == "__main__":
    run()
