-- =====================================================================
-- V14__option_stock.sql
-- 옵션별 재고 (D-054).
--
-- 재고는 판매 폼 단위였다 (sale_form.stock_max / held / sold). 색상 A · B 처럼
-- 옵션마다 물량이 정해진 단독 판매에서 옵션 A 가 다 나갔는데 폼 재고가 남아
-- 계속 팔리는 문제가 있어 옵션에도 재고를 둔다.
--
-- stock_max 는 NULL 을 허용한다. NULL 이면 이 옵션은 재고 상한이 없고 폼 재고만
-- 따른다 — 기존 옵션 전부가 이 상태다. 한 폼 안에서는 전부 NULL 이거나 전부
-- 값이 있어야 한다(서비스가 검사한다). 값이 있으면 폼의 stock_max 는 옵션 합계로
-- 서버가 계산한다.
--
-- held · sold 는 stock_max 가 NULL 이어도 움직인다. 나중에 재고를 넣더라도
-- 그때까지 나간 수량이 맞아야 하고, 셀러 화면의 옵션별 판매 수량도 여기서 읽는다.
--
-- 확보는 sale_form 과 같은 조건부 UPDATE 한 방이다. 폼을 먼저 잠그고 그 폼의
-- 옵션을 id 오름차순으로 잠근다 — 순서가 어긋나면 데드락이 난다.
-- =====================================================================

ALTER TABLE product_option
    ADD COLUMN stock_max INT NULL
        COMMENT '옵션 재고 상한. NULL 이면 폼 재고만 따른다'
        AFTER deposit2_amount,
    ADD COLUMN held INT NOT NULL DEFAULT 0
        COMMENT '결제 확정 전 선점 수량. 조건부 UPDATE 전용'
        AFTER stock_max,
    ADD COLUMN sold INT NOT NULL DEFAULT 0
        COMMENT '판매 확정 수량. 조건부 UPDATE 전용'
        AFTER held,
    ADD CONSTRAINT ck_option_qty CHECK (held >= 0 AND sold >= 0);
