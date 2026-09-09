-- =====================================================================
-- 공동구매 · 단독 판매 플랫폼 스키마 v4 (MySQL 8.0)
--
-- 이 파일은 읽으라고 있는 스냅샷이지 실행하는 파일이 아니다.
-- 스키마의 원본은 moeum/src/main/resources/db/migration/ 의 Flyway 파일들이고,
-- 여기는 V1~V10 을 전부 적용한 결과를 한 곳에 모아 둔 것이다.
-- 스키마를 바꿀 때는 새 마이그레이션 파일을 만들고 이 파일을 같이 고친다 —
-- 둘이 어긋나면 FlywayMigrationTest 가 깨진다.
--
-- v2 대비 변경점 (프론트 API 계약 반영)
--   · 금액 구조 변경 — 옵션이 deposit1/deposit2 를 절대값으로 갖는다
--     (기존 base_price + extra_price 방식 폐기)
--   · 배송비를 셀러 단위로 이동 — 한 셀러 주문은 배송비 1회
--   · sale_form.max_per_user 추가 (1인당 구매 상한)
--   · buyer_address 분리 — /me/address 로 재사용되는 단일 배송지
--   · order_group.status 에 CONFIRMING 추가 (프론트 계약)
--   · checkout_session = order_group (CREATED 상태). 별도 테이블 아님
--
-- v3(=V1) 이후 마이그레이션이 더한 것
--   · V2  SPRING_SESSION · SPRING_SESSION_ATTRIBUTES — 세션 저장소를 DB 로 (D-020)
--   · V3  seller.store_name, sale_form_image — 공개 상품 API
--   · V4  sale_form_image.url → object_key — 전체 URL 대신 S3 객체 키
--   · V5  sale_form.shortfall_done_at — 목표수량 미달 처리 멱등 가드
--   · V6  outbox.next_attempt_at — 재시도 백오프 겸 처리 중 임대
--   · V7  seller.bio · social_url · profile_image_key — 셀러 페이지(B0) 헤더
--   · V8  wishlist — 찜
--   · V9  seller.public_contact — 구매자 문의 연락처 (G12)
--   · V10 order_group.order_no — 사람이 읽는 주문번호 (G6)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 셀러 — 배송비의 주체
--
-- 심사·정산용 값(representative_name · phone · business_no_enc · settlement_acct_enc)과
-- 구매자에게 보이라고 받는 공개 값(store_name · bio · social_url · profile_image_key ·
-- public_contact)이 한 테이블에 있다. 응답 DTO 에서 갈린다 — 심사용은 나가면 안 된다.
-- ---------------------------------------------------------------------
CREATE TABLE seller (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    kakao_id            VARCHAR(64)  NOT NULL,
    store_slug          VARCHAR(64)  NOT NULL COMMENT '판매공간 URL 식별자',
    store_name          VARCHAR(60)      NULL COMMENT '공개 표시용 상호명. 비면 store_slug 로 대체',
    review_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING, APPROVED, REJECTED',

    shipping_fee        INT          NOT NULL DEFAULT 0 COMMENT '주문 묶음당 1회 부과',
    free_shipping_over  INT              NULL COMMENT '이 금액 이상 무료. NULL이면 미적용',

    business_no_enc     VARBINARY(255)   NULL COMMENT '사업자번호(암호화)',
    settlement_acct_enc VARBINARY(255)   NULL COMMENT '정산계좌(암호화)',
    representative_name VARCHAR(50)      NULL,
    phone               VARCHAR(20)      NULL COMMENT '심사·정산 담당자 연락처. 공개하지 않는다',
    email               VARCHAR(120)     NULL,
    approved_at         DATETIME(6)      NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),

    -- 셀러 페이지(B0) 헤더에 노출되는 공개 프로필 (V7, V9)
    bio                 VARCHAR(100)     NULL COMMENT '한 줄 소개. 셀러 페이지 헤더에 노출된다',
    social_url          VARCHAR(200)     NULL COMMENT '인스타 등 소셜 주소. 헤더 버튼에 걸린다',
    profile_image_key   VARCHAR(500)     NULL COMMENT 'S3 객체 키. 전체 URL 을 저장하지 않는다',
    public_contact      VARCHAR(100)     NULL COMMENT '구매자 문의 연락처. 심사용 phone 과 별개로 공개된다',

    PRIMARY KEY (id),
    UNIQUE KEY uk_seller_kakao (kakao_id),
    UNIQUE KEY uk_seller_slug  (store_slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 판매 폼 — 재고 확보의 단위
-- ---------------------------------------------------------------------
CREATE TABLE sale_form (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    seller_id        BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    slug             VARCHAR(120) NOT NULL,
    sale_type        VARCHAR(10)  NOT NULL COMMENT 'GROUP, SOLO',
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                     COMMENT 'DRAFT, SELLING, PAUSED, CLOSED, ENDED',

    stock_max        INT          NOT NULL,
    held             INT          NOT NULL DEFAULT 0 COMMENT '결제 확정 전 선점 수량',
    sold             INT          NOT NULL DEFAULT 0,
    target_qty       INT              NULL COMMENT '목표수량(최소). SOLO 는 NULL',
    max_per_user     INT              NULL COMMENT '1인당 구매 상한. NULL이면 무제한',

    opens_at         DATETIME(6)      NULL,
    closes_at        DATETIME(6)      NULL,
    extended_count   INT          NOT NULL DEFAULT 0,
    shortfall_policy VARCHAR(10)      NULL COMMENT 'CANCEL, EXTEND, PROCEED',

    ship_start_text  VARCHAR(100)     NULL COMMENT '8월 20일(월) 순차발송 — 서버가 포맷',
    min_order_amount INT          NOT NULL DEFAULT 0,
    description_json JSON             NULL COMMENT 'Lexical JSON (ADR 0001)',
    progress_public  TINYINT(1)   NOT NULL DEFAULT 1,

    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),

    shortfall_done_at DATETIME(6)     NULL
                     COMMENT '목표수량 미달 처리를 끝낸 시각. NULL 이면 아직 안 훑었다',

    PRIMARY KEY (id),
    UNIQUE KEY uk_sale_form_slug (seller_id, slug),
    KEY idx_sale_form_status (status, closes_at),
    -- 미달 처리 배치가 매분 도는 조회. CLOSED 이면서 아직 안 훑은 폼만 집는다
    KEY idx_sale_form_shortfall (status, shortfall_done_at),
    CONSTRAINT fk_sale_form_seller FOREIGN KEY (seller_id) REFERENCES seller (id),
    CONSTRAINT ck_sale_form_qty CHECK (held >= 0 AND sold >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE sale_form_history (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    sale_form_id BIGINT       NOT NULL,
    field        VARCHAR(50)  NOT NULL,
    old_value    VARCHAR(500)     NULL,
    new_value    VARCHAR(500)     NULL,
    changed_by   BIGINT           NULL COMMENT 'seller.id',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_history_form (sale_form_id, created_at),
    CONSTRAINT fk_history_form FOREIGN KEY (sale_form_id) REFERENCES sale_form (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 상품 이미지 — 순서가 곧 노출 순서이고 images[0] 이 대표 이미지다
--
-- 전체 URL 이 아니라 S3 객체 키를 담는다. 버킷을 바꾸거나 CloudFront 를 앞에
-- 세울 때 쌓인 행을 전부 고치지 않고 응답 조립 코드 한 곳만 바꾸면 된다.
--
-- 폼이 지워지면 이미지도 같이 지운다 — 이미지만 남아 있을 이유가 없다.
-- ---------------------------------------------------------------------
CREATE TABLE sale_form_image (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    sale_form_id BIGINT       NOT NULL,
    object_key   VARCHAR(500) NOT NULL
                 COMMENT 'S3 객체 키 (예: sale-forms/12/9f3a....jpg). 전체 URL 을 저장하지 않는다',
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_sale_form_image_form (sale_form_id, sort_order),
    CONSTRAINT fk_sale_form_image_form FOREIGN KEY (sale_form_id)
        REFERENCES sale_form (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 상품 — 가격은 옵션이 갖는다
-- ---------------------------------------------------------------------
CREATE TABLE product (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    sale_form_id BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_product_form (sale_form_id, sort_order),
    CONSTRAINT fk_product_form FOREIGN KEY (sale_form_id) REFERENCES sale_form (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 옵션 — 금액을 절대값으로 갖는다 (기준가 + 추가금 방식 아님)
-- 옵션 자체 재고는 없다. 배송비도 없다 (셀러 단위)
-- ---------------------------------------------------------------------
CREATE TABLE product_option (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    product_id      BIGINT       NOT NULL,
    name            VARCHAR(100) NOT NULL,
    deposit1_amount INT          NOT NULL COMMENT '1차금 절대값 — 주문 시 결제',
    deposit2_amount INT          NOT NULL DEFAULT 0 COMMENT '2차금 상품 잔금. 1차금이 전액이면 0',
    sort_order      INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_option_product (product_id, sort_order),
    CONSTRAINT fk_option_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_option_amount CHECK (deposit1_amount >= 0 AND deposit2_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 구매자
-- ---------------------------------------------------------------------
CREATE TABLE buyer (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    kakao_id   VARCHAR(64) NOT NULL,
    nickname   VARCHAR(50)     NULL COMMENT '카카오 프로필. 수령인 이름과는 별개',
    payer_id   VARCHAR(64)     NULL COMMENT 'point3 결제자 식별값. 받은 문자열 그대로',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_buyer_kakao (kakao_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 배송지 — /me/address. 구매자당 1건, 주문 시 스냅샷으로 복사
-- ---------------------------------------------------------------------
CREATE TABLE buyer_address (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    buyer_id       BIGINT       NOT NULL,
    recipient_name VARCHAR(50)  NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    postal_code    VARCHAR(10)      NULL,
    address1       VARCHAR(255) NOT NULL,
    address2       VARCHAR(255)     NULL,
    memo           VARCHAR(200)     NULL,
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_address_buyer (buyer_id),
    CONSTRAINT fk_address_buyer FOREIGN KEY (buyer_id) REFERENCES buyer (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 찜(하트) — 셀러 페이지 카드와 상품 상세에서 누른다
--
-- 판매 폼 단위다. 재고 · 마감 · 목표수량이 전부 폼 단위라 구매자가 보는
-- '상품'의 실체가 폼이고, 찜도 같은 단위여야 한다 (D-021 과 같은 이유).
--
-- 셀러 페이지 목록 응답에는 이 값이 들어가지 않는다. 넣는 순간 그 응답이
-- 사용자별로 갈려 모두에게 같은 응답을 줄 수 없게 된다 (D-029) —
-- 프론트가 /me/wishlist 로 id 목록만 따로 받아 합친다.
--
-- uk_wishlist 가 중복 방지의 전부다. 두 요청이 동시에 통과할 수 있으므로
-- 최종 판정은 DB 에 맡긴다.
-- ---------------------------------------------------------------------
CREATE TABLE wishlist (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    buyer_id     BIGINT      NOT NULL,
    sale_form_id BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wishlist (buyer_id, sale_form_id),
    -- 폼이 마감될 때 찜한 사람에게 알리려면 폼 기준 조회가 필요하다
    KEY idx_wishlist_form (sale_form_id),
    CONSTRAINT fk_wishlist_buyer FOREIGN KEY (buyer_id)     REFERENCES buyer (id),
    CONSTRAINT fk_wishlist_form  FOREIGN KEY (sale_form_id) REFERENCES sale_form (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='찜한 판매 폼';


-- ---------------------------------------------------------------------
-- 장바구니 — 셀러당 1개. 재고를 잡지 않는다
-- ---------------------------------------------------------------------
CREATE TABLE cart (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    buyer_id   BIGINT      NOT NULL,
    seller_id  BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
               ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_buyer_seller (buyer_id, seller_id),
    CONSTRAINT fk_cart_buyer  FOREIGN KEY (buyer_id)  REFERENCES buyer (id),
    CONSTRAINT fk_cart_seller FOREIGN KEY (seller_id) REFERENCES seller (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE cart_item (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    cart_id      BIGINT      NOT NULL,
    sale_form_id BIGINT      NOT NULL COMMENT '담을 때 cart.seller_id 와 일치 검증',
    product_id   BIGINT      NOT NULL,
    option_id    BIGINT      NOT NULL,
    qty          INT         NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_item (cart_id, option_id),
    KEY idx_cart_item_form (sale_form_id),
    CONSTRAINT fk_cart_item_cart    FOREIGN KEY (cart_id)      REFERENCES cart (id),
    CONSTRAINT fk_cart_item_form    FOREIGN KEY (sale_form_id) REFERENCES sale_form (id),
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id)   REFERENCES product (id),
    CONSTRAINT fk_cart_item_option  FOREIGN KEY (option_id)    REFERENCES product_option (id),
    CONSTRAINT ck_cart_item_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 주문 묶음 = checkout_session
--   결제 1회 · 배송지 1개 · 배송비 1회
--   바로구매는 orders 1건, 장바구니는 orders N건인 묶음일 뿐이다
-- ---------------------------------------------------------------------
CREATE TABLE order_group (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    session_token  VARCHAR(40)  NOT NULL COMMENT 'cs_xxx — B2 옵션·수량 확정 시 발급',
    order_token    VARCHAR(40)      NULL COMMENT 'ord_xxx — /pay 시점에 발급',
    buyer_id       BIGINT       NOT NULL,
    seller_id      BIGINT       NOT NULL COMMENT '한 묶음은 한 셀러로 제한',

    deposit1_total INT          NOT NULL COMMENT '1차금 합계 — B5 청구액',
    deposit2_total INT          NOT NULL DEFAULT 0 COMMENT '2차금 상품 잔금 합계',
    shipping_fee   INT          NOT NULL DEFAULT 0 COMMENT '셀러 배송비 1회분 스냅샷',

    status         VARCHAR(20)  NOT NULL DEFAULT 'CREATED'
                   COMMENT 'CREATED, PAY_PENDING, CONFIRMING, PAID, SECOND_PENDING, SECOND_PAID, SHIPPED, CANCELED, EXPIRED, FAILED',
    fail_reason    VARCHAR(100)     NULL,
    canceled_at    DATETIME(6)      NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),

    order_no       VARCHAR(20)      NULL
                   COMMENT '사람이 읽는 주문번호 ORD-YYMMDD-{id}. /pay 에서 발급, 그 전에는 NULL',

    PRIMARY KEY (id),
    UNIQUE KEY uk_group_session  (session_token),
    UNIQUE KEY uk_group_order    (order_token),
    UNIQUE KEY uk_group_order_no (order_no),
    KEY idx_group_buyer  (buyer_id, created_at),
    KEY idx_group_seller (seller_id, status),
    -- 셀러 주문 목록(G6) 전용. idx_group_seller 는 상태를 IN 으로 거르고
    -- 최신순으로 정렬하는 그 화면의 쿼리에서 정렬을 태우지 못한다
    KEY idx_group_seller_created (seller_id, created_at),
    KEY idx_group_status_updated (status, updated_at),
    CONSTRAINT fk_group_buyer  FOREIGN KEY (buyer_id)  REFERENCES buyer (id),
    CONSTRAINT fk_group_seller FOREIGN KEY (seller_id) REFERENCES seller (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- order_no 는 그날의 순번을 쓰지 않는다 — 순번을 매기려면 카운터 행을 잠가야 하고,
-- 결제 시작 경로에 락을 하나 더 놓을 값이 아니다. id 가 이미 유일하다.
-- 결제 전 체크아웃 세션(CREATED)에는 번호를 붙이지 않는다. 15분 뒤 사라질 자리다.

-- 2차 결제 청구액 = deposit2_total + shipping_fee


-- ---------------------------------------------------------------------
-- 주문 — 판매 폼별. 진행 상태 머신이 여기서 돈다
-- ---------------------------------------------------------------------
CREATE TABLE orders (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_group_id BIGINT       NOT NULL,
    sale_form_id   BIGINT       NOT NULL,
    qty            INT          NOT NULL COMMENT '이 폼에서 확보한 총 수량',
    deposit1_sum   INT          NOT NULL,
    deposit2_sum   INT          NOT NULL DEFAULT 0,
    status         VARCHAR(20)  NOT NULL DEFAULT 'CREATED'
                   COMMENT 'CREATED, PAID, RECRUITING, CLOSED, PRODUCING, ARRIVED, SHIPPED, CANCELED, EXPIRED',
    canceled_at    DATETIME(6)      NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_group_form (order_group_id, sale_form_id),
    KEY idx_orders_form (sale_form_id, status),
    CONSTRAINT fk_orders_group FOREIGN KEY (order_group_id) REFERENCES order_group (id),
    CONSTRAINT fk_orders_form  FOREIGN KEY (sale_form_id)   REFERENCES sale_form (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE order_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    option_id       BIGINT       NOT NULL,
    qty             INT          NOT NULL,
    deposit1_amount INT          NOT NULL COMMENT '주문 시점 스냅샷',
    deposit2_amount INT          NOT NULL DEFAULT 0 COMMENT '주문 시점 스냅샷',
    product_name    VARCHAR(200) NOT NULL COMMENT '표시용 스냅샷',
    option_name     VARCHAR(100) NOT NULL COMMENT '표시용 스냅샷',
    PRIMARY KEY (id),
    KEY idx_item_order (order_id),
    KEY idx_item_option (product_id, option_id),
    CONSTRAINT fk_item_order   FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_item_option  FOREIGN KEY (option_id)  REFERENCES product_option (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 재고 홀드 — 판매 폼 단위이므로 orders 에 붙는다
-- ---------------------------------------------------------------------
CREATE TABLE stock_hold (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    sale_form_id BIGINT      NOT NULL,
    qty          INT         NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'HELD'
                 COMMENT 'HELD, COMMITTED, RELEASED',
    expires_at   DATETIME(6) NOT NULL COMMENT '기본 15분',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_hold_order (order_id),
    KEY idx_hold_expire (status, expires_at),
    CONSTRAINT fk_hold_order FOREIGN KEY (order_id)     REFERENCES orders (id),
    CONSTRAINT fk_hold_form  FOREIGN KEY (sale_form_id) REFERENCES sale_form (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 결제 — 묶음당 최대 2행
-- ---------------------------------------------------------------------
CREATE TABLE payment (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    order_group_id  BIGINT      NOT NULL,
    phase           VARCHAR(10) NOT NULL COMMENT 'FIRST, SECOND',
    session_id      VARCHAR(128)    NULL COMMENT 'point3 sessionId',
    amount          INT         NOT NULL,
    supply_amount   INT             NULL,
    vat             INT             NULL,
    tax_free_amount INT         NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED'
                    COMMENT 'CREATED, CAPTURE_PENDING, CAPTURED, FAILED',
    fail_reason     VARCHAR(100)    NULL,
    refunded_amount INT         NOT NULL DEFAULT 0,
    captured_at     DATETIME(6)     NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_group_phase (order_group_id, phase),
    UNIQUE KEY uk_payment_session (session_id),
    KEY idx_payment_pending (status, updated_at),
    CONSTRAINT fk_payment_group FOREIGN KEY (order_group_id) REFERENCES order_group (id),
    CONSTRAINT ck_payment_refunded CHECK (refunded_amount >= 0 AND refunded_amount <= amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE payment_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id  BIGINT      NOT NULL,
    from_status VARCHAR(20)     NULL,
    to_status   VARCHAR(20) NOT NULL,
    reason      VARCHAR(200)    NULL,
    actor       VARCHAR(10) NOT NULL COMMENT 'USER, BATCH, SELLER, SYSTEM',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_event_payment (payment_id, created_at),
    CONSTRAINT fk_event_payment FOREIGN KEY (payment_id) REFERENCES payment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 취소 · 환불
-- ---------------------------------------------------------------------
CREATE TABLE refund (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id       BIGINT       NOT NULL,
    order_id         BIGINT           NULL COMMENT '특정 폼만 취소한 경우. 전액이면 NULL',
    point3_refund_id VARCHAR(128)     NULL,
    idempotency_key  VARCHAR(300)     NULL COMMENT '중복 차단용. 재시도용 아님',
    amount           INT          NOT NULL,
    tax_free_amount  INT          NOT NULL DEFAULT 0,
    vat              INT          NOT NULL DEFAULT 0,
    reason           VARCHAR(200)     NULL COMMENT '최대 200자',
    requested_by     VARCHAR(10)  NOT NULL COMMENT 'BUYER, SELLER, SYSTEM',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                     COMMENT 'PROCESSING, COMPLETED, FAILED',
    settled_manual   TINYINT(1)   NOT NULL DEFAULT 0
                     COMMENT '정산 완료 후 셀러 직접 환불 접수건',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_idem (idempotency_key),
    KEY idx_refund_payment (payment_id, created_at),
    KEY idx_refund_pending (status, updated_at),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT fk_refund_order   FOREIGN KEY (order_id)   REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 배송 — 묶음당 1건. 주문 시 buyer_address 를 스냅샷으로 복사
-- ---------------------------------------------------------------------
CREATE TABLE shipping (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_group_id BIGINT       NOT NULL,
    recipient_name VARCHAR(50)  NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    postal_code    VARCHAR(10)      NULL,
    address1       VARCHAR(255) NOT NULL,
    address2       VARCHAR(255)     NULL,
    memo           VARCHAR(200)     NULL,
    carrier        VARCHAR(50)      NULL,
    tracking_no    VARCHAR(50)      NULL,
    shipped_at     DATETIME(6)      NULL,
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipping_group (order_group_id),
    CONSTRAINT fk_shipping_group FOREIGN KEY (order_group_id) REFERENCES order_group (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- Outbox
--
-- next_attempt_at 이 두 가지를 겸한다.
--   ① 실패 후 백오프 — 실패할 때마다 뒤로 민다. created_at(적재 시각)으로는
--      계산할 수 없어 실패가 쌓여도 간격이 벌어지지 않는다
--   ② 처리 중 임대   — 집어갈 때도 앞으로 민다. 릴레이가 발송하는 동안 다른
--      인스턴스가 같은 행을 집으면 알림톡이 두 번 나간다.
--      프로세스가 죽으면 임대가 만료돼 자동으로 다시 잡힌다
-- ---------------------------------------------------------------------
CREATE TABLE outbox (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(30)  NOT NULL COMMENT 'ORDER_GROUP, ORDER, SALE_FORM, PAYMENT',
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         JSON         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING, SENT, DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500)     NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at         DATETIME(6)      NULL,
    next_attempt_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    COMMENT '이 시각 전에는 집지 않는다. 실패 백오프와 처리 중 임대를 겸한다',
    PRIMARY KEY (id),
    KEY idx_outbox_pending (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 인프라 테이블 — 도메인이 아니지만 같은 스키마에 산다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 세션 저장소 (V2, D-020). 톰캣 인메모리 대신 DB 에 담는다.
--
-- Spring Session 3.5.x 의 공식 스키마 그대로다. 손대지 않는다 —
-- 라이브러리가 이 컬럼명 · 타입을 그대로 쿼리한다.
-- 스키마는 Flyway 가 소유한다 (spring.session.jdbc.initialize-schema=never).
--
-- 시각은 전부 epoch millis(BIGINT) 다. 만료 정리 스케줄러가 EXPIRY_TIME 으로
-- 훑으므로 IX2 가 그 인덱스다.
-- ATTRIBUTE_BYTES 는 자바 직렬화 바이트라 사람이 읽을 수 있는 형태가 아니다.
-- 그래서 카카오 access token 은 여기 넣지 않는다 (D-020).
-- ---------------------------------------------------------------------
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL
                          COMMENT '초 단위. server.servlet.session.timeout 이 들어온다',
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100)     NULL,
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

-- 세션이 지워지면 속성도 같이 지워진다 (ON DELETE CASCADE)
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

-- flyway_schema_history 는 여기 없다. Flyway 가 스스로 만든다.


-- =====================================================================
-- 핵심 쿼리
-- =====================================================================

-- 1) 재고 확보 — 조건부 UPDATE. 영향 행 0이면 품절 또는 마감
-- UPDATE sale_form
--    SET held = held + :qty
--  WHERE id = :formId
--    AND status = 'SELLING'
--    AND stock_max - held - sold >= :qty
--    AND (closes_at IS NULL OR closes_at > NOW());

-- 2) 홀드 확정 / 해제
-- UPDATE sale_form SET held = held - :qty, sold = sold + :qty WHERE id = :formId;
-- UPDATE sale_form SET held = held - :qty                     WHERE id = :formId;

-- 3) 1인당 구매 상한 검사
-- SELECT COALESCE(SUM(o.qty), 0) FROM orders o
--   JOIN order_group g ON g.id = o.order_group_id
--  WHERE g.buyer_id = :buyerId AND o.sale_form_id = :formId
--    AND o.status NOT IN ('CANCELED','EXPIRED');

-- 4) 승인 대사 배치
-- SELECT * FROM payment
--  WHERE status = 'CAPTURE_PENDING'
--    AND updated_at < NOW(6) - INTERVAL 30 SECOND
--  ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED;

-- 5) 홀드 만료 배치 — CAPTURE_PENDING 묶음은 제외
-- SELECT h.* FROM stock_hold h
--   JOIN orders o       ON o.id = h.order_id
--   JOIN order_group g  ON g.id = o.order_group_id
--   LEFT JOIN payment p ON p.order_group_id = g.id AND p.status = 'CAPTURE_PENDING'
--  WHERE h.status = 'HELD' AND h.expires_at < NOW(6) AND p.id IS NULL
--  LIMIT 100 FOR UPDATE SKIP LOCKED;

-- 6) 2차금 청구 대상 — 묶음의 모든 주문이 ARRIVED
-- SELECT g.id FROM order_group g
--   JOIN orders o ON o.order_group_id = g.id
--  WHERE g.status = 'PAID'
--  GROUP BY g.id
-- HAVING SUM(o.status NOT IN ('ARRIVED','CANCELED')) = 0
--    AND SUM(o.status = 'ARRIVED') > 0;

-- 7) 발주서 — 폼별 · 옵션별 수량 집계
-- SELECT i.product_name, i.option_name, SUM(i.qty) AS total_qty
--   FROM order_item i JOIN orders o ON o.id = i.order_id
--  WHERE o.sale_form_id = :formId AND o.status NOT IN ('CANCELED','EXPIRED')
--  GROUP BY i.product_id, i.option_id;


-- =====================================================================
-- 확정된 정책
-- =====================================================================
-- (1) 배송비는 셀러 단위. 한 셀러의 여러 상품을 주문해도 1회만 부과
-- (2) 장바구니는 셀러당 1개. 다른 셀러 상품은 별도 장바구니
-- (3) 장바구니에 담긴 물건은 한 번에 배송된다
-- (4) 바로구매와 장바구니는 같은 order_group 구조 (orders 1건 vs N건)
-- (5) 옵션 가격은 절대값. deposit1 + deposit2 로 나뉜다
-- (6) 옵션별 재고는 관리하지 않는다. 재고는 sale_form 단위
-- (7) 장바구니는 재고를 잡지 않는다. 검증은 주문 생성 시점
-- (8) 배송지는 단일. buyer_address 를 주문 시 shipping 으로 스냅샷 복사
