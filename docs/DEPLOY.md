# Deploying the API (free tier)

The API is one container (`services/api/Dockerfile`, ~520 MB, full 成语 lexicon baked in). It is stateless: state lives in Postgres, blobs, and an optional Redis.

## Option A: Google Cloud Run (recommended)
```bash
gcloud run deploy monet-api --source services/api --region europe-west1 \
  --allow-unauthenticated --memory 1Gi --max-instances 2 --min-instances 0 \
  --set-env-vars MONET_ENV=prod,MONET_AUTH_MODE=supabase,MONET_LLM_PROVIDER=gemini \
  --set-secrets MONET_LLM_API_KEY=gemini-key:latest,MONET_SUPABASE_JWT_SECRET=supabase-jwt:latest,MONET_DATABASE_URL=db-url:latest
```
`--allow-unauthenticated` only opens the network door: every `/v1/*` route except `/v1/health` still requires a valid Supabase JWT.

Cold start is ~3 s (lexicon + jieba). Keep `--max-instances` low: the free LLM tier is the real limit.

## Postgres: Supabase
Create a project, copy the **session pooler** connection string and set it as `MONET_DATABASE_URL` using the `postgresql+asyncpg://` scheme. Migrations run automatically at start (`alembic upgrade head`). Enable pgvector only when a feature needs it.

## Auth: Supabase JWT
`MONET_AUTH_MODE=supabase` + `MONET_SUPABASE_JWT_SECRET` (Project settings → API → JWT secret). The Android app signs users in with Supabase Auth and sends the access token as `Authorization: Bearer …`.

## Blobs
`MONET_BLOB_DIR` is a local disk path (`/data/blobs`), which Cloud Run wipes between instances. Before production, add a Supabase Storage `BlobStore` (interface in `documents/store.py`) or mount a volume.

## LLM
Gemini free tier. A fallback chain (`MONET_LLM_FALLBACK_MODELS`) absorbs 429s; when every model is exhausted the API degrades to dictionary results with an empty `translation`. For more capacity, point `openai_compat` at Groq, DeepSeek or Qwen.

## Local smoke test of the image
```bash
docker build -t monet-api services/api
docker run --rm -p 8080:8080 -e MONET_ENV=dev -e MONET_AUTH_MODE=dev -e MONET_LLM_PROVIDER=fake monet-api
```
