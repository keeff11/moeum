-- =====================================================================
-- V23__payment_hold_check.sql
-- 홀드 만료 배치가 point3 에 구매자 확정을 물어본 기록 (D-079).
--
-- 만료 배치는 결제창까지 간 만료 묶음을 id 순으로 50건 묻는다. point3 가 unknown · 401/403 으로 답하거나
-- 처리 중 예외가 나면 그 건은 풀리지도 대사로 넘어가지도 않아 매 회차 같은 자리에 다시 선다.
-- 그런 건이 50건 쌓이면 뒤쪽 건은 차례가 오지 않고, 결제창을 거친 만료 홀드가 전부 안 풀린다.
--
-- hold_checked_at : 마지막으로 물어본 시각. 이 순서로 돌아가며 본다. NULL 은 한 번도 안 본 건이라 맨 앞이다
-- hold_check_misses : 물어봤는데 풀지도 넘기지도 못한 횟수. 기준을 넘으면 Slack 으로 알린다
--
-- reconcile_checked_at(V21)을 같이 쓰지 않는다 — 구조된 건이 CAPTURE_PENDING 으로 넘어가면
-- 대사 배치가 그 값을 "방금 본 건" 으로 읽어 줄 뒤로 보낸다.
-- =====================================================================

ALTER TABLE payment
    ADD COLUMN hold_checked_at DATETIME(6) NULL
        COMMENT '홀드 만료 배치가 point3 에 마지막으로 물어본 시각. 이 순서로 돌아가며 본다',
    ADD COLUMN hold_check_misses INT NOT NULL DEFAULT 0
        COMMENT '홀드 만료 배치가 물어봤는데 결론이 나지 않은 횟수';

ALTER TABLE payment_alert
    MODIFY COLUMN level VARCHAR(10) NOT NULL COMMENT 'WARN, CRITICAL, HOLD_CHECK';
