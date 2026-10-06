-- #59 A안: 이메일을 수집하지 않는다 — 로그인 식별자를 이메일에서 임의의 아이디로 전환.
--
-- 이메일은 로그인 식별자로만 쓰였고(메일을 보내는 곳이 없다) 그 하나 때문에 개인정보 수집·고지·소유 확인·
-- 탈퇴 경로가 전부 따라왔다. 수집 자체를 없애면 그 부담이 사라진다. 대가는 비밀번호 분실 시 복구 수단이
-- 없다는 것이고, 가입 폼의 안내 문구로 수용한다.
--
-- 컬럼 이름만 바꾸고 값은 건드리지 않는다. 전환 전에 가입한 계정은 기존 이메일 문자열이 그대로 아이디가
-- 되어 같은 값으로 계속 로그인할 수 있다(로그인 DTO 는 형식을 검사하지 않고, 가입 DTO 만 새 규칙을 강제한다).
-- 타입(VARCHAR(255) NULL)은 유지 — prod 는 ddl-auto: validate 라 엔티티 정의와 맞아야 기동한다.
ALTER TABLE users RENAME COLUMN email TO login_id;
ALTER TABLE users RENAME INDEX uk_users_email TO uk_users_login_id;
