-- 정산 후 셀러 직접 환불의 완료 표시 (S14 · D-059).
--
-- settled_manual 은 "시스템으로는 취소할 수 없다" 까지만 말한다. 셀러가 실제로 계좌로
-- 이체했는지는 아무 데도 없어서, 접수된 건이 처리됐는지 알 방법이 없었다.
-- 이 값이 비어 있으면 '환불 대기', 차 있으면 '환불 완료' 다.
ALTER TABLE refund
    ADD COLUMN manual_refunded_at DATETIME(6) NULL
        COMMENT '정산 후 셀러가 직접 이체를 마쳤다고 표시한 시각. settled_manual 건에만 찬다'
        AFTER settled_manual,
    ADD KEY idx_refund_manual (settled_manual, manual_refunded_at);
