-- 취소 대사 알림 이력 (D-069).
--
-- payment_alert 와 같은 이유로 둔다. 취소 대사 배치도 1분마다 돌아서, 기록 없이 알리면
-- 같은 미확정 환불로 Slack 이 매분 울린다. (refund_id, level) 유니크에 INSERT IGNORE 로
-- 선점한 쪽만 보낸다.
--
-- payment_alert 와 달리 환불 id 로 묶는다. 환불은 재결제처럼 같은 행을 다시 쓰지 않는다 —
-- 실패한 취소를 다시 요청하면 새 refund 행이 생긴다.
CREATE TABLE refund_alert (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    refund_id  BIGINT      NOT NULL,
    level      VARCHAR(10) NOT NULL COMMENT 'WARN, CRITICAL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_alert (refund_id, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
