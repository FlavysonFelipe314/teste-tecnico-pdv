#!/usr/bin/env bash
# Full H01-H05 validation; uses isolated containers/database and preserves the working lab.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p docs/evidencias
mysql_id=$(docker compose ps -q mysql)
[[ -n "$mysql_id" ]] || { echo 'Suba o laboratório primeiro: docker compose up --build -d' >&2; exit 1; }
network=$(docker inspect -f '{{range $name,$config := .NetworkSettings.Networks}}{{$name}}{{end}}' "$mysql_id")
for name in rota-clara-validation-core rota-clara-validation-fiscal rota-clara-validation-minio rota-clara-validation-redis; do
  if docker container inspect "$name" >/dev/null 2>&1; then
    echo "Container $name já existe; recusando interferir em outra execução." >&2
    exit 1
  fi
done
existing=$(docker compose exec -T mysql mysql -uroot -plab-only-root -N -e \
  "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='rota_clara_validation'")
[[ -z "$existing" ]] || { echo 'O banco rota_clara_validation já existe; recusando sobrescrever.' >&2; exit 1; }

if [[ "${1:-}" != "--skip-unit" ]]; then
  docker compose build core admin > docs/evidencias/build.log 2>&1
  bash scripts/test-replenishment-mysql.sh --docker > docs/evidencias/testes-mysql.log 2>&1
else
  echo 'Usando imagens já compiladas e testes já executados.' >&2
fi
containers=()
created_db=false
cleanup() {
  status=$?
  if [[ $status -ne 0 ]]; then
    docker logs --tail 100 rota-clara-validation-core >&2 2>/dev/null || true
  fi
  for container in "${containers[@]}"; do docker rm -f "$container" >/dev/null || true; done
  if [[ "$created_db" == true ]]; then
    docker compose exec -T mysql mysql -uroot -plab-only-root -e 'DROP DATABASE rota_clara_validation;' || true
  fi
  return "$status"
}
trap cleanup EXIT

docker compose exec -T mysql mysql -uroot -plab-only-root -e \
  "CREATE DATABASE rota_clara_validation CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON rota_clara_validation.* TO 'rota_clara'@'%';"
created_db=true
for service in redis minio fiscal-simulado; do
  if [[ "$service" == "redis" ]]; then
    image=$(docker compose config --images | sed -n '/^redis:/p')
  else
    image="rota-clara-lab-$service"
  fi
  docker image inspect "$image" >/dev/null 2>&1 || {
    echo "Imagem de $service ausente. Execute docker compose build e suba o laboratório." >&2
    exit 1
  }
  case "$service" in
    redis) name=rota-clara-validation-redis; docker run -d --name "$name" --network "$network" "$image" >/dev/null ;;
    minio) name=rota-clara-validation-minio; docker run -d --name "$name" --network "$network" \
      -e MINIO_ROOT_USER=rota_clara -e MINIO_ROOT_PASSWORD=lab-only-password "$image" server /data >/dev/null ;;
    fiscal-simulado) name=rota-clara-validation-fiscal; docker run -d --name "$name" --network "$network" "$image" >/dev/null ;;
  esac
  containers+=("$name")
done
docker run -d --name rota-clara-validation-core --network "$network" -p 127.0.0.1:18081:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/rota_clara_validation \
  -e SPRING_DATA_REDIS_HOST=rota-clara-validation-redis \
  -e LAB_FISCAL_URL=http://rota-clara-validation-fiscal:8090 \
  -e LAB_S3_ENDPOINT=http://rota-clara-validation-minio:9000 \
  -e LAB_S3_BUCKET=rota-clara-validation-documents \
  -e LAB_NEEDS_NAMESPACE="rota-clara-validation-$$:needs:v1" \
  -e LAB_DOCUMENTS_MAX_ATTEMPTS=3 -e LAB_DOCUMENTS_RETRY_SECONDS=1 \
  -e EUREKA_CLIENT_ENABLED=false rota-clara-lab-core >/dev/null
containers+=(rota-clara-validation-core)
phase() {
  docker exec -i -e CORE_URL=http://rota-clara-validation-core:8080 \
    -e MINIO_URL=http://rota-clara-validation-minio:9000 \
    rota-clara-validation-fiscal python - "$1" < scripts/integration_all.py
}
phase initial
# Interrupt after the provider authorizes, before XML storage; the intent and protocol survive restart.
docker stop rota-clara-validation-minio >/dev/null
phase authorized_without_xml
docker stop rota-clara-validation-core >/dev/null
docker start rota-clara-validation-minio >/dev/null
docker start rota-clara-validation-core >/dev/null
phase after_restart
docker stop rota-clara-validation-redis >/dev/null
phase redis_down
docker start rota-clara-validation-redis >/dev/null
phase redis_up > docs/evidencias/h01-h05-integracao.json
# Count the provider's durable records to independently verify one record per logical document.
docker exec rota-clara-validation-fiscal python -c \
  "import sqlite3; c=sqlite3.connect('/data/fiscal.db'); n=c.execute('SELECT count(*) FROM documents').fetchone()[0]; assert n==4,n; print('Provedor: 4 documentos únicos para 4 pedidos')"
cat docs/evidencias/h01-h05-integracao.json
echo 'VALIDAÇÃO H01–H05 OK; recursos de teste serão removidos.' >&2
