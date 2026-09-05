#!/usr/bin/env bash
set -euo pipefail
: "${PGHOST:?Isolated Postgres host required}"
if [ "$PGHOST" != "localhost" ] && [ "$PGHOST" != "127.0.0.1" ]; then
  echo 'This script only runs against the isolated local CI Postgres service.' >&2
  exit 1
fi
psql -X -v ON_ERROR_STOP=1 -f tests/bootstrap.sql
psql -X -v ON_ERROR_STOP=1 -f supabase/schema.sql
for migration in supabase/migrations/*.sql; do
  psql -X -v ON_ERROR_STOP=1 -f "$migration"
done
psql -X -v ON_ERROR_STOP=1 -f tests/messaging.sql
