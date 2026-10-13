#!/usr/bin/env bash
# H01-H05 against the Compose MySQL, in an isolated, temporary database.
set -euo pipefail
cd "$(dirname "$0")/.."
existing=$(docker compose exec -T mysql mysql -uroot -plab-only-root -N -e \
  "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='rota_clara_test'")
if [[ -n "$existing" ]]; then
  echo 'O banco rota_clara_test já existe. Recusando apagar dados de outra execução.' >&2
  exit 1
fi
docker compose exec -T mysql mysql -uroot -plab-only-root -e \
  "CREATE DATABASE rota_clara_test; GRANT ALL PRIVILEGES ON rota_clara_test.* TO 'rota_clara'@'%';"
cleanup() {
  docker compose exec -T mysql mysql -uroot -plab-only-root -e 'DROP DATABASE rota_clara_test;'
}
trap cleanup EXIT
export REPLENISHMENT_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/rota_clara_test'
export REPLENISHMENT_TEST_MYSQL_USER='rota_clara'
export REPLENISHMENT_TEST_MYSQL_PASSWORD='lab-only-password'
if [[ "${1:-}" == "--docker" ]]; then
  shift
  docker build --target build -t rota-clara-replenishment-tests core
  docker run --rm --network host \
    --memory=1g -e MAVEN_OPTS=-Xmx384m \
    -e REPLENISHMENT_TEST_MYSQL_URL \
    -e REPLENISHMENT_TEST_MYSQL_USER \
    -e REPLENISHMENT_TEST_MYSQL_PASSWORD \
    rota-clara-replenishment-tests mvn -B -DargLine=-Xmx384m test "$@"
else
  mvn -B -f core/pom.xml test "$@"
fi
