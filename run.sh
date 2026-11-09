#!/bin/bash
set -e
cd "$(dirname "$0")"

set -a
source ./.env
set +a

exec java -jar target/panchayat-backend-0.0.1-SNAPSHOT.jar
