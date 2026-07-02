#!/usr/bin/env bash
#
# E2E test for the snap-agent starter inside a real Spring Boot 2.x app
# running in Docker. Builds the demo fat jar, builds the image, runs the
# container, curls every endpoint, asserts responses, then tears down.
#
# Usage: ./e2e-docker.sh
# Exit codes: 0 = all assertions passed; non-zero = failure.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="${ROOT_DIR}/snap-agent-demo"
IMAGE_TAG="snap-agent-demo:e2e"
CONTAINER_NAME="snap-agent-e2e"
HOST_PORT=18080

log() { printf '[e2e] %s\n' "$*"; }
fail() { printf '[e2e][FAIL] %s\n' "$*"; exit 1; }

cleanup() {
  log "cleaning up container ${CONTAINER_NAME}"
  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
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

# 3. Build the Docker image
log "building docker image ${IMAGE_TAG}"
docker build -t "${IMAGE_TAG}" . || fail "docker build failed"

# 4. Run the container
log "starting container ${CONTAINER_NAME} on port ${HOST_PORT}"
docker run -d --rm --name "${CONTAINER_NAME}" -p "${HOST_PORT}:8080" "${IMAGE_TAG}" >/dev/null \
  || fail "docker run failed"

# 5. Wait for the app to be ready (poll the skills endpoint)
log "waiting for app to be ready"
ready=0
for i in $(seq 1 40); do
  if curl -sf "http://localhost:${HOST_PORT}/snap-agent/skills" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
[ "${ready}" -eq 1 ] || fail "app did not become ready in time"

BASE="http://localhost:${HOST_PORT}"

# 6. Assertions
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
# Stream should eventually emit a done or error event (LLM call fails with dummy key)
stream_out=$(curl -s -u demo:demo --max-time 15 \
  "${BASE}/snap-agent/runs/${task_id}/stream" || true)
echo "${stream_out}" | grep -qE 'event:(done|error)' \
  || fail "stream did not emit done/error event: ${stream_out}"

log "asserting GET /snap-agent/runs/{id} returns 401 without auth"
status=$(curl -s -o /dev/null -w '%{http_code}' \
  "${BASE}/snap-agent/runs/${task_id}")
[ "${status}" = "401" ] || fail "GET /runs/{id} without auth expected 401, got ${status}"

log "ALL E2E ASSERTIONS PASSED"
