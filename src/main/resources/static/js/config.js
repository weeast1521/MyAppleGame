'use strict';

/* ================================================================
 * 소셜 로그인 설정
 *  - 카카오: developers.kakao.com 앱의 "REST API 키"
 *  - 네이버: developers.naver.com 앱의 "Client ID"
 *  - redirectUri 는 각 개발자 콘솔에 동일하게 등록되어 있어야 합니다.
 *  - 비워두면 소셜 로그인 버튼 클릭 시 안내 메시지가 표시됩니다.
 * ================================================================ */
const OAUTH_CONFIG = {
    kakao: {
        clientId: '',
        redirectUri: `${location.origin}/oauth/kakao/callback.html`,
    },
    naver: {
        clientId: '',
        redirectUri: `${location.origin}/oauth/naver/callback.html`,
    },
};
