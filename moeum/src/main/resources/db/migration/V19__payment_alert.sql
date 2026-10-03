-- 승인 대사 알림 이력 (D-068).
--
-- 대사 배치는 1분마다 돈다. 기록 없이 알리면 같은 미확정 건으로 Slack 이 매분 울린다.
-- (session_id, level) 유니크에 INSERT IGNORE 로 선점한 쪽만 보낸다 — 인스턴스가 여럿이어도 한 번이다.
--
-- payment 에 컬럼을 두지 않은 이유: payment.updated_at 은 ON UPDATE 라 알림 기록이
-- 대기 시작 시각을 밀어 버린다. 대사 배치와 마감 계산이 그 값을 기준으로 삼는다.
--
-- 결제 id 가 아니라 세션으로 묶는 이유: 재결제(D-023)는 같은 payment 행을 다시 쓰고
-- 세션만 갈아끼운다. 새 세션의 미확정은 새 사건이라 다시 알려야 한다.
CREATE TABLE payment_alert (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id BIGINT       NOT NULL,
    session_id VARCHAR(128) NOT NULL COMMENT 'point3 sessionId',
    level      VARCHAR(10)  NOT NULL COMMENT 'WARN, CRITICAL',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_alert (session_id, level),
    KEY idx_payment_alert_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
