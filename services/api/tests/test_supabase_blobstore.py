import httpx
import pytest
from fastapi.testclient import TestClient

from monet_api.config import Settings
from monet_api.documents.store import LocalBlobStore, SupabaseBlobStore
from monet_api.main import build_blob_store, create_app

SERVICE_KEY = "super-secret-service-key"
BUCKET = "documents"


def _safe_settings(settings):
    return settings.model_copy(
        update={"database_url": None, "supabase_url": None, "supabase_service_key": None}
    )


def make_transport(
    blobs: dict[str, bytes],
    *,
    fail_status: int | None = None,
    seen: list[httpx.Request] | None = None,
) -> httpx.MockTransport:
    prefix = f"/storage/v1/object/{BUCKET}/"

    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        if fail_status is not None:
            return httpx.Response(fail_status)
        assert request.url.path.startswith(prefix)
        key = request.url.path[len(prefix) :]
        if request.method == "POST":
            blobs[key] = request.content
            return httpx.Response(200, json={"Key": f"{BUCKET}/{key}"})
        if request.method == "GET":
            if key not in blobs:
                return httpx.Response(404)
            return httpx.Response(200, content=blobs[key])
        if request.method == "DELETE":
            if key not in blobs:
                return httpx.Response(404)
            del blobs[key]
            return httpx.Response(200)
        return httpx.Response(405)

    return httpx.MockTransport(handler)


def make_store(blobs: dict[str, bytes], **kwargs) -> SupabaseBlobStore:
    client = httpx.AsyncClient(transport=make_transport(blobs, **kwargs))
    return SupabaseBlobStore("https://project.supabase.co/", SERVICE_KEY, BUCKET, client=client)


def make_fixed_store(status: int, body: dict | None) -> SupabaseBlobStore:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=body)

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return SupabaseBlobStore("https://project.supabase.co/", SERVICE_KEY, BUCKET, client=client)


async def test_put_then_get_round_trip():
    blobs: dict[str, bytes] = {}
    store = make_store(blobs)
    await store.put("docs/one.pdf", b"%PDF-1.4 hello")
    assert await store.get("docs/one.pdf") == b"%PDF-1.4 hello"


async def test_put_sends_auth_upsert_and_url():
    blobs: dict[str, bytes] = {}
    seen: list[httpx.Request] = []
    store = make_store(blobs, seen=seen)
    await store.put("docs/one.pdf", b"data")
    assert len(seen) == 1
    request = seen[0]
    assert request.method == "POST"
    assert str(request.url) == "https://project.supabase.co/storage/v1/object/documents/docs/one.pdf"
    assert request.headers["authorization"] == f"Bearer {SERVICE_KEY}"
    assert request.headers["apikey"] == SERVICE_KEY
    assert request.headers["x-upsert"] == "true"


async def test_get_missing_raises_file_not_found():
    store = make_store({})
    with pytest.raises(FileNotFoundError):
        await store.get("missing.pdf")


async def test_delete_existing_then_get_missing():
    blobs = {"a.pdf": b"abc"}
    store = make_store(blobs)
    await store.delete("a.pdf")
    with pytest.raises(FileNotFoundError):
        await store.get("a.pdf")


async def test_delete_missing_does_not_raise():
    store = make_store({})
    await store.delete("nope.pdf")


async def test_put_error_does_not_leak_service_key():
    store = make_store({}, fail_status=500)
    with pytest.raises(RuntimeError) as exc:
        await store.put("a.pdf", b"x")
    assert "500" in str(exc.value)
    assert SERVICE_KEY not in str(exc.value)
    assert "a.pdf" not in str(exc.value)


async def test_invalid_keys_raise_value_error():
    store = make_store({})
    for key in ("../escape.pdf", "docs/../../escape.pdf", "/absolute.pdf"):
        with pytest.raises(ValueError, match="invalid blob key"):
            await store.put(key, b"x")


def test_build_blob_store_without_supabase_settings(tmp_path):
    settings = Settings(_env_file=None, blob_dir=tmp_path, supabase_url=None, supabase_service_key=None)
    assert isinstance(build_blob_store(settings), LocalBlobStore)


def test_build_blob_store_with_supabase_settings(tmp_path):
    settings = Settings(
        _env_file=None,
        blob_dir=tmp_path,
        supabase_url="https://project.supabase.co",
        supabase_service_key=SERVICE_KEY,
    )
    assert isinstance(build_blob_store(settings), SupabaseBlobStore)


async def test_put_percent_encodes_email_and_space():
    seen: list[httpx.Request] = []
    store = make_store({}, seen=seen)
    await store.put("user@x.com/a b.pdf", b"x")
    assert len(seen) == 1
    assert seen[0].url.raw_path.decode() == "/storage/v1/object/documents/user%40x.com/a%20b.pdf"


async def test_put_percent_encodes_literal_percent():
    seen: list[httpx.Request] = []
    store = make_store({}, seen=seen)
    await store.put("100%.pdf", b"x")
    assert len(seen) == 1
    assert seen[0].url.raw_path.decode() == "/storage/v1/object/documents/100%25.pdf"


async def test_put_then_get_with_special_characters():
    blobs: dict[str, bytes] = {}
    store = make_store(blobs)
    await store.put("a b/ü.pdf", b"payload")
    assert await store.get("a b/ü.pdf") == b"payload"


async def test_aclose_without_client_and_twice_with_client():
    store = SupabaseBlobStore("https://p.supabase.co", SERVICE_KEY)
    await store.aclose()
    assert store._http is None

    store = make_store({})
    await store.aclose()
    await store.aclose()
    assert store._http.is_closed is True


def test_lifespan_closes_blob_store(settings, fake_llm):
    app = create_app(_safe_settings(settings), llm=fake_llm)
    store = make_store({})
    app.state.documents.blobs = store
    client = store._http
    with TestClient(app):
        pass
    assert client.is_closed is True


def test_lifespan_with_local_blob_store(settings, fake_llm):
    with TestClient(create_app(_safe_settings(settings), llm=fake_llm)):
        pass


async def test_get_missing_object_400_no_such_key_raises_file_not_found():
    store = make_fixed_store(400, {"statusCode": "404", "error": "not_found", "code": "NoSuchKey"})
    with pytest.raises(FileNotFoundError):
        await store.get("a.pdf")


async def test_delete_missing_object_400_no_such_key_does_not_raise():
    store = make_fixed_store(400, {"statusCode": "404", "error": "not_found", "code": "NoSuchKey"})
    await store.delete("a.pdf")


async def test_missing_bucket_400_no_such_bucket_raises_runtime_error():
    store = make_fixed_store(400, {"statusCode": "404", "error": "Bucket not found", "code": "NoSuchBucket"})
    with pytest.raises(RuntimeError) as exc:
        await store.get("a.pdf")
    assert "400" in str(exc.value)
    assert "a.pdf" not in str(exc.value)
    assert "Bucket not found" not in str(exc.value)
    assert "NoSuchBucket" not in str(exc.value)
    assert SERVICE_KEY not in str(exc.value)
    with pytest.raises(RuntimeError) as exc:
        await store.delete("a.pdf")
    assert "400" in str(exc.value)
    assert "a.pdf" not in str(exc.value)
    assert "Bucket not found" not in str(exc.value)
    assert "NoSuchBucket" not in str(exc.value)
    assert SERVICE_KEY not in str(exc.value)


async def test_get_400_without_status_code_raises_runtime_error():
    store = make_fixed_store(400, {"message": "bad request"})
    with pytest.raises(RuntimeError):
        await store.get("a.pdf")


async def test_get_500_with_404_status_code_body_raises_runtime_error():
    store = make_fixed_store(500, {"statusCode": "404", "code": "NoSuchKey"})
    with pytest.raises(RuntimeError):
        await store.get("a.pdf")
