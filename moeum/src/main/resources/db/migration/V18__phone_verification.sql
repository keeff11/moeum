-- =====================================================================
-- V18__phone_verification.sql
-- 알림 받을 번호 문자 인증 (D-064).
--
-- 알림톡은 지금 배송지 번호로 나간다 (D-040). 그 번호는 수령인 번호라 선물 주문이면
-- 구매 사실이 제3자에게 가고, 오타가 나도 막을 방법이 없었다. 구매자 본인 번호를
-- 문자 인증으로 한 번 받아 두고, 있으면 그쪽으로 보낸다.
--
-- buyer.notify_phone : 인증을 마친 번호만 들어간다. 비어 있으면 예전처럼 배송지 번호로 간다
-- phone_verification : 발급한 인증번호. 발송 간격 · 하루 한도 · 시도 횟수를 여기서 센다.
--                      세션에 두지 않는 이유는 하루 한도 때문이다 — 세션을 새로 열면
--                      한도가 초기화되어 문자 요금을 무한히 태울 수 있다
--
-- 인증번호는 평문으로 두지 않는다(code_hash). 6자리라 해시만으로 못 푸는 값은 아니지만,
-- 조회 권한만 있는 사람이 남의 번호를 바로 인증하는 것은 막는다.
-- =====================================================================

ALTER TABLE buyer
    ADD COLUMN notify_phone VARCHAR(20) NULL
        COMMENT '알림 받을 번호. 문자 인증을 마친 값만 들어간다 (숫자만)'
        AFTER payer_id,
    ADD COLUMN notify_phone_verified_at DATETIME(6) NULL
        COMMENT 'notify_phone 을 인증한 시각'
        AFTER notify_phone;

CREATE TABLE phone_verification (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    buyer_id     BIGINT       NOT NULL,
    phone        VARCHAR(20)  NOT NULL COMMENT '인증할 번호 (숫자만)',
    code_hash    CHAR(64)     NOT NULL COMMENT 'SHA-256(번호:인증번호) hex',
    attempts     INT          NOT NULL DEFAULT 0 COMMENT '틀린 횟수',
    expires_at   DATETIME(6)  NOT NULL,
    verified_at  DATETIME(6)  NULL COMMENT '맞힌 시각. 찬 행은 다시 쓰지 않는다',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_phone_verification_buyer (buyer_id, created_at),
    KEY idx_phone_verification_phone (phone, created_at),
    CONSTRAINT fk_phone_verification_buyer FOREIGN KEY (buyer_id) REFERENCES buyer (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
