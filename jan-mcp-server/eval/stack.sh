#!/usr/bin/env bash
# JAN-06 평가 스택: 평가 전용 MySQL·Redis 컨테이너 + 메인 앱 bootJar + MCP 서버 bootJar.
# 사용: stack.sh up | down
# key(서비스 계정·MCP 클라이언트·JWT)는 up 마다 무작위로 만들어 .runtime/ 에만 둔다(git 제외). down 이 .runtime 을 지운다.
set -euo pipefail

EVAL="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$EVAL/../.." && pwd)"
RT="$EVAL/.runtime"
MYSQL=jan06-eval-mysql
REDIS=jan06-eval-redis
MYSQL_PORT=13306
REDIS_PORT=16379
MAIN_PORT=18080
MCP_PORT=18090

# 새 session 으로 띄운다: 이 스크립트를 부른 셸·도구가 끝나거나 프로세스 그룹을 정리해도 서버가 남고, 호출자가 서버 출력을 기다리지 않는다
detach() {
  python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' "$@"
}

wait_for() {  # 설명, 최대 초, 명령...
  local what=$1 limit=$2; shift 2
  for _ in $(seq "$limit"); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "[stack] $what 대기 시간 초과" >&2
  return 1
}

up() {
  mkdir -p "$RT"
  docker run -d --name "$MYSQL" -e MYSQL_DATABASE=stagelog -e MYSQL_USER=stagelog -e MYSQL_PASSWORD=stagelog \
    -e MYSQL_ROOT_PASSWORD=root -e TZ=UTC -p "127.0.0.1:$MYSQL_PORT:3306" -v "$ROOT/db/schema:/docker-entrypoint-initdb.d:ro" \
    mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci >/dev/null
  docker run -d --name "$REDIS" -p "127.0.0.1:$REDIS_PORT:6379" redis:7-alpine >/dev/null
  # initdb 가 끝나고(fresh@dev 행) TCP 로 받는 최종 서버가 떠야 준비된 것이다
  wait_for "MySQL 초기화" 180 sh -c "docker exec $MYSQL mysql -h127.0.0.1 -ustagelog -pstagelog stagelog -N -e \
    \"SELECT 1 FROM schema_history WHERE filename='fresh@dev'\" 2>/dev/null | grep -q 1"

  local main_key client_key jwt
  main_key=$(openssl rand -hex 24)
  client_key=$(openssl rand -hex 24)
  jwt=$(openssl rand -hex 32)
  docker exec -i "$MYSQL" mysql -ustagelog -pstagelog stagelog 2>/dev/null <<SQL
INSERT INTO service_accounts (name, key_hash, roles, created_at, updated_at)
VALUES ('jan-mcp-eval', SHA2('$main_key', 256), '["MCP_READER","MCP_REQUESTER"]', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SQL

  (cd "$ROOT" && ./gradlew -q bootJar :jan-mcp-server:bootJar)
  local main_jar mcp_jar
  main_jar=$(ls "$ROOT"/build/libs/*.jar | grep -v -- '-plain' | head -1)
  mcp_jar=$(ls "$ROOT"/jan-mcp-server/build/libs/*.jar | grep -v -- '-plain' | head -1)

  # 동기화·푸시·정리·Quota 리셋 스케줄은 끈다. 고정 시각 스케줄(04·05·09시 KST)은 그 시각을 피해 실행한다
  (cd "$RT" && detach java -Xmx768m -jar "$main_jar" --spring.profiles.active=local --server.port=$MAIN_PORT \
    "--spring.datasource.url=jdbc:mysql://127.0.0.1:$MYSQL_PORT/stagelog?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true" \
    --spring.data.redis.port=$REDIS_PORT --stagelog.jwt.secret="$jwt" \
    "--stagelog.sync.cron.naver=-" "--stagelog.sync.cron.kopis=-" "--stagelog.sync.cron.ticketmaster=-" "--stagelog.sync.cron.setlistfm=-" \
    "--stagelog.push.dispatch-cron=-" "--stagelog.push.resync-cron=-" "--jan.storage.cleanup-cron=-" "--jan.youtube.quota.reset-cron=-" \
    < /dev/null > "$RT/main.log" 2>&1 & echo $! > "$RT/main.pid")
  wait_for "메인 앱" 180 curl -sf "http://127.0.0.1:$MAIN_PORT/actuator/health"

  (cd "$RT" && JAN_MCP_PORT=$MCP_PORT JAN_MCP_MAIN_BASE_URL="http://127.0.0.1:$MAIN_PORT" JAN_MCP_MAIN_API_KEY="$main_key" \
    JAN_MCP_CLIENT_KEYS="eval:$(printf %s "$client_key" | shasum -a 256 | cut -d' ' -f1)" \
    detach java -Xmx384m -jar "$mcp_jar" < /dev/null > "$RT/mcp.log" 2>&1 & echo $! > "$RT/mcp.pid")
  # /mcp 는 인증 없이 401 을 주므로 401 이면 떠 있는 것이다
  wait_for "MCP 서버" 120 sh -c "curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:$MCP_PORT/mcp | grep -q 401"

  cat > "$RT/mcp.json" <<JSON
{"mcpServers":{"jan":{"type":"http","url":"http://127.0.0.1:$MCP_PORT/mcp","headers":{"Authorization":"Bearer $client_key"}}}}
JSON
  printf %s "$main_key" > "$RT/main.key"
  chmod 600 "$RT/mcp.json" "$RT/main.key"
  cat > "$RT/env.json" <<JSON
{"mysqlContainer":"$MYSQL","mainUrl":"http://127.0.0.1:$MAIN_PORT","mcpUrl":"http://127.0.0.1:$MCP_PORT/mcp","mcpConfig":"$RT/mcp.json"}
JSON
  echo "[stack] 준비 완료: main=$MAIN_PORT mcp=$MCP_PORT mysql=$MYSQL_PORT"
}

down() {
  for pid in "$RT/main.pid" "$RT/mcp.pid"; do
    [ -f "$pid" ] && kill "$(cat "$pid")" 2>/dev/null || true
  done
  docker rm -f "$MYSQL" "$REDIS" >/dev/null 2>&1 || true
  rm -rf "$RT"
  echo "[stack] 정리 완료"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) echo "사용: $0 up|down" >&2; exit 2 ;;
esac
