-- =====================================================================
-- V24__payment_session_check.sql
-- 결과값이 안 온 결제를 point3 에 미리 물어보는 일정 (D-091).
--
-- 구매자가 결제창에서 확정했는데 결과값(payment-flow 17번)이 우리에게 안 닿으면 결제는 CREATED 에 남는다.
-- 지금까지는 1차금만 홀드 만료(최대 30분) 때 처음 물었고, 2차금은 묻는 곳이 없었다.
-- point3 승인은 구매자가 확정한 당일 자정(KST)까지만 된다 — 늦게 물을수록 그 기한을 넘길 수 있다.
--
-- session_check_due_at : 다음에 물어볼 시각. 세션을 붙일 때 30초 뒤로 잡는다.
--                        NULL 이면 묻지 않는다 (아직 세션이 없거나, 결론이 났거나, 상한에 닿았다)
-- session_checks       : 지금까지 물어본 횟수. 간격(1 → 2 → 4 → 8 → 10분)과 상한을 정한다
--
-- hold_checked_at(V23) 을 같이 쓰지 않는다 — 그쪽은 만료 배치의 줄 순서라 의미가 다르다.
-- =====================================================================

ALTER TABLE payment
    ADD COLUMN session_check_due_at DATETIME(6) NULL
        COMMENT '결과값이 안 온 결제를 point3 에 다음으로 물어볼 시각. NULL 이면 묻지 않는다',
    ADD COLUMN session_checks INT NOT NULL DEFAULT 0
        COMMENT '결과값이 안 온 결제를 point3 에 물어본 횟수';

CREATE INDEX idx_payment_session_check ON payment (status, session_check_due_at);
