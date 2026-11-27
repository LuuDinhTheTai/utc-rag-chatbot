#!/bin/sh
# Migration 010: set the utc_worker password from POSTGRES_WORKER_PASSWORD.
#
# Runs automatically ONLY when the PostgreSQL volume is initialised for the first
# time (docker-entrypoint-initdb.d). For an existing volume run instead:
#   docker compose exec -T database sh -c \
#     'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v pw="$POSTGRES_WORKER_PASSWORD" \
#      -c "ALTER ROLE utc_worker WITH PASSWORD :'"'"'pw'"'"'"'
set -eu

if [ -z "${POSTGRES_WORKER_PASSWORD:-}" ]; then
    echo "010-worker-password: POSTGRES_WORKER_PASSWORD is not set; utc_worker cannot log in until a password is set." >&2
    exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v pw="$POSTGRES_WORKER_PASSWORD" <<'EOSQL'
ALTER ROLE utc_worker WITH LOGIN PASSWORD :'pw';
EOSQL
echo "010-worker-password: password set for utc_worker"

