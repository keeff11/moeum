-- payment.refunded_amount 를 걷는다 (D-060).
--
-- 이 컬럼은 처음부터 죽어 있었다. 엔티티 생성자가 0 으로 채운 뒤 갱신하는 코드가
-- 한 줄도 없었다 — 환불을 아무리 해도 0 이었다. 그런데 이름이 그럴듯해서 읽는 쪽이
-- 생겼고, 개발 콘솔의 '오늘 매출' 이 SUM(amount - refunded_amount) 로 세는 바람에
-- 환불한 만큼 매출이 부풀어 보였다. 남겨 두면 다음 사람이 또 믿는다.
--
-- 실제 기준은 COMPLETED 인 refund 행의 합이고, 돈을 다루는 경로는 전부 이미
-- 그렇게 읽고 있었다 (RefundRepository.sumCompleted · PaymentWriter.readStatus).
--
-- CHECK 제약이 이 컬럼을 잡고 있어 먼저 푼다.
ALTER TABLE payment
    DROP CHECK ck_payment_refunded;

ALTER TABLE payment
    DROP COLUMN refunded_amount;
