-- =====================================================================
-- V8__wishlist.sql
-- 찜(하트). 셀러 페이지 카드와 상품 상세에서 누른다.
--
-- 판매 폼 단위다. 재고 · 마감 · 목표수량이 전부 폼 단위라 구매자가 보는
-- '상품'의 실체가 폼이고, 찜도 같은 단위여야 한다 (D-021 과 같은 이유).
--
-- 셀러 페이지 목록 응답에는 이 값이 들어가지 않는다. 넣는 순간 그 응답이
-- 사용자별로 갈려 모두에게 같은 응답을 줄 수 없게 된다 (D-029) —
-- 프론트가 /me/wishlist 로 id 목록만 따로 받아 합친다.
--
-- uk_wishlist 가 중복 방지의 전부다. 애플리케이션에서 먼저 확인하더라도
-- 두 요청이 동시에 통과할 수 있으므로 최종 판정은 DB 에 맡긴다.
-- =====================================================================

CREATE TABLE wishlist (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    buyer_id     BIGINT      NOT NULL,
    sale_form_id BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wishlist (buyer_id, sale_form_id),
    -- 폼이 마감될 때 찜한 사람에게 알리려면 폼 기준 조회가 필요하다
    KEY idx_wishlist_form (sale_form_id),
    CONSTRAINT fk_wishlist_buyer FOREIGN KEY (buyer_id) REFERENCES buyer (id),
    CONSTRAINT fk_wishlist_form FOREIGN KEY (sale_form_id) REFERENCES sale_form (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='찜한 판매 폼';
