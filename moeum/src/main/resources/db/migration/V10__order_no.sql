-- =====================================================================
-- V10__order_no.sql
-- 사람이 읽는 주문번호와 셀러 주문 목록용 인덱스 (와이어프레임 G6 주문 목록).
--
-- 지금까지 묶음을 가리키는 값은 session_token(cs_xxx) 과 order_token(ord_xxx)
-- 둘뿐이었다. 둘 다 주소창에 실리는 임의 문자열이라 셀러와 구매자가 전화로
-- 주고받을 수 없고, 화면의 검색창은 '주문번호'로 찾게 되어 있다.
--
-- 형식은 ORD-{yyMMdd}-{id} 다. 그날의 순번을 쓰지 않는다 — 순번을 매기려면
-- 카운터 행을 잠가야 하고, 결제 시작 경로에 락을 하나 더 놓는 값이 아니다.
-- id 는 이미 유일하므로 날짜와 붙이면 그대로 유일하다.
--
-- NULL 을 허용한다. 결제 전 체크아웃 세션(CREATED)에는 주문번호가 없다 —
-- 15분 뒤 사라질 자리에 번호를 붙이면 번호가 구멍투성이가 된다.
-- 발급 시점은 order_token 과 같은 /pay 다.
--
-- idx_group_seller_created 는 셀러 주문 목록 전용이다. 기존
-- idx_group_seller (seller_id, status) 는 상태를 IN 으로 거르고 최신순으로
-- 정렬하는 이 화면의 쿼리에서 정렬을 태우지 못한다.
-- =====================================================================

ALTER TABLE order_group
    ADD COLUMN order_no VARCHAR(20) NULL
        COMMENT '사람이 읽는 주문번호 ORD-YYMMDD-{id}. /pay 에서 발급, 그 전에는 NULL',
    ADD UNIQUE KEY uk_group_order_no (order_no),
    ADD KEY idx_group_seller_created (seller_id, created_at);
