#!/usr/bin/env bash
# blue-green 무중단 배포 (VM의 /opt/applegame 에서 실행)
#   사용법: scripts/deploy.sh <이미지태그>
#
# 순서: 새 이미지를 유휴 색으로 기동 → 헬스 통과 대기 → nginx 전환(reload)
#       → 이전 색 정지. 헬스가 끝내 안 뜨면 새 색만 정리하고 실패 종료 —
#       기존 색이 계속 서빙 중이므로 실패해도 다운타임은 없다.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:?사용법: scripts/deploy.sh <이미지태그>}"
COMPOSE="docker compose -f docker-compose.prod.yml"

# 현재 활성 색은 nginx include 파일이 유일한 진실 원천.
# 이 파일은 git이 추적하지 않는 VM 로컬 상태 — 최초 배포처럼 없으면 템플릿에서 만든다(기본 blue)
INC=nginx/active_upstream.inc
[ -f "$INC" ] || cp "${INC}.example" "$INC"
ACTIVE=$(grep -oE 'app-(blue|green)' "$INC" | head -1)
if [ "$ACTIVE" = "app-blue" ]; then OLD=blue NEW=green; else OLD=green NEW=blue; fi
echo "▶ ${OLD}(활성) → ${NEW}(신규) 배포: 태그 ${TAG}"

# .env의 APP_TAG 갱신 — 이후의 어떤 up/restart도 같은 태그를 쓰게 한다
if grep -q '^APP_TAG=' .env; then
    sed -i "s|^APP_TAG=.*|APP_TAG=${TAG}|" .env
else
    echo "APP_TAG=${TAG}" >> .env
fi

$COMPOSE --profile "$NEW" pull "app-${NEW}"
$COMPOSE --profile "$NEW" up -d "app-${NEW}"

# 헬스 대기 (최대 120초) — JRE 이미지에 curl이 없으므로 nginx(알파인)의 wget으로
# 내부망에서 확인한다
echo "▶ app-${NEW} 헬스 체크 대기"
healthy=0
for _ in $(seq 1 60); do
    if $COMPOSE exec -T nginx wget -qO- "http://app-${NEW}:8080/actuator/health" 2>/dev/null | grep -q '"UP"'; then
        healthy=1
        break
    fi
    sleep 2
done

if [ "$healthy" -ne 1 ]; then
    echo "✖ app-${NEW} 헬스 체크 실패 — 전환하지 않고 새 색을 정리합니다 (기존 ${OLD}가 계속 서빙 중)"
    $COMPOSE logs --tail 50 "app-${NEW}" || true
    $COMPOSE --profile "$NEW" stop "app-${NEW}"
    exit 1
fi

# 트래픽 전환: include 파일 교체 후 reload — reload는 처리 중인 연결을 끊지 않는다
sed -i "s/app-${OLD}/app-${NEW}/" "$INC"
$COMPOSE exec -T nginx nginx -s reload
echo "▶ nginx 전환 완료 → app-${NEW}"

# 이전 색 정지 (이때 붙어 있던 WebSocket은 끊긴다 — 클라이언트가 재접속으로 복구)
$COMPOSE --profile "$OLD" stop "app-${OLD}"
echo "✔ 배포 완료: app-${NEW} (${TAG})"
