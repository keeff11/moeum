-- =====================================================================
-- V21__payment_reconcile_checked.sql
-- 승인 대사 배치가 마지막으로 집어간 시각 (D-074).
--
-- 대사 쿼리는 updated_at 이 오래된 순으로 50건을 집는데, point3 가 아직 모른다고 답하면
-- 아무것도 쓰지 않아 updated_at 이 그대로다. 미확정 건이 50건을 넘으면 매 회차 같은 50건만
-- 다시 보고 뒤쪽 건은 승인 마감(다음 날 00:00)을 넘길 때까지 차례가 오지 않는다.
--
-- updated_at 을 갱신해 순서를 돌리면 안 된다 — 멈춤 알림이 updated_at 을 "멈춘 시각" 으로 쓴다.
-- 그래서 집어간 시각을 따로 둔다. NULL 은 한 번도 안 본 건이라 맨 앞에 선다.
-- =====================================================================

ALTER TABLE payment
    ADD COLUMN reconcile_checked_at DATETIME(6) NULL
        COMMENT '승인 대사 배치가 마지막으로 집어간 시각. 이 순서로 돌아가며 본다';

CREATE INDEX idx_payment_reconcile ON payment (status, reconcile_checked_at);
