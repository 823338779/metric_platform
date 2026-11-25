"""MCP 调用所需的平台 HTTP 契约。响应校验保留平台的扩展字段。"""

import math
import re
from datetime import datetime
from typing import Literal, Self
from urllib.parse import urlsplit
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, JsonValue, model_validator
from pydantic.alias_generators import to_camel

JsonObject = dict[str, JsonValue]
INVALID_REF = re.compile(r"[\x00-\x20\x7f~^:?*\[\\]")
REF_PREFIX = "refs/"
REF_SUFFIX = ".lock"
INVALID_BRANCH_NAME_MESSAGE = "branchName must be a short Git branch name"


class Contract(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class Response(BaseModel):
    # 网关返回平台原始 JSON；按 wire 字段名检查后再使用，避免误认 Python 别名。
    model_config = ConfigDict(alias_generator=to_camel)


def validate_branch(value: str) -> str:
    """校验 Git 短分支名，防止 ref 或选项被混入远端读取。"""
    if (
        not value
        or value.startswith((REF_PREFIX, "-", "/"))
        or value.endswith(("/", "."))
        or INVALID_REF.search(value)
        or ".." in value
        or "@{" in value
        or value == "@"
        or any(not part or part.startswith(".") or part.endswith(REF_SUFFIX) for part in value.split("/"))
    ):
        raise ValueError(INVALID_BRANCH_NAME_MESSAGE)
    return value


def valid_repository(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"http", "https", "ssh"}
        or not parsed.hostname
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or any(char.isspace() for char in value)
        or parsed.scheme in {"http", "https"}
        and parsed.username is not None
    ):
        raise ValueError("repository must be a remote URL without embedded credentials")
    return value


FilterOperator = Literal["=", "!=", ">", ">=", "<", "<=", "IN"]


class SelectedDimension(Contract):
    option_id: str = Field(description="固定构建及指标集合的维度选项")
    grain: str | None = Field(default=None, description="引擎支持的时间粒度")


class BoundDimension(Contract):
    name: str = Field(pattern=r"^[a-z][a-z0-9_]{0,62}$", description="对齐后的公共输出列名")
    bindings: dict[str, SelectedDimension] = Field(
        min_length=1, max_length=100, description="指标名到该单指标维度选项的绑定"
    )


class SelectedFilter(Contract):
    option_id: str
    operator: FilterOperator
    value: JsonValue

    @model_validator(mode="after")
    def validate_value(self) -> Self:
        if self.operator == "IN":
            if not isinstance(self.value, list) or not 1 <= len(self.value) <= 100:
                raise ValueError("IN requires 1 to 100 scalar values")
            values = self.value
        else:
            values = [self.value]
        for value in values:
            if not isinstance(value, (str, int, float, bool)) or (
                isinstance(value, float) and not math.isfinite(value)
            ):
                raise ValueError("filter values must be finite JSON scalars")
        return self


class SelectedOrder(Contract):
    field_id: str
    direction: Literal["ASC", "DESC"]


class DimensionOption(Response):
    granted: bool | None = None
    option_id: str
    name: str | None = None
    semantic_model_name: str | None = None
    display_name: str
    dimension_type: str
    granularities: list[str]
    operators: list[str]


class AvailableDimensionsView(Response):
    build_id: UUID
    metrics: list[str]
    dimensions: list[DimensionOption]


class ResultColumn(Response):
    name: str
    type: str


class NormalizedTimeRange(Response):
    start_time: str | None
    end_time: str | None


class MetricQueryResult(Response):
    build_id: UUID
    commit_sha: str | None
    columns: list[ResultColumn]
    rows: list[list[JsonValue]]
    result_truncated: bool
    mode: Literal["QUERY"]
    business_timezone: str
    normalized_time_range: NormalizedTimeRange


class ResourceSourceView(Response):
    path: str = Field(description="工程内相对路径")
    content: str = Field(description="封存源码正文")


class ResourceSummary(Response):
    resource_id: str
    name: str
    label: str | None = None
    description: str
    resource_type: str
    query_name_unique: bool | None = None


class ResourceView(Response):
    """统一资源读取响应；原生字段完整保留在 resource 内。"""

    build_id: UUID = Field(description="资源及源码所属的固定构建")
    resource_id: str = Field(description="dbt 原生资源 unique_id")
    resource: JsonObject = Field(description="完整的 dbt 原生节点定义")
    source: ResourceSourceView | None = Field(default=None, description="对应的完整源码文件；无路径或输入快照时为空")


class CatalogPage(Response):
    items: list[ResourceSummary]
    next_cursor: str | None = None


class DeploymentKey(Response):
    repository: str
    environment_id: UUID
    branch_name: str


class DeploymentAttempt(DeploymentKey):
    build_id: UUID
    generation: int = Field(ge=1)
    deployment_status: Literal["WAITING_FOR_BUILD", "PENDING", "CHECKING", "DEPLOYED", "STALE", "FAILED", "CANCELLED"]
    created_at: datetime
    reason: str | None = None


class DeploymentTargetView(DeploymentKey):
    active_build_id: UUID | None
    active_commit_sha: str | None = None
    latest_attempt: DeploymentAttempt | None = None


class ConnectionTarget(Response):
    type: Literal["starrocks", "postgres", "duckdb"]
    instance_id: str


class ExecutionSnapshot(Response):
    environment_id: UUID
    connection: ConnectionTarget
    credential_ref: str
    catalog: str
    schema_name: str = Field(alias="schema")
    business_timezone: str = "UTC"


class BuildView(Response):
    build_id: UUID
    repository: str
    branch_name: str | None
    requested_commit_sha: str | None
    commit_sha: str | None
    execution: ExecutionSnapshot
    toolchain_version: str
    build_status: Literal["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "OUTCOME_UNKNOWN"]
    phase: str
    created_at: datetime
    updated_at: datetime
    initial_deployment: DeploymentAttempt | None = None
    catalog_available: bool = False
