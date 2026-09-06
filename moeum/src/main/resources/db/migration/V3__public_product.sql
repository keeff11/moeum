-- =====================================================================
-- V3__public_product.sql
-- 구매자용 공개 상품 API(GET /products/{id}) 를 위한 두 가지를 추가한다.
--
-- 1. seller.store_name — 공개 페이지에 표시할 상호명.
--    지금까지 있던 이름은 store_slug(URL 식별자)와 representative_name(대표자 실명)뿐이다.
--    실명을 공개 상품 페이지에 노출할 수 없어 표시 전용 컬럼을 따로 둔다.
-- 2. sale_form_image — 상품 이미지.
--
-- 두 컬럼 모두 NULL 을 허용한다. 이미 쌓인 셀러 행이 있어 NOT NULL 로 넣을 수 없다.
-- store_name 이 비면 응답에서 store_slug 로 대체한다.
-- =====================================================================

ALTER TABLE seller
    ADD COLUMN store_name VARCHAR(60) NULL COMMENT '공개 표시용 상호명. 비면 store_slug 로 대체' AFTER store_slug;

-- ---------------------------------------------------------------------
-- 상품 이미지. 순서가 곧 노출 순서이고 images[0] 이 대표 이미지다.
--
-- 파일 업로드 인프라가 아직 없다. 지금은 셀러가 외부 URL 을 입력하는 형태이고,
-- 업로드가 붙어도 이 컬럼을 채우는 주체만 바뀐다.
--
-- 폼이 지워지면 이미지도 같이 지운다 — 이미지만 남아 있을 이유가 없다.
-- ---------------------------------------------------------------------
CREATE TABLE sale_form_image (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    sale_form_id BIGINT       NOT NULL,
    url          VARCHAR(500) NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_sale_form_image_form (sale_form_id, sort_order),
    CONSTRAINT fk_sale_form_image_form FOREIGN KEY (sale_form_id)
        REFERENCES sale_form (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
