-- =====================================================================
-- V22__refund_reconcile_checked.sql
-- 취소 대사 배치가 마지막으로 집어간 시각 (D-074). V21 의 payment 와 같은 이유다.
--
-- 대사 쿼리가 updated_at 이 오래된 순으로 50건을 집는데, point3 가 아직 처리 중이라고 답하면
-- 아무것도 쓰지 않아 updated_at 이 그대로다. 미확정 취소가 50건을 넘으면 매 회차 같은 50건만
-- 다시 보고 뒤쪽 건은 차례가 오지 않는다. NULL 은 한 번도 안 본 건이라 맨 앞에 선다.
-- =====================================================================

ALTER TABLE refund
    ADD COLUMN reconcile_checked_at DATETIME(6) NULL
        COMMENT '취소 대사 배치가 마지막으로 집어간 시각. 이 순서로 돌아가며 본다';

CREATE INDEX idx_refund_reconcile ON refund (status, reconcile_checked_at);
