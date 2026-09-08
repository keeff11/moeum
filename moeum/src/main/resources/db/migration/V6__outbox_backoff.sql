-- =====================================================================
-- V6__outbox_backoff.sql
-- Outbox 릴레이의 재시도 백오프와 임대(lease) 시각.
--
-- 원래 스키마에는 retry_count 만 있고 "다음에 언제 다시 시도할지" 를 담을 곳이 없었다.
-- created_at 으로는 계산할 수 없다 — 마지막 시도 시각이 아니라 적재 시각이라
-- 실패가 쌓여도 간격이 벌어지지 않고 1초마다 계속 두드리게 된다.
--
-- 이 컬럼은 두 가지를 겸한다.
--   ① 실패 후 백오프  — 실패할 때마다 뒤로 민다
--   ② 처리 중 임대     — 집어갈 때도 앞으로 민다. 릴레이가 발송하는 동안 다른
--                        인스턴스가 같은 행을 집으면 알림톡이 두 번 나간다.
--                        프로세스가 죽으면 임대가 만료돼 자동으로 다시 잡힌다
--
-- 기존 인덱스 (status, created_at) 는 이 조회를 타지 못하므로 교체한다.
-- =====================================================================

ALTER TABLE outbox
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT '이 시각 전에는 집지 않는다. 실패 백오프와 처리 중 임대를 겸한다';

DROP INDEX idx_outbox_pending ON outbox;

CREATE INDEX idx_outbox_pending ON outbox (status, next_attempt_at);
