"""Request-id propagation and the logging plumbing behind it.

The backend forwards an ``X-Request-Id`` so one call can be followed across both services; these
pin the three things that has to do to be useful — echo an id back, preserve a trusted inbound one,
and refuse to echo a malformed one verbatim — plus the two pure functions the middleware leans on.
"""

from __future__ import annotations

import logging
import re
import uuid

from fastapi.testclient import TestClient

from core.observability import (
    HEADER,
    RequestIdFilter,
    _resolve_request_id,
    request_id_ctx,
)
from main import app

client = TestClient(app)

_UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def test_response_carries_a_generated_id_when_none_is_sent():
    response = client.get("/health")

    assert response.status_code == 200
    # A caller that sent no id still gets one back to quote in a report.
    assert _UUID_RE.fullmatch(response.headers[HEADER])


def test_a_trusted_inbound_id_is_preserved_end_to_end():
    response = client.get("/health", headers={HEADER: "trace-abc_123"})

    # Same id in the response: the call keeps the identity it arrived with, which is what lets the
    # backend's log line and this service's line be matched.
    assert response.headers[HEADER] == "trace-abc_123"


def test_a_malformed_inbound_id_is_replaced_not_echoed():
    """An id with spaces is not a safe token; it must not come back verbatim."""
    forged = "not a valid id"

    response = client.get("/health", headers={HEADER: forged})

    assert response.headers[HEADER] != forged
    assert _UUID_RE.fullmatch(response.headers[HEADER])


def test_resolve_keeps_safe_ids_and_mints_uuids_for_the_rest():
    assert _resolve_request_id("abc-123_XYZ") == "abc-123_XYZ"

    # None, empty, too long, or containing punctuation the pattern forbids: a fresh uuid instead.
    for bad in (None, "", "x" * 65, "has spaces", "semi;colon", "new\nline"):
        assert _UUID_RE.fullmatch(_resolve_request_id(bad)), bad


def test_filter_stamps_the_current_context_id_onto_the_record():
    record = logging.LogRecord("t", logging.INFO, __file__, 1, "msg", None, None)
    token = request_id_ctx.set("ctx-42")
    try:
        assert RequestIdFilter().filter(record) is True
        assert record.request_id == "ctx-42"
    finally:
        request_id_ctx.reset(token)


def test_filter_reads_the_default_when_no_request_is_in_scope():
    record = logging.LogRecord("t", logging.INFO, __file__, 1, "msg", None, None)

    RequestIdFilter().filter(record)

    # The context variable's default outside a request, mirroring the backend's empty bracket.
    assert record.request_id == "-"
