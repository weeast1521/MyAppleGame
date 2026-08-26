#!/usr/bin/env bash
# MySQL 논리 백업 (VM의 /opt/applegame 에서 실행) — 7일 보관
# cron 등록 (deploy 유저, 매일 04시):
#   0 4 * * * /opt/applegame/scripts/backup-mysql.sh >> /opt/applegame/backups/backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")/.."

BACKUP_DIR=backups
mkdir -p "$BACKUP_DIR"
STAMP=$(date +%F_%H%M)

# --single-transaction: InnoDB를 잠그지 않고 일관된 스냅샷으로 덤프 (서비스 영향 없음)
docker compose -f docker-compose.prod.yml exec -T mysql \
    sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines applegame' \
    | gzip > "$BACKUP_DIR/applegame_${STAMP}.sql.gz"

# 7일 지난 백업 삭제
find "$BACKUP_DIR" -name 'applegame_*.sql.gz' -mtime +7 -delete

echo "✔ 백업 완료: ${BACKUP_DIR}/applegame_${STAMP}.sql.gz"
