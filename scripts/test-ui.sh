#!/usr/bin/env bash
# Linux/Docker; deterministic browser fixtures do not modify the lab's real data.
set -euo pipefail
cd "$(dirname "$0")/.."
docker build -t rota-clara-ui-tests - <<'DOCKERFILE'
FROM node:22.14.0-alpine@sha256:9bef0ef1e268f60627da9ba7d7605e8831d5b56ad07487d24d1aa386336d1944
WORKDIR /work
RUN apk add --no-cache chromium && npm install playwright-core@1.61.1
ENTRYPOINT ["node","/work/ui-regression.cjs"]
DOCKERFILE
docker run --rm --init --memory=768m --network host -v "$PWD/scripts/ui-regression.cjs:/work/ui-regression.cjs:ro" \
  -e ADMIN_URL="${ADMIN_URL:-http://127.0.0.1:13000}" rota-clara-ui-tests
