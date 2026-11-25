"""平台 HTTP 响应校验、ProblemDetail 转换与提交受理状态判断。"""

import httpx
from pydantic import BaseModel, ValidationError

from .contracts import JsonObject


class ToolFailure(Exception):
    def __init__(self, error: JsonObject) -> None:
        self.error = error


ACCEPTANCE_UNKNOWN = "BUILD_ACCEPTANCE_UNKNOWN"
HTTP_ERROR = "HTTP_ERROR"
RETRY_SUBMISSION = "Retry with the original inputs and idempotency key."
HTTP_STATUS_CODES = {401: "UNAUTHORIZED", 403: "FORBIDDEN"}
# 平台默认等待上游查询 1860 秒，外围读取再留出响应和序列化时间。
QUERY_TIMEOUT = httpx.Timeout(120, read=1920)
AMBIGUOUS_UPSTREAM_CODES = ("SERVICE_UNAVAILABLE", "INVALID_SERVICE_RESPONSE")


async def request(
    http: httpx.AsyncClient,
    method: str,
    path: str,
    response_model: type[BaseModel],
    *,
    params: JsonObject | None = None,
    body: JsonObject | None = None,
    build_id: str | None = None,
    submitting: bool = False,
    timeout: httpx.Timeout | None = None,
) -> JsonObject:
    try:
        response = await http.request(
            method, path, params=params, json=body, follow_redirects=False, timeout=timeout or http.timeout
        )
    except httpx.RequestError as exc:
        unknown = submitting and not isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout))
        raise ToolFailure(
            {
                "code": ACCEPTANCE_UNKNOWN if unknown else "SERVICE_UNREACHABLE",
                "message": RETRY_SUBMISSION if unknown else "Cannot reach Metric Platform.",
                "retryable": True,
                "buildId": build_id,
            }
        ) from exc
    try:
        payload = response.json()
    except ValueError:
        payload = None
    if response.is_success and isinstance(payload, dict):
        try:
            # 以严格 JSON 类型验证原始响应，保留 UUID 与时间的标准字符串编码。
            response_model.model_validate_json(response.content, strict=True)
        except ValidationError:
            pass
        else:
            if build_id is None or payload.get("buildId", build_id) == build_id:
                return payload

    # 无法确认 POST 已受理时只提示使用原幂等键恢复。带业务码的拒绝沿用平台诊断。
    status = response.status_code
    problem = payload if not response.is_success and isinstance(payload, dict) else {}
    code = problem.get("code")
    known = isinstance(code, str) and bool(code)
    uncertain = submitting and (
        code in AMBIGUOUS_UPSTREAM_CODES or not known and (response.is_success or status >= 500 or status == 408)
    )
    if uncertain:
        raise ToolFailure(
            {"code": ACCEPTANCE_UNKNOWN, "message": RETRY_SUBMISSION, "buildId": build_id, "retryable": True}
        )
    detail = problem.get("detail")
    error: JsonObject = {
        "code": code if known else HTTP_STATUS_CODES.get(status, HTTP_ERROR),
        "message": detail if isinstance(detail, str) else f"Unexpected platform response (HTTP {status}).",
        "buildId": problem.get("buildId") if isinstance(problem.get("buildId"), str) else build_id,
        "retryable": problem["retryable"] if isinstance(problem.get("retryable"), bool) else status >= 500,
    }
    if isinstance(problem.get("phase"), str):
        error["phase"] = problem["phase"]
    token = http.headers.get("Authorization", "").removeprefix("Bearer ")
    if token:
        error = {k: v.replace(token, "[redacted]") if isinstance(v, str) else v for k, v in error.items()}
    raise ToolFailure(error)
