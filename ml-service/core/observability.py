"""Request tracing and log configuration — the ml-service side of end-to-end traceability.

The backend tags each inbound request with an id and forwards it on the ``X-Request-Id`` header
(see its ``RequestIdFilter`` and the ML transport interceptor). Here that id is bound to a context
variable for the life of the request, printed on every log line by the formatter configured below,
and echoed back on the response — so one call can be followed across both services by grepping a
single id.

Logging is set up in code rather than through a uvicorn ``--log-config`` file so the one knob that
matters, ``ML_LOG_LEVEL``, stays with the rest of the configuration in :mod:`core.config`.
"""

from __future__ import annotations

import contextvars
import logging
import re
import uuid
from logging.config import dictConfig

from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

#: Response and accepted inbound header. Matched case-insensitively on the way in.
HEADER = "X-Request-Id"

#: The current request's id. ``"-"`` until a request is in scope — startup and background work read
#: that, mirroring the empty bracket the backend prints for the same case.
request_id_ctx: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")

#: What an inbound id must look like to be trusted: printable, unpunctuated, bounded. Kept in step
#: with the backend's own check. A value failing it is written into log lines and a response header,
#: so an unchecked one could forge log entries (an embedded newline) or split the response header —
#: it is replaced rather than sanitised.
_SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")


class RequestIdFilter(logging.Filter):
    """Copies the current request id onto each record so the formatter can print it."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_ctx.get()
        return True


def configure_logging(level: str) -> None:
    """Route every logger through one console handler that prints the request id.

    Honors ``ML_LOG_LEVEL``. When launched from the uvicorn CLI, uvicorn configures its own
    loggers first and then imports this app; calling this at import time therefore runs afterward
    and wins. uvicorn's own loggers are pointed at the same handler (empty handler list + propagate)
    so their lines carry the same format instead of a parallel one.
    """
    dictConfig({
        "version": 1,
        "disable_existing_loggers": False,
        "filters": {
            "request_id": {"()": "core.observability.RequestIdFilter"},
        },
        "formatters": {
            "standard": {
                "format": "%(asctime)s %(levelname)5s [%(request_id)s] %(name)s : %(message)s",
                "datefmt": "%Y-%m-%d %H:%M:%S",
            },
        },
        "handlers": {
            "console": {
                "class": "logging.StreamHandler",
                "formatter": "standard",
                "filters": ["request_id"],
            },
        },
        "root": {"level": level.upper(), "handlers": ["console"]},
        "loggers": {
            "uvicorn": {"handlers": [], "propagate": True},
            "uvicorn.error": {"handlers": [], "propagate": True},
            "uvicorn.access": {"handlers": [], "propagate": True},
        },
    })


def _resolve_request_id(inbound: str | None) -> str:
    """The inbound id if present and safe to echo, otherwise a fresh random one."""
    if inbound is not None and _SAFE_ID.fullmatch(inbound):
        return inbound
    return str(uuid.uuid4())


class RequestIdMiddleware:
    """Binds the request id to :data:`request_id_ctx` and echoes it on the response.

    Written as raw ASGI rather than with ``@app.middleware("http")`` on purpose: Starlette's
    ``BaseHTTPMiddleware`` runs the endpoint in a child task, which makes a context variable set
    just before ``call_next`` unreliable to read downstream. A raw ASGI middleware sets it inline on
    the same task as the handler, so every log line the request produces sees it.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = _resolve_request_id(_inbound_header(scope))
        token = request_id_ctx.set(request_id)

        async def send_with_request_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message)[HEADER] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_with_request_id)
        finally:
            # Context variables are per-task, but resetting keeps a pooled worker from carrying a
            # stale id into whatever it handles next.
            request_id_ctx.reset(token)


def _inbound_header(scope: Scope) -> str | None:
    """Reads ``X-Request-Id`` from the raw ASGI scope headers (lowercased byte tuples)."""
    for name, value in scope.get("headers", []):
        if name == b"x-request-id":
            return value.decode("latin-1")
    return None
