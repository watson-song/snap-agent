#!/usr/bin/env bash
#
# E2E test for the snap-agent starter inside a real Spring Boot 2.x app,
# run directly on the host (java -jar). Equivalent to the Docker E2E when the
# Docker registry is unreachable. Builds the demo fat jar, starts it, curls
# every endpoint, asserts responses, then stops the app.
#
# Usage: ./e2e-local.sh
# Exit codes: 0 = all assertions passed; non-zero = failure.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="${ROOT_DIR}/snap-agent-demo"
HOST_PORT=18080
APP_PID=""

log() { printf '[e2e] %s\n' "$*"; }
fail() { printf '[e2e][FAIL] %s\n' "$*"; exit 1; }

cleanup() {
  if [ -n "${APP_PID}" ] && kill -0 "${APP_PID}" 2>/dev/null; then
    log "stopping app (pid ${APP_PID})"
    kill "${APP_PID}" >/dev/null 2>&1 || true
    wait "${APP_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# 1. Ensure the library is installed to the local Maven repo
log "installing snap-agent modules to local repo (skip tests)"
cd "${ROOT_DIR}"
mvn -q install -DskipTests -Djacoco.skip=true || fail "mvn install failed"

# 2. Build the demo fat jar
log "building demo fat jar"
cd "${DEMO_DIR}"
mvn -q clean package -DskipTests || fail "demo mvn package failed"

# 3. Start the app on a free port, with upload-skills-dir pointing at the source tree
log "starting demo app on port ${HOST_PORT}"
JAR="${DEMO_DIR}/target/snap-agent-demo-1.0.0-SNAPSHOT.jar"
SKILLS_DIR="${DEMO_DIR}/src/main/resources/skills"
java -jar "${JAR}" \
  --server.port="${HOST_PORT}" \
  --snap-agent.upload-skills-dir="${SKILLS_DIR}" \
  > /tmp/snap-agent-e2e.log 2>&1 &
APP_PID=$!

# 4. Wait for the app to be ready
log "waiting for app to be ready"
ready=0
for i in $(seq 1 60); do
  if curl -sf "http://localhost:${HOST_PORT}/snap-agent/skills" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    fail "app process died; see /tmp/snap-agent-e2e.log"
  fi
  sleep 1
done
[ "${ready}" -eq 1 ] || fail "app did not become ready in time (see /tmp/snap-agent-e2e.log)"

BASE="http://localhost:${HOST_PORT}"

# 5. Assertions
log "asserting GET /snap-agent/skills returns 200 with health-check skill"
body=$(curl -sf "${BASE}/snap-agent/skills")
echo "${body}" | grep -q '"name":"health-check"' || fail "health-check skill not in response: ${body}"
echo "${body}" | grep -q '"availability":"AVAILABLE"' || fail "skill not AVAILABLE: ${body}"

log "asserting GET /snap-agent/models returns 200 with default model"
body=$(curl -sf "${BASE}/snap-agent/models")
echo "${body}" | grep -q '"default":"claude-sonnet-4-6"' || fail "default model wrong: ${body}"

log "asserting GET /snap-agent/tools returns 200"
body=$(curl -sf "${BASE}/snap-agent/tools")
echo "${body}" | grep -q '"tools"' || fail "tools response malformed: ${body}"

log "asserting GET /snap-agent-internal/tasks/nope/probe returns 404 (with token)"
status=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "X-Skills-Agent-Internal-Token: e2e-internal-secret" \
  "${BASE}/snap-agent-internal/tasks/nope/probe")
[ "${status}" = "404" ] || fail "internal probe expected 404, got ${status}"

log "asserting GET /snap-agent-internal/tasks/nope/probe returns 401 (no token)"
status=$(curl -s -o /dev/null -w '%{http_code}' \
  "${BASE}/snap-agent-internal/tasks/nope/probe")
[ "${status}" = "401" ] || fail "internal probe without token expected 401, got ${status}"

log "asserting POST /snap-agent/runs returns 202 (basic auth)"
body=$(curl -sf -u demo:demo \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"health-check","inputs":{"message":"hello"}}' \
  "${BASE}/snap-agent/runs")
echo "${body}" | grep -q '"taskId"' || fail "POST /runs did not return taskId: ${body}"
echo "${body}" | grep -q '"streamUrl"' || fail "POST /runs did not return streamUrl: ${body}"
task_id=$(echo "${body}" | sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p')
log "created task: ${task_id}"

log "asserting GET /snap-agent/runs/{id} returns 200 (basic auth)"
status=$(curl -s -o /dev/null -w '%{http_code}' -u demo:demo \
  "${BASE}/snap-agent/runs/${task_id}")
[ "${status}" = "200" ] || fail "GET /runs/{id} expected 200, got ${status}"

log "asserting GET /snap-agent/runs/{id}/stream sends events (basic auth)"
stream_out=$(curl -s -u demo:demo --max-time 15 \
  "${BASE}/snap-agent/runs/${task_id}/stream" || true)
echo "${stream_out}" | grep -qE 'event:(done|error)' \
  || fail "stream did not emit done/error event: ${stream_out}"

log "asserting GET /snap-agent/runs/{id} returns 401 without auth"
status=$(curl -s -o /dev/null -w '%{http_code}' \
  "${BASE}/snap-agent/runs/${task_id}")
[ "${status}" = "401" ] || fail "GET /runs/{id} without auth expected 401, got ${status}"

log "ALL E2E ASSERTIONS PASSED"
