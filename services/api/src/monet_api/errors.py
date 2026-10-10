"""RFC 9457 problem+json responses for every error."""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger(__name__)
PROBLEM = "application/problem+json"


class Problem(Exception):
    def __init__(
        self, status: int, title: str, detail: str | None = None, headers: dict[str, str] | None = None
    ) -> None:
        self.status, self.title, self.detail, self.headers = status, title, detail, headers


def _resp(
    status: int, title: str, detail: str | None, request: Request, headers: dict[str, str] | None = None
) -> JSONResponse:
    body = {"type": "about:blank", "title": title, "status": status, "instance": request.url.path}
    if detail:
        body["detail"] = detail
    return JSONResponse(body, status_code=status, media_type=PROBLEM, headers=headers)


def install(app: FastAPI, *, expose_errors: bool) -> None:
    @app.exception_handler(Problem)
    async def _problem(request: Request, exc: Problem):
        return _resp(exc.status, exc.title, exc.detail, request, exc.headers)

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException):
        return _resp(
            exc.status_code,
            str(exc.detail) if exc.detail else "Error",
            None,
            request,
            getattr(exc, "headers", None),
        )

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError):
        detail = "; ".join(f"{'.'.join(str(p) for p in e['loc'][1:])}: {e['msg']}" for e in exc.errors())
        return _resp(422, "Invalid request", detail[:1000], request)

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception):
        log.exception("unhandled error on %s", request.url.path)
        return _resp(500, "Internal error", repr(exc) if expose_errors else None, request)
