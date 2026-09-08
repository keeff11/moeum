-- =====================================================================
-- V7__seller_profile.sql
-- 셀러 페이지(B0) 헤더에 쓰는 공개 프로필.
--
-- 지금까지 seller 에 있던 공개 가능한 값은 store_name · store_slug 뿐이라
-- 헤더의 소개글 · 소셜 주소 · 프로필 이미지를 담을 곳이 없었다.
--
-- 대표자 실명 · 연락처 · 사업자번호와 성격이 다르다. 그쪽은 심사용이라
-- 구매자에게 나가면 안 되고, 이 세 개는 구매자에게 보이라고 받는 값이다.
-- 같은 테이블에 있지만 응답 DTO 에서 갈린다.
--
-- profile_image_key 는 전체 URL 이 아니라 S3 객체 키다 (V4 와 같은 이유) —
-- 버킷을 바꾸거나 CloudFront 를 앞에 세울 때 쌓인 행을 고치지 않아도 된다.
-- =====================================================================

ALTER TABLE seller
    ADD COLUMN bio VARCHAR(100) NULL
        COMMENT '한 줄 소개. 셀러 페이지 헤더에 노출된다',
    ADD COLUMN social_url VARCHAR(200) NULL
        COMMENT '인스타 등 소셜 주소. 헤더 버튼에 걸린다',
    ADD COLUMN profile_image_key VARCHAR(500) NULL
        COMMENT 'S3 객체 키. 전체 URL 을 저장하지 않는다';
