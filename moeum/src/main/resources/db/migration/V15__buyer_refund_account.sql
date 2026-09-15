-- =====================================================================
-- V15__buyer_refund_account.sql
-- 환불 계좌 (D-057).
--
-- B5 1차금 결제 화면에서 배송지와 함께 받는 필수 항목이다. 판매 유형을 가리지
-- 않는다 — 단독(재고) 판매도 같이 받는다. 결제 수단으로 되돌리지 못하는 환불
-- (정산이 끝난 뒤의 환불 · S14 / B8-C3)에서 돈을 보낼 곳이 여기밖에 없다.
--
-- buyer_address 와 같은 모양이다. 구매자당 1건(uk_refund_account_buyer)이고
-- PUT 한 번으로 통째로 교체한다.
--
-- 스냅샷을 뜨지 않는다. 배송지(shipping)와 다른 점이다. 배송지는 "어디로
-- 보냈는가"가 주문 시점으로 굳어야 근거가 되지만, 환불 계좌는 돈을 보내는
-- 시점에 살아 있는 계좌여야 한다. 몇 주 뒤의 정산 후 환불에서 주문 당시의
-- 계좌를 쓰면 해지된 계좌로 보내게 된다.
--
-- 계좌번호는 AES-256-GCM 으로 암호화한다(EncryptedStringConverter). 셀러의
-- 정산계좌와 같은 키를 쓰고, 조건 조회를 하지 않으므로 검색 가능성을 포기했다.
-- 은행명·예금주는 암호화하지 않는다 — 계좌번호 없이는 송금에 쓸 수 없고,
-- 예금주는 마스킹 표시에 필요하다.
-- =====================================================================

CREATE TABLE buyer_refund_account (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    buyer_id        BIGINT         NOT NULL,
    bank            VARCHAR(30)    NOT NULL COMMENT '은행명. 화면의 선택지를 그대로 받는다',
    account_no_enc  VARBINARY(255) NOT NULL COMMENT '계좌번호(암호화)',
    holder_name     VARCHAR(50)    NOT NULL COMMENT '예금주',
    updated_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_account_buyer (buyer_id),
    CONSTRAINT fk_refund_account_buyer FOREIGN KEY (buyer_id) REFERENCES buyer (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
