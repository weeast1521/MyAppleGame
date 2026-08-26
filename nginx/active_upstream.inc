# 현재 트래픽을 받는 색 — deploy.sh가 blue↔green으로 바꿔 쓰고 nginx -s reload 한다.
# (server 블록 안에 include 되는 파일이므로 set 지시어만 넣는다)
# 확장자가 .conf가 아닌 이유: nginx는 conf.d/*.conf를 http 컨텍스트에 자동 include
# 하는데, set은 http 레벨에서 문법 오류이기 때문. .inc는 자동 include를 피한다.
set $active_app "app_blue";
