#!/usr/bin/env bash
# Stage 3 session-persistence gate.
#
# For one database, proves the compatibility claim end to end:
#   1. a 9.4 engine (sagait/engine:4.6.0-ubuntu) writes a session row
#   2. that session survives a 9.4 restart          -> the store works on 9.4
#   3. the SAME cookie authenticates on the Jetty 12 build   -> pre-upgrade rows readable
#   4. that session survives a Jetty 12 restart      -> the store works on 12
# Run once per cache mode (default / none).
#
# usage: session-gate.sh <db> <cachemode>     db = postgres|mysql|sqlserver|oracle
set -u

DB="${1:?db}"
CACHE="${2:-default}"
ENGINE94_IMAGE=sagait/engine:4.6.0-ubuntu
J12_SETUP="$HOME/src/oie-jetty12/server/setup"
JAVA_HOME="$(ls -d /usr/lib/jvm/bellsoft-java21* | head -1)"
NET=jetty12-sessgate
CJ="/tmp/sessgate-$DB-$CACHE.cookies"
E94_HTTPS=19443
J12_HTTPS=18443
XRW='X-Requested-With: OpenIntegrationEngine'

case "$DB" in
  postgres)
    DBC=sessgate-pg; DBIMG=postgres:16-alpine; DBPORT=5432
    DBENV=(-e POSTGRES_PASSWORD=mirthpw -e POSTGRES_USER=mirth -e POSTGRES_DB=mirthdb)
    READY="pg_isready -U mirth -d mirthdb"
    MIRTH_DB=postgres
    URL_DOCKER="jdbc:postgresql://$DBC:5432/mirthdb"
    URL_HOST="jdbc:postgresql://127.0.0.1:15433/mirthdb"
    PUBLISH=(-p 15433:5432)
    DBUSER=mirth; DBPASS=mirthpw; DRIVER=""
    ;;
  mysql)
    DBC=sessgate-mysql; DBIMG=mysql:8.4; DBPORT=3306
    DBENV=(-e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_USER=mirth -e MYSQL_PASSWORD=mirthpw -e MYSQL_DATABASE=mirthdb)
    READY="mysqladmin ping -h 127.0.0.1 -umirth -pmirthpw"
    MIRTH_DB=mysql
    # NO '&' — engine-docker's entrypoint substitutes DATABASE_URL with sed and escapes only
    # '/', so an unescaped '&' expands to the whole matched line and corrupts mirth.properties.
    # (Real bug in engine-docker; worked around here.) MySQL 8.4 + connector 8.4 negotiate TLS
    # by default, so caching_sha2_password works without allowPublicKeyRetrieval/useSSL.
    PARAMS="?allowMultiQueries=true"
    URL_DOCKER="jdbc:mysql://$DBC:3306/mirthdb$PARAMS"
    URL_HOST="jdbc:mysql://127.0.0.1:13307/mirthdb$PARAMS"
    PUBLISH=(-p 13307:3306)
    DBUSER=mirth; DBPASS=mirthpw; DRIVER=""
    ;;
  sqlserver)
    DBC=sessgate-mssql; DBIMG=mcr.microsoft.com/mssql/server:2022-latest; DBPORT=1433
    DBENV=(-e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=Str0ng!Passw0rd' -e MSSQL_PID=Developer)
    READY="/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P Str0ng!Passw0rd -C -Q 'SELECT 1'"
    MIRTH_DB=sqlserver
    SUFFIX=";databaseName=mirthdb;encrypt=false;trustServerCertificate=true"
    URL_DOCKER="jdbc:sqlserver://$DBC:1433$SUFFIX"
    URL_HOST="jdbc:sqlserver://127.0.0.1:11434$SUFFIX"
    PUBLISH=(-p 11434:1433)
    DBUSER=sa; DBPASS='Str0ng!Passw0rd'; DRIVER="com.microsoft.sqlserver.jdbc.SQLServerDriver"
    ;;
  oracle)
    DBC=sessgate-oracle; DBIMG=gvenzl/oracle-free:23-slim; DBPORT=1521
    DBENV=(-e ORACLE_PASSWORD=mirthpw -e APP_USER=mirth -e APP_USER_PASSWORD=mirthpw)
    READY="healthcheck.sh"
    MIRTH_DB=oracle
    URL_DOCKER="jdbc:oracle:thin:@//$DBC:1521/FREEPDB1"
    URL_HOST="jdbc:oracle:thin:@//127.0.0.1:11522/FREEPDB1"
    PUBLISH=(-p 11522:1521)
    DBUSER=mirth; DBPASS=mirthpw; DRIVER=""
    ;;
  *) echo "unknown db $DB"; exit 2 ;;
esac

say() { echo ""; echo "### $*"; }
fail() { echo "GATE FAIL [$DB/$CACHE]: $*"; exit 1; }

cleanup_engines() {
    docker rm -f sessgate-engine94 >/dev/null 2>&1
    pkill -u "$(id -u)" -f 'mirth-server-launche[r]' >/dev/null 2>&1
    sleep 2
}

say "[$DB/$CACHE] starting database container"
docker rm -f "$DBC" >/dev/null 2>&1
docker network create "$NET" >/dev/null 2>&1
docker run -d --name "$DBC" --network "$NET" "${PUBLISH[@]}" "${DBENV[@]}" "$DBIMG" >/dev/null || fail "db container did not start"

for i in $(seq 1 90); do
    if docker exec "$DBC" sh -c "$READY" >/dev/null 2>&1; then echo "database ready after ${i}0s"; break; fi
    if [ "$i" = 90 ]; then docker logs --tail 20 "$DBC"; fail "database never became ready"; fi
    sleep 10
done

# The mssql image has no MSSQL_DATABASE env — the database must be created by hand.
if [ "$DB" = sqlserver ]; then
    say "[$DB/$CACHE] creating mirthdb"
    docker exec "$DBC" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Str0ng!Passw0rd' -C \
        -Q "IF DB_ID('mirthdb') IS NULL CREATE DATABASE mirthdb" || fail "could not create mirthdb"
fi

say "[$DB/$CACHE] booting 9.4 engine against $MIRTH_DB"
cleanup_engines
docker run -d --name sessgate-engine94 --network "$NET" -p $E94_HTTPS:8443 \
    -e DATABASE="$MIRTH_DB" -e DATABASE_URL="$URL_DOCKER" \
    -e DATABASE_USERNAME="$DBUSER" -e DATABASE_PASSWORD="$DBPASS" \
    -e SESSION_STORE=true -e _MP_SERVER_API_SESSIONCACHE="$CACHE" \
    ${DRIVER:+-e _MP_DATABASE_DRIVER="$DRIVER"} \
    "$ENGINE94_IMAGE" >/dev/null || fail "9.4 engine container did not start"

for i in $(seq 1 60); do
    curl -sk -o /dev/null -m 2 "https://127.0.0.1:$E94_HTTPS/" 2>/dev/null && { echo "9.4 engine up"; break; }
    if [ "$i" = 60 ]; then docker logs --tail 30 sessgate-engine94; fail "9.4 engine never came up"; fi
    sleep 5
done

say "[$DB/$CACHE] logging in on 9.4 and capturing session cookie"
rm -f "$CJ"
code=$(curl -sk -c "$CJ" -o /dev/null -w '%{http_code}' -X POST "https://127.0.0.1:$E94_HTTPS/api/users/_login" -H "$XRW" -d 'username=admin&password=admin')
[ "$code" = 200 ] || fail "9.4 login returned $code"
grep -q JSESSIONID "$CJ" || fail "no JSESSIONID cookie issued"

say "[$DB/$CACHE] restarting 9.4 engine — session must survive (store works on 9.4)"
docker restart sessgate-engine94 >/dev/null
for i in $(seq 1 60); do curl -sk -o /dev/null -m 2 "https://127.0.0.1:$E94_HTTPS/" 2>/dev/null && break; sleep 5; done
code=$(curl -sk -b "$CJ" -o /dev/null -w '%{http_code}' "https://127.0.0.1:$E94_HTTPS/api/users/current" -H "$XRW")
[ "$code" = 200 ] || fail "session did not survive 9.4 restart (got $code) — store not working on 9.4"
echo "PASS: session survived 9.4 restart"

say "[$DB/$CACHE] stopping 9.4, booting Jetty 12 build against the same database"
docker rm -f sessgate-engine94 >/dev/null 2>&1
cd "$J12_SETUP" || fail "no jetty12 setup dir"
sed -i "s|^database = .*|database = $MIRTH_DB|; s|^database.url = .*|database.url = $URL_HOST|; s|^database.username = .*|database.username = $DBUSER|; s|^database.password = .*|database.password = $DBPASS|; s|^http.port = .*|http.port = 18080|; s|^https.port = .*|https.port = $J12_HTTPS|" conf/mirth.properties
grep -q '^server.api.sessionstore' conf/mirth.properties && sed -i "s|^server.api.sessionstore.*|server.api.sessionstore = true|" conf/mirth.properties || printf '\nserver.api.sessionstore = true\n' >> conf/mirth.properties
grep -q '^server.api.sessioncache' conf/mirth.properties && sed -i "s|^server.api.sessioncache.*|server.api.sessioncache = $CACHE|" conf/mirth.properties || printf 'server.api.sessioncache = %s\n' "$CACHE" >> conf/mirth.properties
# ALWAYS write the driver line, empty when the database needs no explicit driver — otherwise a
# previous run's driver (e.g. SQL Server's) lingers in this shared install and is handed to the
# next database.
if grep -q '^database.driver' conf/mirth.properties; then
    sed -i "s|^database.driver.*|database.driver = $DRIVER|" conf/mirth.properties
else
    printf 'database.driver = %s\n' "$DRIVER" >> conf/mirth.properties
fi

rm -f logs/mirth.log
( exec "$JAVA_HOME/bin/java" -jar mirth-server-launcher.jar > /tmp/sessgate-j12-$DB-$CACHE.log 2>&1 & )
for i in $(seq 1 60); do
    curl -sk -o /dev/null -m 2 "https://127.0.0.1:$J12_HTTPS/" 2>/dev/null && { echo "jetty 12 engine up"; break; }
    if [ "$i" = 60 ]; then tail -30 "/tmp/sessgate-j12-$DB-$CACHE.log"; fail "jetty 12 engine never came up"; fi
    sleep 5
done

say "[$DB/$CACHE] THE CLAIM: 9.4-written session read by Jetty 12"
code=$(curl -sk -b "$CJ" -o /dev/null -w '%{http_code}' "https://127.0.0.1:$J12_HTTPS/api/users/current" -H "$XRW")
[ "$code" = 200 ] || fail "pre-upgrade 9.4 session NOT readable by Jetty 12 (got $code)"
echo "PASS: 9.4-written session authenticates on Jetty 12"

say "[$DB/$CACHE] restarting Jetty 12 — session must survive (store works on 12)"
pkill -u "$(id -u)" -f 'mirth-server-launche[r]' >/dev/null 2>&1
sleep 3
( exec "$JAVA_HOME/bin/java" -jar mirth-server-launcher.jar > "/tmp/sessgate-j12-$DB-$CACHE-2.log" 2>&1 & )
for i in $(seq 1 60); do curl -sk -o /dev/null -m 2 "https://127.0.0.1:$J12_HTTPS/" 2>/dev/null && break; sleep 5; done
code=$(curl -sk -b "$CJ" -o /dev/null -w '%{http_code}' "https://127.0.0.1:$J12_HTTPS/api/users/current" -H "$XRW")
[ "$code" = 200 ] || fail "session did not survive Jetty 12 restart (got $code)"
echo "PASS: session survived Jetty 12 restart"

pkill -u "$(id -u)" -f 'mirth-server-launche[r]' >/dev/null 2>&1
echo ""
echo "GATE PASS [$DB/$CACHE]: 9.4 write -> 9.4 restart -> 12 read -> 12 restart"
