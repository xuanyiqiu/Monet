# A-10: Supabase Storage BlobStore (EXAMPLE of a spec that worked first time)

**Branch:** feat/a10-supabase-blobstore
**Goal:** Add `SupabaseBlobStore`, an HTTP-backed implementation of the `BlobStore` protocol, selectable by config, so uploaded documents survive Cloud Run restarts.

## Context (read these first)
- `services/api/src/monet_api/documents/store.py`: `BlobStore` Protocol (`put`, `get`, `delete`, all async) and `LocalBlobStore` as the reference behaviour.
- `services/api/src/monet_api/config.py`: Settings class (pydantic; env prefix is already used for existing fields).
- `services/api/src/monet_api/main.py` lines ~84-90: where `LocalBlobStore(settings.blob_dir)` is constructed.
- `httpx` is already a dependency. Do NOT add new dependencies.

## Files to edit
- `services/api/src/monet_api/documents/store.py`: add `SupabaseBlobStore` (keep `LocalBlobStore` untouched).
- `services/api/src/monet_api/config.py`: add three optional settings: `supabase_url: str | None`, `supabase_service_key: str | None`, `supabase_bucket: str = "documents"`.
- `services/api/src/monet_api/main.py`: add a small `build_blob_store(settings)` helper and use it in place of the direct `LocalBlobStore(...)` call.
- `services/api/tests/test_supabase_blobstore.py`: NEW test file.

## Do not touch
Everything else, especially `contracts/openapi.yaml`, any `.env` file, migrations, and other tests.

## Behaviour (decided by planner)
`SupabaseBlobStore(base_url, service_key, bucket, client: httpx.AsyncClient | None = None)`:
- Endpoint for a key: `{base_url}/storage/v1/object/{bucket}/{key}` (strip a trailing `/` from base_url).
- Every request sends `Authorization: Bearer <service_key>` and `apikey: <service_key>`.
- `put`: `POST` the bytes with header `x-upsert: true` and `Content-Type: application/octet-stream`. Any non-2xx raises `RuntimeError` including status code (never include the key or the service key in the message).
- `get`: `GET`; 200 returns `response.content`; 404 raises `FileNotFoundError` (same as `LocalBlobStore`); other non-2xx raises `RuntimeError`.
- `delete`: `DELETE`; 200 and 404 are both fine (idempotent, like `unlink(missing_ok=True)`); other non-2xx raises `RuntimeError`.
- Validate the key like `LocalBlobStore` does: reject keys containing `..` or starting with `/` with `ValueError("invalid blob key")`.
- If no client is injected, create one lazily with `timeout=httpx.Timeout(20.0)`. Add an `aclose()` method.

`build_blob_store(settings)` in `main.py`: return `SupabaseBlobStore` when BOTH `supabase_url` and `supabase_service_key` are set, otherwise `LocalBlobStore(settings.blob_dir)`.

## Tests (use `httpx.MockTransport`, no network, no real server)
1. put then get round-trip returns the same bytes (mock keeps a dict).
2. put sends the Authorization header, `x-upsert: true`, and the right URL.
3. get of a missing key raises `FileNotFoundError`.
4. delete of an existing key then get raises `FileNotFoundError`; delete of a missing key does not raise.
5. A 500 on put raises `RuntimeError` and the message does not contain the service key.
6. Keys with `..` or a leading `/` raise `ValueError`.
7. `build_blob_store` returns `LocalBlobStore` with no Supabase settings and `SupabaseBlobStore` with both set.

## Acceptance
- [ ] `cd services/api && ../../.tools/bin/uv.exe run pytest -q` green (all existing tests still pass)
- [ ] `cd services/api && ../../.tools/bin/uv.exe run ruff check .` green
- [ ] Commit on the current branch: `feat(api): Supabase Storage blob store`

## Worker report
<!-- worker appends here -->
