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
| 상태 | **Phase 1 완료 (09-02)** — http://223.130.152.28/ 서빙 중, main 머지 = 자동 배포 |

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
GitHub Actions: test → build → push GHCR ghcr.io/<owner>/myapplegame:<sha>
```

## 1. 확정한 결정 (D1~D11)

D-번호는 이후 문서·PR에서 참조하는 식별자.

| # | 항목 | 결정 | 근거 / 대안 |
|---|---|---|---|
| D1 | 클라우드 자원 | **NCP VM 1대가 전부** | 매니지드 DB/Redis 없음 → 전부 컨테이너로 VM 안에서 운영 |
| D2 | VM 스펙 | 2 vCPU / 15 GB / swap 4 GB | RAM 여유, CPU 병목. 메모리 예산은 §3 |
| D3 | 도메인/TLS | 1차: 공인 IP + http. 도메인은 Phase 2 (DuckDNS) | wss는 Phase 2. Nginx 설정만 바꾸면 되도록 구조화 |
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
| app (JVM) | -Xmx2g · limit 3g | 다중 인스턴스 시 ×2 |
| mysql | buffer_pool 2G · limit 3g | 09-02 반영 |
| redis | maxmemory 1gb · limit 1.5g | noeviction · 09-02 반영 |
| nginx / prometheus / grafana / node-exporter | 128m / 1g / 512m / 64m | prometheus retention 15d |
| **합계** | **≈ 9 GB** | 여유 6GB + swap 4GB. `mem_limit` 명시는 미반영(잔여) |

CPU 2코어라 부하 테스트·다중 인스턴스 실험 시 앱끼리 경합 → 절대 처리량이 아니라 **정합성과 before/after 상대 비교**가 목적임을 결과에 명시한다.

디스크(99 GB 중 ~28 GB 예산): OS 4.2 + swap 4 + 이미지 ~3(`docker image prune` 잔여 작업) + MySQL 10(`skip-log-bin` 09-02 반영) + Redis AOF 1 + Prometheus 2~3 + 백업 1. 디스크는 병목 아님.

## 4. 단계별 로드맵

각 Phase는 앞 Phase의 산출물 위에서만 성립한다.

- **Phase 1 — 데모 배포 (IP + http): ✅ 완료 (09-02 01:35, 이슈 #15 닫음)**
  Dockerfile(multi-stage·layered jar) · docker-compose.prod.yml(7서비스, blue-green 2색) · nginx 설정 · actuator+prometheus · Flyway · deploy.yml(test→build→GHCR→ssh 전환) · VM 초기화 런북 B-0~B-11 · compose 잔여 설정(Redis 영속성·MySQL 튜닝·TZ, PR #22)
- **Phase 2 — HTTPS**: DuckDNS 서브도메인 → certbot → 443 + http→https 리다이렉트 → firewalld https 추가 → `new SockJS('/ws')`의 wss 자동 적용 확인
- **Phase 3 — 모니터링 고도화**: Grafana 대시보드(JVM 힙·GC, HikariCP, HTTP p95, WS 세션 수), mysqld/redis-exporter 여부, 슬로우 쿼리 → `index_experiment.md` 연결
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
| 80 | 전체 | Nginx (Phase 2부터 443 리다이렉트) |
| 443 | ACG 열림 · firewalld 차단 | Phase 2에서 firewalld에 https 추가 |
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

## 10. 프로덕션 트러블슈팅 기록 (09-02)

셋 다 **"로컬에서는 재현 불가"** — 프록시·HTTP 실서버 구조에서만 드러났다. 상세는 노션 트러블슈팅 문서.

| # | 증상 | 원인 | 수정 |
|---|---|---|---|
| ① | 헬스체크 400, Prometheus down | compose 서비스명 `app_blue`의 밑줄 — 호스트명 규칙(RFC 1123) 위반으로 Tomcat이 Host 헤더 거절 | `app-blue`/`app-green` 개명 (PR #21) |
| ② | 대전 입장은 되는데 시작 안 됨 — `/ws` 전부 403 | nginx `proxy_set_header`는 location에서 하나라도 정의하면 상위 블록 것을 **전부 미상속** → `/ws`에 Host 누락 → 기본값 `$proxy_host`(app-green:8080)가 전달돼 SockJS same-origin 검사가 cross-origin 판정 | `/ws`·`/grafana/`에 Host·X-Forwarded-* 재선언 (PR #23) |
| ③ | 게임은 시작되는데 사과 제거 무반응 | `crypto.randomUUID()`는 보안 컨텍스트(HTTPS·localhost) 전용 — http+IP에서 undefined → TypeError로 전송 자체가 안 됨 | `getRandomValues` 기반 UUID v4 폴백 `genRequestId()` (PR #24) |

교훈: 로컬에서 멀쩡한데 실서버에서만 고장 나면 **호스트명 규칙 → 프록시 헤더 전달 → 보안 컨텍스트** 순으로 의심한다. 진단은 nginx 접근 로그(어떤 요청이 몇 번으로 실패)와 서버 상태 저장소(Redis 키 — "도착했다면 반드시 남았을 흔적")가 결정적이었다.
