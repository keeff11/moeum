-- =====================================================================
-- V11__second_charge.sql
-- 2차금 청구 이력 (와이어프레임 S10 일괄 청구).
--
-- 셀러가 '청구하기' 를 누르면 대상 묶음마다 한 행이 쌓인다. 청구 자체는
-- 알림(SECOND_PAYMENT_DUE)을 다시 내보내는 것이고, 주문 상태는 건드리지 않는다 —
-- 상태를 SECOND_PENDING 으로 올리면 isSecondPaymentDue() 가 false 가 되어
-- 정작 구매자의 결제가 막힌다 (D-035).
--
-- 유니크 제약을 두지 않는다. 미납이 이어지면 다시 청구하는 것이 정상이라
-- 묶음당 여러 행이 쌓인다. 중복 독촉은 가장 최근 charged_at 으로 판정하는
-- 쿨다운이 막는다 — 알림톡은 건당 발송 단가가 있고, 구매자에게 하루에
-- 여러 번 독촉이 가면 안 된다.
--
-- amount 는 청구 시점의 스냅샷이다. 나중에 폼이 취소되면 2차금 청구액이
-- 줄어드는데, 그때 "얼마로 청구했었나" 를 되짚을 수 있어야 한다.
--
-- seller_id 를 따로 둔다. order_group 을 타고 가면 알 수 있지만, 셀러 기준으로
-- "언제 얼마를 청구했나" 를 훑는 것이 이 표의 주 용도다.
-- =====================================================================

CREATE TABLE second_charge (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_group_id BIGINT      NOT NULL,
    seller_id      BIGINT      NOT NULL,
    amount         INT         NOT NULL COMMENT '청구 시점의 2차금 스냅샷',
    charged_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 쿨다운은 묶음의 가장 최근 청구를 본다
    KEY idx_charge_group (order_group_id, charged_at),
    KEY idx_charge_seller (seller_id, charged_at),
    CONSTRAINT fk_charge_group  FOREIGN KEY (order_group_id) REFERENCES order_group (id),
    CONSTRAINT fk_charge_seller FOREIGN KEY (seller_id)      REFERENCES seller (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='2차금 청구 이력';
