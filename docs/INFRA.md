# 인프라 설계서 (Infrastructure Decision Record)

> 2026-08-26 작성 · 08-27 미결 확정 · 09-01 VM 실사 반영 · **09-02 Phase 1 완료 + 트러블슈팅 3건 반영**
>
> 인터뷰로 확정한 결정과, 그 과정에서 드러난 암묵지(당연하다고 여겨 말하지 않았던 전제)를 기록한다.
> 결정이 바뀌면 이 문서를 먼저 고친다. 상세 런북·기초 설명(부록 C)은
> [설계서 아티팩트](https://claude.ai/code/artifact/fa739c72-e90d-4e8d-8e3c-f68ce15ede3d) 참고.

| 항목 | 값 |
|---|---|
| 자원 | NCP VM 1대 (제공처 관리 · 콘솔 권한 없음) |
| OS | Rocky 8.8 · 2 vCPU · 15 GB RAM · swap 4 GB · 디스크 99 GB |
| IP | 외부 223.130.152.28 · 내부 192.168.0.75 |
| 앱 | Spring Boot 3.4 · MySQL 8.4 · Redis 7 · STOMP(SockJS) |
| 상태 | **Phase 1 완료 (09-02)** · **Phase 2(HTTPS) 완료 (09-20)** · **도메인 이전 완료 (10-06, #60)** — https://fruitboxduel.com 서빙, main 머지 = 자동 배포 |

---

## 0. 한 장 요약 (Phase 1 기준)

Nginx만 바깥에 서고 나머지는 Docker 내부 네트워크에만 존재한다.

```
                    Internet
                       │
        NCP ACG (제공처 관리 · 변경 불가): 22 · 80 · 443 · 3000 열림
                       │
        VM 안 방어선: firewalld(22/80만) + sshd 키 인증만 + fail2ban
                       │
┌────────── VM · Docker Compose · /opt/applegame ──────────────────┐
│  nginx :80 ── 유일한 진입점                                        │
│    ├─ /        → $active_app:8080 (app-blue | app-green)          │
│    ├─ /ws      → 〃 (Upgrade 헤더 · timeout 3600s · Host 재선언)   │
│    ├─ /grafana → grafana:3000 (자체 로그인)                       │
│    └─ /actuator→ 403 (외부 차단)                                  │
│  app :8080 (JVM -Xmx2g) ── mysql:8.4 :3306 ── redis:7 :6379       │
│  prometheus :9090 ←(app /actuator/prometheus · node-exporter)     │
│         └→ grafana                                                │
│  ※ DB·Redis 등은 ports: 미사용 — Docker 내부 네트워크 전용        │
└───────────────────────────────────────────────────────────────────┘
     ▲ ssh: git pull → scripts/deploy.sh <sha> (blue-green 전환)
GitHub Actions: test → build → push GHCR ghcr.io/<owner>/fruitboxduel:<sha>
```

## 1. 확정한 결정 (D1~D11)

D-번호는 이후 문서·PR에서 참조하는 식별자.

| # | 항목 | 결정 | 근거 / 대안 |
|---|---|---|---|
| D1 | 클라우드 자원 | **NCP VM 1대가 전부** | 매니지드 DB/Redis 없음 → 전부 컨테이너로 VM 안에서 운영 |
| D2 | VM 스펙 | 2 vCPU / 15 GB / swap 4 GB | RAM 여유, CPU 병목. 메모리 예산은 §3 |
| D3 | 도메인/TLS | 1차: 공인 IP + http. Phase 2: DuckDNS + Let's Encrypt, nginx가 TLS 종료. **10-04: 자체 도메인 fruitboxduel.com(Cloudflare Registrar, DNS only)으로 이전** | wss는 SockJS 상대경로라 자동. 자바·프론트 코드 무변경 — nginx/compose만. 이전 사유는 §9-3 |
| D4 | 3000 포트 | ACG에서는 못 닫는다 → Grafana를 publish하지 않아 listen 없음 + firewalld 차단으로 **실질 폐쇄** | Grafana는 `/grafana` 경로로만 |
| D5 | 배포 방식 | GitHub Actions → GHCR 이미지 → SSH → blue-green 전환 | 로컬 compose와 구조 일치 |
| D6 | 인프라 목적 | 데모 배포 + 모니터링 학습 + 부하 테스트 + 다중 인스턴스 실험 | "단순하되 확장 지점을 열어두는" 구조 |
| D7 | 다중 인스턴스 브로커 | 실험 단계(Phase 4)에서 도입. **그 전까지 pub/sub 도입 안 함** — 단일 인스턴스는 SimpleBroker로 충분 | 현재 Redis의 존재 이유는 pub/sub이 아니라 Lua 원자 연산·랭킹 ZSET·재배포에도 살아남는 방/세션 상태 |
| D8 | 부하 테스트 위치 | 로컬 PC→VM, VM 내부 둘 다 | 각각의 왜곡 요인을 알고 쓴다 (§4 Phase 5) |
| D9 | 모니터링 노출 | Nginx `/grafana` 경로 공개, Grafana 자체 로그인 | 80/443만 사용 |
| D10 | 프론트엔드 | Spring 정적 리소스 서빙 유지 | 별도 프론트 서버 없음. React 분리 시나리오는 부록 A(아티팩트) |
| D11 | 작업 환경 | **NCP 콘솔 없음.** VM 1대 + 웹 터미널(root) + 노출포트 22·443·80·3000 고정 | ACG 등 콘솔 작업 전부 불가 → 보안은 VM 안에서. SSAFY 망은 나가는 22·3000 차단 → SSAFY에서는 웹 터미널, 집에서는 맥 ssh |

## 2. 드러난 암묵지 (요지)

"당연히 되겠지"였지만 실제로는 결정·설정이 필요했던 것들. 전문은 아티팩트 §2.

**네트워크·보안**
- ACG는 우리가 관리 못 한다(D11) → sshd 키 인증만 + root 금지, firewalld, fail2ban으로 VM 안에서 대체.
- Docker가 포트를 publish하면 firewalld를 **우회**한다 → DB/Redis에 `ports:`를 아예 안 쓰는 것이 실제 방어선.
- 22가 열려 있으면 봇이 온다 — 실사 시 로그인 실패 누적 12만 회. 키 인증만 허용되면 무차별 대입은 실질 위협 아님.
- Nginx 뒤 WebSocket: `proxy_http_version 1.1` + Upgrade/Connection 헤더 + `/ws`는 긴 timeout 필수.
- 프록시 뒤에서는 `X-Forwarded-*`를 신뢰해야 함 → `forward-headers-strategy`.

**OS (Rocky 8.8)**
- 기본 repo에 docker-ce 없음 → 공식 repo 추가 설치. SELinux는 이 VM은 Disabled로 제공.
- swap 0으로 제공 → 4GB swapfile + `vm.swappiness=10` (JVM+MySQL 동시 스파이크 시 OOM 완충).
- 호스트·컨테이너 모두 UTC가 기본 → 호스트 `timedatectl` + compose `TZ=Asia/Seoul` (09-02 반영 완료).

**배포·시크릿**
- 프로덕션 시크릿은 VM `/opt/applegame/.env`(600)에만. GitHub Secrets에는 SSH 키만.
- `ddl-auto: validate`는 검증만 한다 → Flyway 도입(O1).
- 단순 `compose up`은 수 초 다운타임 → blue-green 전환 채택.
- 배포 성공 = 컨테이너 떴음이 아니다 → `/actuator/health` UP 대기 후 전환.
- docker json-file 로그 무한 증가 → VM `/etc/docker/daemon.json`에서 max-size 10m/max-file 3.

**데이터**
- Redis는 캐시가 아니라 랭킹(ZSET)·방 상태의 원본 → `appendonly yes` + `noeviction` + maxmemory 1gb (09-02 반영 완료).
- 백업: mysqldump cron(매일 04시) → `/opt/applegame/backups`, 주기적으로 로컬 PC로 scp. 완전한 DR은 명시적 포기.
- `mysql:8` 무빙 태그 금지 → **8.4 LTS 고정** (로컬 볼륨이 이미 8.4로 초기화, 다운그레이드 불가).

## 3. 메모리·CPU·디스크 예산

15 GB / 2 vCPU 기준.

| 컨테이너 | RAM 상한 | 비고 |
|---|---|---|
| app (JVM) | -Xmx2g · limit 3g | 힙 2g + 힙 밖 1g(메타스페이스·스레드 스택·다이렉트 버퍼). 다중 인스턴스 시 ×2 |
| mysql | buffer_pool 2G · limit 3g | pool 밖(정렬·조인 버퍼, 임시 테이블)이 커넥션 수에 비례. buffer_pool 09-02 · limit 09-17 |
| redis | maxmemory 1gb · limit 1.5g | 여유 0.5g는 AOF rewrite의 fork COW 몫. noeviction · maxmemory 09-02 · limit 09-17 |
| nginx / prometheus / grafana / node-exporter | 128m / 1g / 512m / 64m | prometheus는 head block(최근 2h)만 메모리, retention 15d는 디스크 |
| **합계** | **≈ 9.19 GiB** | 여유 ~5.8GB + swap 4GB. `mem_limit` 전 서비스 반영(09-17, #33) |

`mem_limit`은 swarm 전용 `deploy.resources.limits`가 아니라 서비스 최상위 키로 쓴다(일반 compose에서는 후자가 조용히 무시된다). 목적은 격리 — 상한이 없으면 한 컨테이너의 폭주가 VM 메모리를 잠식해 커널 OOM Killer가 무관한 컨테이너(1순위: buffer pool 2G를 쥔 MySQL)를 죽인다.

CPU 2코어라 부하 테스트·다중 인스턴스 실험 시 앱끼리 경합 → 절대 처리량이 아니라 **정합성과 before/after 상대 비교**가 목적임을 결과에 명시한다.

디스크(99 GB 중 ~28 GB 예산): OS 4.2 + swap 4 + 이미지 ~3(`docker image prune -f` 배포 성공 경로에 반영, 09-17 #33) + MySQL 10(`skip-log-bin` 09-02 반영) + Redis AOF 1 + Prometheus 2~3 + 백업 1. 디스크는 병목 아님.

## 4. 단계별 로드맵

각 Phase는 앞 Phase의 산출물 위에서만 성립한다.

- **Phase 1 — 데모 배포 (IP + http): ✅ 완료 (09-02 01:35, 이슈 #15 닫음)**
  Dockerfile(multi-stage·layered jar) · docker-compose.prod.yml(7서비스, blue-green 2색) · nginx 설정 · actuator+prometheus · Flyway · deploy.yml(test→build→GHCR→ssh 전환) · VM 초기화 런북 B-0~B-11 · compose 잔여 설정(Redis 영속성·MySQL 튜닝·TZ, PR #22)
- **Phase 2 — HTTPS: ✅ 완료 (09-20).** DuckDNS(myapplegame.duckdns.org) → certbot(webroot) → 443 + http→https 리다이렉트 + HSTS → firewalld https 추가 → SockJS wss 자동 적용. **nginx/compose만 바뀌고 자바·프론트 코드는 무변경**(SockJS 상대경로, X-Forwarded-Proto·forward-headers-strategy 기존 반영).
  배관(ACME location·certbot 서비스·443 볼륨/포트)과 443 서버 블록은 인증서 유무 때문에 **커밋 2개로 분리**했다. deploy.sh는 nginx를 안 건드리므로(§9-1) 아래 절차는 VM에서 수동:
  ```bash
  # 0) DuckDNS: myapplegame.duckdns.org의 current ip = 223.130.152.28 (dig로 확인)
  cd /opt/applegame && git pull --ff-only origin main
  # 1) 443 열기 — ACG는 이미 열림(§7), VM firewalld에만 추가
  firewall-cmd --add-service=https --permanent && firewall-cmd --reload
  # 2) 볼륨·443 반영(nginx 재생성 — 잠깐 끊긴다. 한가한 시간에)
  docker compose -f docker-compose.prod.yml up -d nginx
  # 3) 인증서 발급(webroot). 이 시점엔 아직 http만 뜨지만 챌린지 경로는 응답한다
  docker compose -f docker-compose.prod.yml run --rm certbot certonly \
    --webroot -w /var/www/certbot -d myapplegame.duckdns.org \
    --email <메일> --agree-tos --no-eff-email
  # 4) 인증서가 생긴 뒤 443 서버 블록이 유효해진다 — 설정만 바뀌었으니 reload(무중단)
  docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
  # 5) 확인: https 자물쇠 + http 접속 시 301
  curl -sSI https://myapplegame.duckdns.org/ | head -1
  curl -sSI http://myapplegame.duckdns.org/  | grep -i location
  ```
  **자동 갱신**(90일 만료) — VM cron 하루 2회:
  ```bash
  docker compose -f /opt/applegame/docker-compose.prod.yml run --rm certbot renew --quiet \
    && docker compose -f /opt/applegame/docker-compose.prod.yml exec -T nginx nginx -s reload
  ```
- **도메인 이전 — DuckDNS → fruitboxduel.com: ✅ 완료 (10-06, #60·PR #61).** 위 Phase 2 절차의 도메인·인증서 경로는 이전 전 기준이다. 사유·절차·실제 수행 기록은 §9-3.
- **HSTS 단일 출처 (10-06).** HSTS 헤더는 nginx만 보낸다. 그 전까지는 Spring Security 기본 HSTS(1년 + includeSubDomains)가 nginx 헤더(30일)보다 먼저 나가서, 첫 헤더만 처리하는 규칙(RFC 6797 §8.1)에 따라 nginx 정책이 한 번도 적용되지 않았다. `SecurityConfig`에서 Spring 쪽을 껐다.
- **Phase 3 — 모니터링 고도화**: Grafana 대시보드(JVM 힙·GC, HikariCP, HTTP p95, WS 세션 수), mysqld/redis-exporter 여부, 슬로우 쿼리 → `index_experiment.md` 연결
  - **09-18 1차 (#32)**: 데이터소스·대시보드 provisioning(`monitoring/grafana/`, 데이터소스 uid `prometheus` 고정) · HTTP 히스토그램 버킷 · 커스텀 지표(`websocket_sessions`·`websocket_inbound_*`·`ranking_aggregation_total`·`ranking_warmup_wait_total`) · 대시보드 "AppleGame — 부하 실습" 22패널 · 로컬 `--profile monitoring`
  - VM 반영은 배포로 되지 않는다(§9-1) — 머지 후 `docker compose -f docker-compose.prod.yml up -d grafana`
  - 남은 것: mysqld/redis-exporter · Alertmanager · 슬로우 쿼리 연결
- **Phase 4 — 다중 인스턴스 실험**: app 2개 + Nginx upstream(SockJS 유지 시 `ip_hash` 필수), Redis pub/sub 직접 구현(O3), 인스턴스 간 브로드캐스트 정합성·Lua 동시성 검증
- **Phase 5 — 부하 테스트**: k6 시나리오(랭킹 폭주·동시 clear·동시 가입). 로컬→VM은 절대치용, VM 내부는 상대 비교용 — 결과 표기 시 어느 쪽인지 반드시 명시

## 5. 다중 인스턴스 브로커 비교 (Phase 4 결정용)

| | Redis pub/sub 직접 구현 (**O3 확정**) | RabbitMQ + StompBrokerRelay |
|---|---|---|
| 신규 컨테이너 | 없음 (기존 Redis) | RabbitMQ, RAM ~300m |
| 코드 변경 | Redis 채널 publish → 각 인스턴스가 로컬 재전파. 클래스 2~3개 | 설정 1줄 |
| `/user/queue` 개인 메시지 | 전 인스턴스에 뿌리고 해당 세션만 전달(추가 처리) | 브로커가 처리 |
| 학습 가치 | pub/sub 동작·at-most-once 체감 | "표준 답안" |
| 2vCPU 적합성 | 유리 | 불리 |

## 6. 미결 항목 최종 결정 (O1~O8 · 전부 처리 완료)

| # | 항목 | 결정 |
|---|---|---|
| O1 | DB 스키마 | **Flyway** — `V1__init.sql`, `ddl-auto: validate` 유지 |
| O2 | 도메인 | DuckDNS (Phase 2) |
| O3 | 브로커 | Redis pub/sub 직접 구현 (Phase 4) |
| O4 | SockJS | 유지 — Phase 4에서 `ip_hash`로 대응 |
| O5 | GHCR 공개 | public — 서버에서 docker login 불필요 |
| O6 | 팀원 VM 접근 | `deploy` 유저 authorized_keys에 공개키 한 줄 추가(`>>`) |
| O7 | 소셜 로그인 redirect | **소멸** — 소셜 로그인 자체 제거(#16). 로그인은 자체 회원가입만 |
| O8 | k3s + GitLab Runner | **소멸** — 09-01 실사 결과 존재하지 않음. 파생된 blue-green 결정은 유지 |

**blue-green 무중단 배포 (O8 파생 · 유지)**: `app-blue`/`app-green` 두 서비스를 정의하고 평소 한쪽만 기동(profile). 배포는 유휴 색 `up -d` → health UP 대기 → nginx include 교체 + reload → 이전 색 stop. HTTP 다운타임 0, WebSocket은 전환 시 1회 재접속(상태는 Redis가 복원). **주의: 무중단은 app에만 해당** — mysql·redis 설정 변경 시 재생성으로 짧은 DB 다운타임 발생(09-02 PR #22에서 체감).

## 7. 포트 정책

| 포트 | 공개 | 용도 |
|---|---|---|
| 22 | 전체 (제공처 고정) | SSH — 키 인증만, root 금지, fail2ban. IP 제한 불가(D11) |
| 80 | 전체 | Nginx — Phase 2부터 ACME 챌린지 외 전부 443 리다이렉트 |
| 443 | ACG·firewalld 열림 | Phase 2 TLS 종료(nginx). 09-20 적용 |
| 3000 | ACG 열림 · firewalld 차단 · listen 없음 | Grafana는 `/grafana` 경로로만 — 실질 폐쇄(D4) |
| 3306 · 6379 · 8080 · 9090 | 비공개 | Docker 내부 네트워크 전용, `ports:` 미사용 |

## 8. VM 초기화 런북 결과 (B-0~B-11 · 전부 완료)

상세 명령·실수 기록은 아티팩트 부록 B. 여기는 결과만.

| 단계 | 내용 | 결과 |
|---|---|---|
| B-0 | 80/443 점유 확인 | 22만 listen — k3s 없음, O8 소멸 |
| B-1 | ACG 정리 | 해당 없음(콘솔 권한 없음) → B-3+B-6으로 대체 |
| B-2 | deploy 유저 + 공개키(윈도우·맥·Actions 3줄) + sudo | 09-01 완료 |
| B-3 | sshd 키 인증만 + root 금지 + 우회 포트 잔재 제거 | 09-01 완료 (로그인 실패 12만 회 대응) |
| B-4 | swapfile 4G + swappiness 10 | 09-01 완료 |
| B-5 | docker-ce 29 + compose + 로그 로테이션 + deploy 권한 | 09-01 완료 |
| B-6 | firewalld(ssh·http만) + fail2ban(5회/10분→1h 차단) | 09-01 완료 — 켜자마자 IP 차단 시작 |
| B-7 | /opt/applegame clone + .env(600, 시크릿은 VM에만·맥에 scp 사본) | 09-01 완료 |
| B-8 | GitHub Secrets(SSH_HOST/USER/KEY) + 첫 이미지 + GHCR public | 09-01 완료 |
| B-9 | 최초 기동 — 외부 200, Flyway V1, Grafana | 09-02 완료 (밑줄 호스트명 버그 발견→PR #21) |
| B-10 | 자동배포 end-to-end (green 전환) | 09-02 완료 — **main 머지 = 자동 배포** |
| B-11 | mysqldump cron 04:00 (cronie 설치 포함) | 09-02 완료 |

## 9. CI/CD & 브랜치 보호 (09-02)

- **파이프라인**: main push(또는 PR) → `test`(MySQL/Redis 서비스 컨테이너, prod 프로필로 Flyway+validate까지 검증) → `build-and-push`(GHCR, main만) → `deploy`(ssh → `git pull --ff-only` → deploy.sh). PR에서는 test만 돈다.
- **소요**: 전체 약 3분 (test ~1분 30초 + 빌드 ~1분 + 배포 ~30초).
- **main 브랜치 보호**: PR 필수(승인 0) · `test` 체크 성공 필수 · force push/삭제 금지 · 관리자 포함(enforce_admins). 필수 체크를 쓰려면 워크플로에 `pull_request` 트리거가 있어야 한다(#25 — 없으면 체크가 영원히 미보고되어 머지 교착).
- **수동 실행 전용 테스트는 환경변수 게이트**: `CLEAR_BENCH=true`(벤치마크) · `SOLO_DUMMY=true`(인덱스 실험용 더미 200만 건). `@Disabled` 주석 토글은 되돌림을 잊으면 CI가 그대로 실행한다(#26에서 25분→1분대로 단축된 원인).

### 9-1. 배포가 다루는 범위 — 앱만 (09-18, #46)

`deploy.sh`는 `up -d --no-deps app-${NEW}`로 **앱 컨테이너만** 교체한다. compose 파일의
인프라 서비스(mysql·redis·nginx·prometheus·grafana·node-exporter) 설정을 바꿔도
배포로는 반영되지 않는다 — 의도된 제약이다.

이유: `--no-deps`가 없으면 compose가 `depends_on`의 mysql·redis까지 챕기고, 그 서비스의
설정이 바뀌어 있으면 재생성한다. 09-18 00:10 배포가 그 사례다(§10 참고) — 무중단 배포
도중 DB·Redis가 교체되어 서빙 중이던 구 색이 30초간 둘을 모두 잃었다.
blue-green의 무중단 보장은 앱 컨테이너에만 성립하므로, 배포가 할 수 있는 일을 그 범위로 좁혔다.

**예외 — nginx 설정 파일은 배포 때 반영된다.** 배포는 nginx 컨테이너를 재생성하지 않지만,
워크플로의 `git pull`로 `nginx/*.conf`가 바뀐 뒤 `deploy.sh`가 색 전환을 위해 `nginx -s reload`를
실행한다. reload는 include 파일만이 아니라 **디스크의 설정 전체를 다시 읽는다** — 즉 nginx 설정 변경은
머지 직후 배포에서 그대로 운영에 적용된다(10-06 도메인 이전이 이 경로로 반영됐다).
그래서 nginx 설정이 외부 파일(인증서 등)에 의존하게 바꿀 때는 **그 파일을 VM에 먼저 준비한 뒤 머지**한다.
준비 없이 머지하면 reload가 실패해 배포가 전환 직전에 멈춘다(`set -e` — 기존 색이 계속 서빙하므로 다운타임은 없다).
compose의 nginx 서비스 정의(포트·볼륨 등)는 이 경로로 반영되지 않는다 — 아래 절차대로 재생성해야 한다.

**인프라 설정을 바꿨을 때의 적용 절차** (VM `/opt/applegame`에서 수동):

```bash
git pull --ff-only origin main          # 배포가 이미 했다면 생략 가능

# 사용자 영향이 없는 서비스 — 아무 때나
docker compose -f docker-compose.prod.yml up -d prometheus grafana node-exporter

# nginx — 재생성하는 순간 모든 연결이 끊긴다(프록시 본체). 한가한 시간에.
#   설정 파일(nginx/*.conf)만 바꾼 경우는 재생성 없이 reload로 충분하다:
#   docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
docker compose -f docker-compose.prod.yml up -d nginx

# mysql·redis — 재시작 동안 서비스가 조회에 실패한다. 의도한 시점에만.
#   데이터는 named volume(mysql-data·redis-data)에 있어 컨테이너 교체로 사라지지 않는다.
docker compose -f docker-compose.prod.yml up -d mysql redis

docker stats --no-stream   # LIMIT 열로 mem_limit 적용 확인
```

배포 전 가드: `deploy.sh`는 `--no-deps`로 `depends_on`의 대기 보장을 잃으므로, 시작 시
`docker inspect`로 mysql이 healthy·redis가 running인지 확인하고 아니면 즉시 실패한다.

### 9-2. 인증서 갱신 (Phase 2, 09-20)

Let's Encrypt 인증서는 **90일**이다(현재 것: 2026-12-17 만료). 갱신은 `scripts/renew-cert.sh`가
하고, VM의 cron이 하루 2회 호출한다.

```
0 3,15 * * * /opt/applegame/scripts/renew-cert.sh >> /opt/applegame/renew.log 2>&1
```

cron 등록은 VM 로컬 상태라 리포로 관리되지 않는다 — VM을 다시 만들면 이 줄을 다시 넣어야 한다.

**갱신 방식은 webroot여야 한다.** `/etc/letsencrypt/renewal/fruitboxduel.com.conf`(이전 전: `myapplegame.duckdns.org.conf`)의
`authenticator` 값이 갱신 때 쓰이는데, 최초 발급을 `--standalone`으로 하면 그 값이 저장되어
갱신이 반드시 실패한다:

- standalone은 certbot이 **직접 80포트에 임시 웹서버를 띄우는** 방식이다
- 그런데 80은 nginx가 쥐고 있고, certbot 컨테이너에는 포트 매핑도 없다
- CA의 챌린지 요청은 nginx에 도달하고, nginx는 `/var/www/certbot`에서 파일을 찾는데
  standalone certbot은 거기 쓴 적이 없다 → **404 → 인증 실패**

09-20에 실제로 이 상태였고 `--dry-run`으로 발견했다. 수정은 위 conf의
`authenticator = webroot` + `webroot_path = /var/www/certbot,`.

**검증은 반드시 `--dry-run`으로.** 갱신 코드는 90일에 한 번 처음 돌고, 실패해도 조용하다
(서버는 정상이라 모니터링에 안 잡히고, 만료 후에는 브라우저가 연결 자체를 거부해
요청이 서버에 도달하지도 않는다).

```bash
docker compose -f docker-compose.prod.yml run --rm certbot renew --dry-run
```

dry-run은 스테이징 서버를 쓰므로 발급 한도(주당 5회)를 소모하지 않는다. 몇 번이든 돌려도 된다.

**갱신 후 `nginx -s reload`가 필요하다** — nginx는 기동 시 인증서를 메모리에 올리므로
파일이 바뀌어도 스스로 알지 못한다. 스크립트가 renew 직후 항상 reload한다(no-op이어도 무해).

### 9-3. 도메인 이전 — myapplegame.duckdns.org → fruitboxduel.com (10-04, #60)

**왜.** HTTPS 전환 직후부터 크롬이 사이트에 빨간 전면 경고("위험한 사이트")를 띄웠다. 인증서·체인·
혼합 콘텐츠는 전부 정상이었고, 원인은 **Google 세이프 브라우징이 사이트를 위험으로 분류**한 것
(투명성 보고서 API 상태 코드 3 = Google 공식 악성 테스트 사이트와 같은 값, 마지막 평가 09-20 새벽).
Google은 사유를 공개하지 않지만, `duckdns.org`가 피싱 호스팅에 가장 많이 쓰이는 공유 도메인이고
(루트 도메인 자체가 2017년부터 "일부 위험" 상태), 도메인명에 `apple`, 첫 화면에 비밀번호 폼 —
브랜드 피싱 패턴과 정확히 겹친다. 자물쇠(인증서)는 "도메인 통제"만 증명하고 사이트 평판은 별도
시스템이 판정한다는 것을 배운 건. 상세는 노션 트러블슈팅 #8.

Search Console 검토 요청으로 풀 수도 있지만 공유 DDNS 평판 때문에 재분류 위험이 남는다.
자체 도메인 `fruitboxduel.com`(Cloudflare Registrar, 연 $10.46 고정, WHOIS 보호 무료)으로 이전.
DNS는 **DNS only(회색 구름)** — Cloudflare 프록시를 켜면 TLS 종료 지점이 Cloudflare로 옮겨가
SSL 모드·챌린지 경로 변수가 늘어난다. 현 구조(VM nginx가 TLS 종료) 그대로 유지하고, 필요하면
나중에 Full (strict)로 프록시를 켠다.

**무엇이 바뀌나 — 전부 설정뿐, 자바·프론트 무변경.**

| 파일 | 변경 |
|---|---|
| `nginx/default.conf` | 443 `server_name`·인증서 경로 → `fruitboxduel.com`. 80 리다이렉트 대상 → 새 도메인. **www → apex 301 블록 추가** |
| `docker-compose.prod.yml` | Grafana `GF_SERVER_ROOT_URL` → 새 도메인(로그인 리다이렉트 고정값). certbot 발급 예시 주석 |
| `scripts/renew-cert.sh` | 만료일 로그의 인증서 경로 |

**VM 절차 (§9-1 — 배포의 reload가 nginx 설정을 반영하고, grafana env는 수동).** 순서가 중요하다:
443 블록이 새 인증서 경로를 가리키므로 **인증서를 먼저 받고 설정을 바꿔야** reload가 성공한다.
80 블록은 `server_name _`라 DNS만 붙으면 새 도메인의 ACME 챌린지도 기존 설정 그대로 응답한다.

```bash
# 0) DNS 확인 — Cloudflare A 레코드 @·www → 223.130.152.28 (DNS only). 전파 확인:
dig +short fruitboxduel.com www.fruitboxduel.com      # 둘 다 223.130.152.28
# 1) 새 도메인 인증서 발급 — 설정 변경 전, 기존 80 블록이 챌린지에 응답한다.
#    apex와 www를 한 장에(SAN 2개). 디렉터리 이름은 첫 -d 값 = fruitboxduel.com
cd /opt/applegame
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
  --webroot -w /var/www/certbot -d fruitboxduel.com -d www.fruitboxduel.com \
  --email <메일> --agree-tos --no-eff-email
# 2) 설정 반영 — 인증서가 있으니 443 블록이 유효하다. 문법 검사 후 reload(무중단)
git pull --ff-only origin main
docker compose -f docker-compose.prod.yml exec nginx nginx -t
docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
# 3) Grafana는 env가 바뀌었으므로 재생성(잠깐 끊김 — 모니터링만)
docker compose -f docker-compose.prod.yml up -d grafana
# 4) 검증
curl -sSI https://fruitboxduel.com/          | head -1          # HTTP/2 200
curl -sSI http://fruitboxduel.com/           | grep -i location # https://fruitboxduel.com/
curl -sSI https://www.fruitboxduel.com/      | grep -i location # https://fruitboxduel.com/
curl -sSI http://myapplegame.duckdns.org/    | grep -i location # 옛 도메인 http도 새 도메인으로
docker compose -f docker-compose.prod.yml run --rm certbot renew --dry-run   # 갱신 경로 확인
# 5) 옛 인증서 정리 — renew가 만료된 duckdns 인증서를 계속 갱신 시도하지 않게
docker compose -f docker-compose.prod.yml run --rm certbot delete --cert-name myapplegame.duckdns.org
```

**실제 수행 (10-06).** 위 절차의 2)는 수동으로 할 필요가 없었다 — PR #61 머지가 자동 배포를 일으켰고,
배포의 `git pull` → `deploy.sh`의 `nginx -s reload`가 새 설정을 적용했다(§9-1 예외). 실제 순서:
DNS 레코드 추가·전파 확인 → 인증서 발급 + `renew --dry-run` → **PR 머지(=배포·reload)** → `up -d grafana`
→ 검증 → 옛 인증서 delete → dry-run 재확인(fruitboxduel.com 단독 success). 인증서 만료 2027-01-03.
1)을 머지보다 먼저 한 것이 핵심이다 — 반대로 했다면 배포의 reload가 없는 인증서 경로 때문에 실패했다.
검증할 때는 시크릿 창이나 curl을 쓴다. 브라우저가 이전의 301(→ duckdns)을 캐시하고 있다.

**옛 주소는.** `http://myapplegame.duckdns.org`는 80 블록이 새 도메인으로 301한다.
`https://myapplegame.duckdns.org`는 매칭되는 443 블록이 없어 default 서버(fruitboxduel.com 인증서)로
떨어지고 **브라우저가 인증서 불일치로 거부**한다 — 어차피 세이프 브라우징 경고가 뜨던 주소라 살리지
않는다. DuckDNS 레코드는 지우지 않고 만료되게 둔다(30일 미갱신 시 자동 소멸).

## 10. 프로덕션 트러블슈팅 기록 (09-02)

셋 다 **"로컬에서는 재현 불가"** — 프록시·HTTP 실서버 구조에서만 드러났다. 상세는 노션 트러블슈팅 문서.

| # | 증상 | 원인 | 수정 |
|---|---|---|---|
| ① | 헬스체크 400, Prometheus down | compose 서비스명 `app_blue`의 밑줄 — 호스트명 규칙(RFC 1123) 위반으로 Tomcat이 Host 헤더 거절 | `app-blue`/`app-green` 개명 (PR #21) |
| ② | 대전 입장은 되는데 시작 안 됨 — `/ws` 전부 403 | nginx `proxy_set_header`는 location에서 하나라도 정의하면 상위 블록 것을 **전부 미상속** → `/ws`에 Host 누락 → 기본값 `$proxy_host`(app-green:8080)가 전달돼 SockJS same-origin 검사가 cross-origin 판정 | `/ws`·`/grafana/`에 Host·X-Forwarded-* 재선언 (PR #23) |
| ③ | 게임은 시작되는데 사과 제거 무반응 | `crypto.randomUUID()`는 보안 컨텍스트(HTTPS·localhost) 전용 — http+IP에서 undefined → TypeError로 전송 자체가 안 됨 | `getRandomValues` 기반 UUID v4 폴백 `genRequestId()` (PR #24) |

교훈: 로컬에서 멀쩡한데 실서버에서만 고장 나면 **호스트명 규칙 → 프록시 헤더 전달 → 보안 컨텍스트** 순으로 의심한다. 진단은 nginx 접근 로그(어떤 요청이 몇 번으로 실패)와 서버 상태 저장소(Redis 키 — "도착했다면 반드시 남았을 흔적")가 결정적이었다.

---

## 11. 요청 빈도 제한 (10-06, #58)

출시 준비도 점검(09-21)에서 "요청 빈도를 제한하는 장치가 어디에도 없다"가 공개 전 필수로 판정됐다.
① 로그인 무차별 대입 무제한 ② BCrypt(요청당 CPU 60~100ms, 2 vCPU)가 인증 없이 호출되는 DoS 경로
③ 한 계정이 방을 무한정 만들어 `noeviction` Redis(1GB)를 채우면 **전 사용자의 쓰기가 실패**.

### 계층마다 막는 대상이 다르다 — 하나로는 안 된다

| 계층 | 식별 | 막는 것 | 못 막는 것 |
|---|---|---|---|
| nginx `limit_req` | IP | 익명 폭주(①②). **앱에 닿기 전에** 거절하므로 BCrypt를 아예 안 돌린다 | NAT 뒤 다수 사용자를 한 명으로 본다 · IP를 바꾸는 공격자 |
| 도메인 불변식 (앱) | userId | "한 사람은 방 하나"(③) — 빈도가 아니라 **총량**을 묶는다. 천천히 만들어도 못 쌓는다 | 빈도 |
| 요청 크기 상한 (DTO) | 요청 1건 | 비밀번호 64자·이메일 254자·moves 85개 — 요청 1건이 쓸 수 있는 CPU의 상한 | 건수 |

②의 핵심은 **비싼 연산 앞에서 막아야 한다**는 것이다. 앱에서 BCrypt 뒤에 세면 이미 CPU를 썼다. 그래서 인증 엔드포인트는 nginx가 1차다.

### nginx 수치와 근거

| zone | 적용 location | rate | burst | 근거 |
|---|---|---|---|---|
| `auth` | `= /api/auth/login` · `signup` · `reissue` | IP당 **10r/m** | 10, `nodelay` | 사람이 비밀번호를 틀려도 분당 10회를 넘기기 어렵다. 대입 공격은 IP당 분당 10회로 무의미. BCrypt 상한이 ~50 req/s(load_test.md S3)이므로 IP 하나가 쓸 수 있는 CPU는 0.3% |
| `api` | `location /` (REST + 정적 파일) | IP당 **20r/s** | 40, `nodelay` | 정상 플레이는 초당 몇 건. 첫 로드의 정적 파일 묶음(10개 안팎)은 burst로 흡수. **사과 제거는 WebSocket(`/ws`)이라 무관** — `/ws`·`/grafana/`에는 걸지 않는다 |

- `nodelay`: burst까지 즉시 통과, 초과분은 **거절**(없으면 큐에 넣어 지연 — 로그인에서 지연은 "먹통"으로 보인다).
- 거절 코드는 기본 503 대신 **429** (`limit_req_status`). 503은 배포 전환 중 일시 장애와 구분이 안 된다. 프론트 `api.js`는 429를 "요청이 너무 많습니다. 잠시 후 다시 시도해주세요"로 띄우고, 재발급의 429는 `NETWORK`로 분류해 **로그아웃시키지 않는다**(멀쩡한 refresh를 버리지 않기 위해).
- 키는 `$binary_remote_addr`. Cloudflare는 DNS only라 `remote_addr`이 실제 클라이언트 IP다. 적용 전 VM의 nginx 접근 로그로 IP가 Docker 게이트웨이(172.x)로 뭉개지지 않고 공인 IP별로 찍히는 것을 확인했다 — 뭉개졌다면 "IP당"이 "전체"가 되어 서비스 전체가 분당 10회가 된다.
- zone 정의(`limit_req_zone`)는 http 컨텍스트 전용이라 `nginx/ratelimit.conf`에 따로 두었다(`conf.d/*.conf`는 자동 include). 적용(`limit_req`)은 `default.conf`의 location. location에 `proxy_set_header`를 쓰지 않았으므로 server 블록의 공통 헤더가 상속된다(§10 ② 교훈).

### 반영 경로와 검증

- **배포로 반영된다**(§9-1 예외 항목): 머지 → 워크플로 `git pull` → `deploy.sh`의 `nginx -s reload`가 새 `.conf`를 읽는다. 수동 작업 없음. 문법 오류면 reload가 실패해 전환 직전에 멈춘다(기존 색이 계속 서빙). 로컬에서 `nginx -t`로 선검증:
  ```bash
  docker run --rm --add-host grafana:127.0.0.1 -v $PWD/nginx:/etc/nginx/conf.d:ro -v <더미 인증서>:/etc/letsencrypt:ro nginx:1.27-alpine nginx -t
  ```
- 배포 후 확인:
  ```bash
  for i in $(seq 1 12); do curl -s -o /dev/null -w "%{http_code} " -X POST https://fruitboxduel.com/api/auth/login -H 'Content-Type: application/json' -d '{"email":"x@x.com","password":"wrong1!xx"}'; done; echo
  # 기대: 401 ×10 → 429 ×2 (burst 10 소진 후 거절)
  docker compose -f docker-compose.prod.yml logs --no-log-prefix nginx | awk '{print $9}' | sort | uniq -c   # 상태코드 집계
  ```
- **429는 앱 지표(Prometheus/Grafana)에 안 잡힌다** — 앱에 도달하지 않았으므로. 거절량은 nginx access.log(상태코드)와 error.log(`limiting requests`, warn)로 센다. Phase 3에서 nginx exporter를 붙이면 그때 대시보드에 올린다.
- **부하 테스트 주의**: k6는 한 IP에서 쏘므로 nginx를 거치면 대부분 429다. 앱의 상한(S3)을 다시 재려면 VM 안에서 앱 포트로 직접(`BASE=http://app-blue:8080`) 보낸다. `load/signup-burst.js`는 429를 별도로 센다.

### 결정 — 계정 단위 실패 카운터는 넣지 않는다

"이메일당 실패 5회 → 10분 잠금"은 **공격자가 남의 이메일로 5번 틀려 그 사람을 잠글 수 있다** — 잠금 자체가 DoS 수단이 된다.
완화책(지연만 늘리기 / IP+이메일 조합 / CAPTCHA)은 전부 복잡도를 더하는데, 지금 지킬 자산은 게임 기록뿐이다.
IP 제한만으로 ① 무차별 대입은 IP당 분당 10회로 묶이고 ② BCrypt CPU는 IP당 0.3%로 묶인다. 계정 단위는 "IP를 바꿔가며 한 계정을 노리는" 공격에만 추가 가치가 있고, 그 공격은 이 서비스의 자산 가치에 비해 비용이 크다. 필요해지면 "잠그지 않고 실패마다 응답을 1초씩 늦추는" 방식부터.

### 남긴 것

- Redis 메모리 사용률 알림 — 제한을 넣어도 "차고 있다"는 걸 아는 수단은 별도(Phase 3 Alertmanager, `redis_memory_used_bytes / maxmemory`).
- nginx 429 집계를 대시보드로 — nginx exporter 또는 access.log 파싱.
- 가입 폭주(봇 가입)는 IP 제한으로 느려질 뿐 막히지 않는다 — 이메일 인증(#59 B안)이나 CAPTCHA가 답이고, 그건 #59의 결정에 달려 있다.
