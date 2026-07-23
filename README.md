# Continuous Chess

Continuous Chess는 일반 체스의 8x8 격자를 시각적 기준으로 쓰되, 일부 기물이 실수 좌표 평면 위의 점으로 이동하는 웹 기반 1대1 친선전 게임입니다.

현재 버전은 체크를 정확히 판정하지만, 체크메이트와 스테일메이트를 자동 판정하지 않습니다.

## 규칙 요약

- 보드 논리 영역은 `0 <= x <= 7`, `0 <= y <= 7`입니다.
- 초기 배치는 일반 체스와 같은 정수 좌표입니다.
- 폰과 킹은 정수점에만 존재합니다. `EPSILON = 1e-7` 안에서 정수에 가까운 좌표는 스냅됩니다.
- 룩, 비숍, 퀸, 나이트는 연속 좌표에 존재할 수 있습니다.
- 룩은 수평 또는 수직 직선으로 이동합니다.
- 비숍은 기울기 `1` 또는 `-1` 대각선으로 이동합니다.
- 퀸은 룩 또는 비숍 조건을 따릅니다.
- 나이트는 현재 위치에서 유클리드 거리 `sqrt(5)`인 원 위 전체로 이동할 수 있고, 기물을 뛰어넘습니다.
- 폰은 전진과 공격 판정이 분리됩니다. 마지막 랭크 도착 시 자동으로 퀸으로 승격합니다.
- 캐슬링은 버튼 또는 킹의 두 칸 이동 명령으로 처리되며, 경로 비움과 체크 통과 금지를 서버가 검증합니다.
- 앙파상은 직전 수가 상대 폰의 두 칸 전진일 때만 서버가 허용합니다.
- 승격 기물 선택, 자동 체크메이트/스테일메이트 판정은 MVP 범위 밖입니다.

## 기술 구조

- Kotlin/JVM 21
- Ktor 3.5.0
- Gradle Kotlin DSL
- kotlinx.serialization JSON
- SQLite JDBC
- 정적 HTML/CSS/JavaScript + Canvas
- 서버 권위 게임 판정
- WebSocket 실시간 동기화
- 기본 AI전
- 수식 입력기, 공격 범위 표시, 보드 흑백 시점 전환

프런트엔드는 기존 코드가 없었기 때문에 대규모 프레임워크를 추가하지 않고 Canvas 기반 정적 UI를 선택했습니다. SQLite 접근은 ORM 없이 prepared statement 기반 JDBC로 구현했고, 규칙 엔진은 Ktor/SQLite/OAuth와 독립된 `engine` 패키지에 분리했습니다.

## 패키지 구조

- `application`: Ktor 모듈, 설정, 플러그인 연결
- `auth`: Google OAuth, 세션, 현재 사용자 경계
- `engine`: 좌표, 파서, 계산기하학, 이동 규칙, 체크 판정
- `room`: 초대 코드 친선방
- `game`: 게임 명령 서비스와 동시성 제어
- `websocket`: WebSocket 메시지와 브로드캐스트
- `persistence`: SQLite 연결, 마이그레이션, 저장소
- `history`: 전적 응답 모델
- `routes`: REST API
- `resources/static`: 기본 웹 UI

## 환경 변수

`.env`는 애플리케이션이 직접 로드하지 않습니다. 개발 환경에서는 실행 셸이나 IDE Run Configuration에 값을 넣으세요.

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `GOOGLE_REDIRECT_URI`
- `SESSION_SECRET`
- `DATABASE_PATH`
- `APP_BASE_URL`
- `PORT`
- `ENVIRONMENT`

기본 SQLite 파일은 `identifier.sqlite`입니다. `DATABASE_PATH`로 변경할 수 있습니다.

## Google OAuth 설정

1. Google Cloud Console에서 OAuth consent screen을 구성합니다.
2. OAuth 2.0 Client ID를 Web application 유형으로 생성합니다.
3. Authorized redirect URI에 `GOOGLE_REDIRECT_URI` 값을 등록합니다. 예: `http://localhost:8080/auth/google/callback`
4. 발급된 Client ID와 Client Secret을 환경 변수로 주입합니다.
5. 내부 사용자 식별자는 이메일이 아니라 Google `sub` 값을 사용합니다.

토큰은 장기 저장하지 않습니다. 콜백에서는 state를 검증하고, Google tokeninfo 응답의 issuer와 audience를 확인합니다.

## 실행

```powershell
.\gradlew.bat run
```

브라우저에서 `http://localhost:8080`으로 접속합니다.

## 테스트

```powershell
.\gradlew.bat test
```

## 빌드

```powershell
.\gradlew.bat build
```

## Fly.io 배포

Fly.io의 Dockerfile 프리셋 선택 화면에서는 `Dockerfile`을 사용하면 됩니다. 이 프로젝트의 Dockerfile은 Gradle로 배포용 런처를 만든 뒤 JRE 이미지에서 Ktor 서버를 실행합니다.

Fly 앱에는 최소한 다음 secrets를 설정해야 합니다.

```powershell
fly secrets set GOOGLE_CLIENT_ID="..."
fly secrets set GOOGLE_CLIENT_SECRET="..."
fly secrets set GOOGLE_REDIRECT_URI="https://YOUR_APP.fly.dev/auth/google/callback"
fly secrets set SESSION_SECRET="긴_랜덤_문자열"
fly secrets set APP_BASE_URL="https://YOUR_APP.fly.dev"
fly secrets set ENVIRONMENT="production"
```

SQLite 파일은 기본적으로 컨테이너의 `/data/conchess.sqlite`를 사용합니다. 데이터를 유지하려면 Fly volume을 만들고 `/data`에 마운트하세요.

```powershell
fly volumes create conchess_data --size 1
```

`fly.toml`에는 다음처럼 8080 포트와 `/data` 마운트를 둡니다.

```toml
[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = true
  auto_start_machines = true
  min_machines_running = 0

[[mounts]]
  source = "conchess_data"
  destination = "/data"
```

배포 DB를 직접 확인하거나 수정해야 한다면 실행 중인 머신에 접속해 SQLite CLI를 사용합니다.

```powershell
fly ssh console -a conchess
sqlite3 /data/conchess.sqlite
```

자주 쓰는 SQLite 명령:

```sql
.tables
.schema users
SELECT id, google_subject, display_name, last_login_at FROM users;
UPDATE users SET display_name = 'New Name' WHERE id = 1;
.quit
```

운영 DB를 직접 수정하기 전에는 가능하면 백업을 먼저 받으세요.

```powershell
fly ssh sftp get /data/conchess.sqlite -a conchess
```

## 데이터베이스와 마이그레이션

애플리케이션 시작 시 `schema_migrations` 테이블을 확인하고 아직 적용되지 않은 SQL만 실행합니다. 기존 운영 DB 파일을 삭제하거나 무작정 재생성하지 않습니다.

저장 방식은 현재 게임 상태 JSON 스냅샷과 `moves` 테이블 병행입니다. 이 방식은 MVP에서 구현 복잡도를 낮추면서도 서버 재시작 후 진행 중 게임을 복원할 수 있습니다.

## 초대방 흐름

1. 로그인 사용자가 `POST /api/rooms`로 방을 만듭니다.
2. 서버가 추측하기 어려운 URL-safe 초대 코드를 생성합니다.
3. 친구가 `/game/join/{inviteCode}`에 접속한 뒤 로그인 세션으로 참가합니다.
4. MVP 색상 정책은 방 생성자가 백, 참가자가 흑입니다.
5. 두 명이 모이면 게임 상태가 `ACTIVE`가 되고 WebSocket으로 진행합니다.

## AI전

로그인 사용자는 대시보드의 `AI전 시작` 버튼 또는 `POST /api/ai-games`로 AI전을 만들 수 있습니다.

- 사용자는 백, AI는 흑입니다.
- AI는 서버 내부 사용자 `Continuous Chess AI`로 저장되며 전적 상대에 표시됩니다.
- AI는 서버 권위 엔진으로 합법성을 검증한 후보 중 포획을 우선하고, 없으면 첫 합법 수를 둡니다.
- 사용자 이동 직후 같은 게임 락 안에서 AI 응수가 적용됩니다.
- MVP AI는 강한 체스 엔진이 아니라 규칙 검증용 기본 상대입니다.
- AI 난도는 `EASY`, `NORMAL`, `HARD` 세 단계입니다. 쉬움은 첫 합법수, 보통은 포획 우선, 어려움은 높은 가치 포획과 체크 보너스를 우선합니다.
- AI전 생성 시 사용자 색상, 제한시간, 증초를 설정할 수 있습니다.
- 제한시간은 서버 상태에 저장되며 착수 요청 시 현재 턴 플레이어 시간이 0이면 `TIMEOUT`으로 종료됩니다.

## REST API

- `GET /auth/google/login`
- `GET /auth/google/callback`
- `POST /auth/logout`
- `GET /api/me`
- `POST /api/rooms`
- `GET /api/rooms/{inviteCode}`
- `POST /api/rooms/{inviteCode}/join`
- `POST /api/rooms/{inviteCode}/cancel`
- `POST /api/ai-games`
- `GET /api/games/{gameId}`
- `POST /api/games/{gameId}/move`
- `POST /api/games/{gameId}/resign`
- `POST /api/games/{gameId}/draw-offer`
- `POST /api/games/{gameId}/draw-accept`
- `POST /api/games/{gameId}/draw-decline`
- `GET /api/me/stats`
- `GET /api/me/matches`
- `GET /api/games/{gameId}/moves`

`POST /api/ai-games` 요청 예:

```json
{
  "userColor": "BLACK",
  "initialSeconds": 600,
  "incrementSeconds": 2,
  "difficulty": "HARD"
}
```

상태 변경 HTTP 요청은 `X-CSRF-Token` 헤더가 필요합니다. 클라이언트가 보낸 사용자 ID, 색상, 결과, 체크 여부는 신뢰하지 않습니다.

## WebSocket

게임 채널은 `/ws/games/{gameId}`입니다. 인증된 참가자만 연결할 수 있습니다.

클라이언트 명령:

- `JOIN_GAME`
- `MOVE`
- `RESIGN`
- `OFFER_DRAW`
- `ACCEPT_DRAW`
- `DECLINE_DRAW`
- `PING`

서버 이벤트:

- `CONNECTED`
- `PLAYER_JOINED`
- `PLAYER_DISCONNECTED`
- `GAME_STARTED`
- `GAME_STATE`
- `GAME_STATE_UPDATED`
- `MOVE_REJECTED`
- `CHECK`
- `DRAW_OFFERED`
- `DRAW_DECLINED`
- `GAME_FINISHED`
- `ERROR`
- `PONG`

## 보안 고려사항

- OAuth Client Secret과 세션 secret은 코드에 저장하지 않습니다.
- 세션 쿠키는 HttpOnly이며 운영 환경에서는 Secure가 적용됩니다.
- SameSite=Lax와 OAuth state 검증을 사용합니다.
- SQL은 prepared statement만 사용합니다.
- 수식 파서는 Kotlin, JavaScript, 리플렉션, 파일, 네트워크, 프로세스 실행을 지원하지 않는 허용 문법 파서입니다.
- WebSocket 메시지 크기와 수식 길이/복잡도 제한이 있습니다.
- 게임별 `Mutex`로 이동과 저장을 직렬화합니다.
- 완료된 게임은 나중 요청으로 결과를 덮어쓰지 않습니다.

## 대안으로 고려한 기술

- 프런트엔드 프레임워크: 기존 프런트엔드가 없어 MVP에는 과하다고 판단했습니다.
- ORM/SQL DSL: 스키마가 작고 명시적 트랜잭션이 중요해 JDBC를 선택했습니다.
- 수식 파싱 라이브러리: 안전 경계와 허용 문법을 명확히 하기 위해 직접 재귀 하강 파서를 구현했습니다.
- 정규화 pieces 테이블: MVP에서는 JSON 스냅샷 + moves가 복원성과 구현 속도의 균형이 좋습니다.

## 현재 제한사항

- 공식 랭크, 자동 매칭, ELO, 관전, 채팅은 없습니다.
- 연결 종료 후 자동 패배 타이머는 아직 관리 정책 확장 지점으로만 남아 있으며, MVP는 게임을 유지하고 재접속 시 최신 상태를 복원합니다.
- 자동 체크메이트와 스테일메이트 판정, 승격 기물 선택은 구현하지 않았습니다.
