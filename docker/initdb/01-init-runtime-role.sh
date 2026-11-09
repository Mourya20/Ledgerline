#!/bin/sh
set -eu

# ==============================================================================
# Fresh Installation Provisioning Script for LedgerLine PostgreSQL
# Executed automatically by PostgreSQL container entrypoint on an empty volume.
# ==============================================================================

# Fail immediately if APP_DB_PASSWORD is absent or empty. Never silently skip.
if [ -z "${APP_DB_PASSWORD:-}" ]; then
    echo "FATAL: APP_DB_PASSWORD environment variable is absent or empty. Cannot initialize ledgerline_runtime role securely." >&2
    exit 1
fi

# 1. Pre-provision the application runtime role with APP_DB_PASSWORD.
#    Uses psql \getenv and format(%L, :'runtime_pwd') with \gexec to safely escape
#    passwords containing single quotes, dollar signs, and special characters without
#    raw SQL interpolation or exposing secrets in command-line arguments.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" << 'EOSQL'
\getenv runtime_pwd APP_DB_PASSWORD
SELECT format('CREATE ROLE ledgerline_runtime WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', :'runtime_pwd')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ledgerline_runtime')\gexec
EOSQL

# 2. Pre-provision the isolated test database for integration test suites.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" << 'EOSQL'
SELECT 'CREATE DATABASE ledgerline_test_db OWNER ledgerline_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ledgerline_test_db')\gexec
EOSQL
