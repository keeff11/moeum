-- =====================================================================
-- V1__init.sql
-- docs/schema.sql (v3) 의 DDL 전체.
-- 이후 스키마 변경은 이 파일을 고치지 말고 새 Flyway 파일(V2__...)로 추가한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 셀러 — 배송비의 주체
-- ---------------------------------------------------------------------
CREATE TABLE seller (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    kakao_id            VARCHAR(64)  NOT NULL,
    store_slug          VARCHAR(64)  NOT NULL COMMENT '판매공간 URL 식별자',
    review_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING, APPROVED, REJECTED',

    shipping_fee        INT          NOT NULL DEFAULT 0 COMMENT '주문 묶음당 1회 부과',
    free_shipping_over  INT              NULL COMMENT '이 금액 이상 무료. NULL이면 미적용',

    business_no_enc     VARBINARY(255)   NULL COMMENT '사업자번호(암호화)',
    settlement_acct_enc VARBINARY(255)   NULL COMMENT '정산계좌(암호화)',
    representative_name VARCHAR(50)      NULL,
    phone               VARCHAR(20)      NULL,
    email               VARCHAR(120)     NULL,
    approved_at         DATETIME(6)      NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_sale_form_slug (seller_id, slug),
    KEY idx_sale_form_status (status, closes_at),
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
    session_token  VARCHAR(40)  NOT NULL COMMENT 'cs_xxx — B5 진입 시 발급',
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_session (session_token),
    UNIQUE KEY uk_group_order   (order_token),
    KEY idx_group_buyer  (buyer_id, created_at),
    KEY idx_group_seller (seller_id, status),
    KEY idx_group_status_updated (status, updated_at),
    CONSTRAINT fk_group_buyer  FOREIGN KEY (buyer_id)  REFERENCES buyer (id),
    CONSTRAINT fk_group_seller FOREIGN KEY (seller_id) REFERENCES seller (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
-- ---------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(30)  NOT NULL COMMENT 'ORDER_GROUP, ORDER, SALE_FORM, PAYMENT',
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    payload        JSON         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING, SENT, DEAD',
    retry_count    INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)     NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at        DATETIME(6)      NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_pending (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
