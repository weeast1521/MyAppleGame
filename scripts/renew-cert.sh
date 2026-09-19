#!/usr/bin/env bash
# Let's Encrypt 인증서 자동 갱신 (VM의 cron에서 하루 2회 실행 — INFRA.md §Phase 2)
#
# certbot renew는 만료 30일 전부터만 실제로 갱신한다. 그 전에는 아무것도 하지 않고 끝나므로
# 매일 여러 번 돌려도 낭비가 아니다. 하루 2회인 이유는 한 번 실패해도 재시도할 여유를 두기 위해서다.
set -euo pipefail
cd "$(dirname "$0")/.."

# cron의 PATH는 로그인 셸과 다르다 — docker를 전체 경로로 부르지 않으면
# command not found로 조용히 실패한다(출력도 안 보이므로 알아차리기 어렵다).
COMPOSE="/usr/bin/docker compose -f docker-compose.prod.yml"

echo "[$(date '+%F %T')] 갱신 확인"

# certbot은 상주 서비스가 아니라 profiles로 묶여 있다. run은 대상 서비스의 프로필을 자동으로 켜므로
# --profile을 따로 주지 않아도 된다.
#
# 갱신 방식은 /etc/letsencrypt/renewal/*.conf의 authenticator에 저장돼 있고 webroot여야 한다.
# standalone이면 certbot이 80포트에 임시 웹서버를 띄우려 하는데, 그 포트는 nginx가 쥐고 있고
# certbot 컨테이너에는 포트 매핑도 없어 CA의 챌린지 요청이 nginx에 도달해 404가 된다(09-20에 겪음).
# webroot 방식은 챌린지 파일을 certbot-webroot 볼륨에 쓰고 nginx가 그 경로를 서빙한다
# (nginx/default.conf의 location /.well-known/acme-challenge/ — 80→443 리다이렉트보다 먼저 매칭된다).
$COMPOSE run --rm certbot renew --quiet

# nginx는 기동할 때 인증서를 메모리에 올린다 — 파일이 바뀌어도 스스로 알지 못하므로 reload가 필요하다.
# 갱신이 없었어도(no-op) reload는 무해하다: 처리 중인 연결을 끊지 않는 graceful reload다.
# certbot의 --deploy-hook(갱신됐을 때만 실행)을 쓰지 않는 이유는 certbot이 컨테이너 안이라
# 호스트의 nginx 컨테이너를 부를 수단이 없기 때문이다.
$COMPOSE exec -T nginx nginx -s reload

# 만료일을 로그에 남긴다 — 갱신이 실제로 일어났는지 이전 실행과 비교할 수 있는 유일한 흔적이다.
EXPIRY=$($COMPOSE run --rm --entrypoint openssl certbot \
    x509 -enddate -noout -in /etc/letsencrypt/live/myapplegame.duckdns.org/fullchain.pem 2>/dev/null \
    | cut -d= -f2 || echo "확인 실패")
echo "[$(date '+%F %T')] 완료 — 만료일: ${EXPIRY}"
