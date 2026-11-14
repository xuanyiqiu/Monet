"""Operational endpoints. Deliberately absent from the OpenAPI contract (not part of the app's API)."""

import hmac
import json

from fastapi import APIRouter, Request, Response

from monet_api.errors import Problem

router = APIRouter()


@router.get("/v1/metrics", include_in_schema=False)
async def metrics(request: Request, format: str = "prometheus") -> Response:
    """Prometheus text by default, `?format=json` for humans.

    Open in dev. In prod it needs `Authorization: Bearer $MONET_METRICS_TOKEN`; when no token is configured
    (or it does not match) the endpoint answers 404, so its existence is not advertised.
    """
    settings = request.app.state.settings
    if not settings.is_dev:
        header = request.headers.get("authorization", "")
        supplied = header[7:] if header.lower().startswith("bearer ") else ""
        if not settings.metrics_token or not hmac.compare_digest(supplied, settings.metrics_token):
            raise Problem(404, "Not Found")
    m = request.app.state.metrics
    if format == "json":
        return Response(json.dumps(m.snapshot()), media_type="application/json")
    return Response(m.render(), media_type="text/plain; version=0.0.4; charset=utf-8")
