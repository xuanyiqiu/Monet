# Monet API image. Build from this directory:  docker build -t monet-api services/api
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim AS build
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
COPY data ./data
COPY scripts ./scripts
COPY migrations ./migrations
COPY alembic.ini ./
RUN uv sync --frozen --no-dev \
    && uv run --no-dev python scripts/fetch_data.py   # bake the full 成语 lexicon into the image

FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PATH="/app/.venv/bin:$PATH" \
    MONET_ENV=prod MONET_AUTH_MODE=supabase MONET_BLOB_DIR=/data/blobs MONET_CACHE_DIR=/tmp/monet-cache
WORKDIR /app
RUN useradd --system --uid 10001 --home /app monet && mkdir -p /data/blobs && chown -R monet /data
COPY --from=build --chown=monet /app /app
USER monet
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD python -c "import urllib.request,os;urllib.request.urlopen('http://127.0.0.1:%s/v1/health'%os.environ.get('PORT','8080'),timeout=4)"
# Cloud Run injects $PORT. One worker: the lexicon/jieba are loaded per process (~1 s, ~200 MB).
CMD ["sh", "-c", "exec uvicorn monet_api.main:create_app --factory --host 0.0.0.0 --port ${PORT:-8080}"]
