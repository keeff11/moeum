-- =====================================================================
-- seed-demo.sql — 프론트 개발용 임시 셀러 데이터
--
-- ⚠️ Flyway 마이그레이션이 아니다. db/migration 에 두면 안 된다 —
--    거기 두면 테스트 DB 에도 매번 들어가 고정 데이터를 세는 검증들이 깨진다.
--    운영 DB 에 손으로 한 번 실행하는 스크립트다.
--
-- 목적: 셀러 페이지(B0)를 그릴 데이터가 아직 없어서 프론트가 화면을 볼 수 없다.
--       카드에 나오는 모든 상태를 한 번에 만들어 둔다.
--
-- 넣는 것
--   셀러 1명   demo-store  (승인 완료, 배송비 3,000 / 5만원 이상 무료)
--   판매 폼 6개
--     ① 공동구매 · 판매중 · D-5  · 모집 68/100        가장 흔한 카드
--     ② 단독판매 · 판매중 · 재고 5개
--     ③ 공동구매 · 판매중 · D-12 · 모집 12/50         목표 미달 진행 중
--     ④ 공동구매 · 마감                                지난 공구 카드
--     ⑤ 단독판매 · 품절                                SOLD_OUT 파생 확인
--     ⑥ 공동구매 · 작성 중(DRAFT)                      ★ 목록에 안 나와야 정상
--
-- 실행 (운영 인스턴스에서):
--   docker cp seed-demo.sql <mysql컨테이너>:/tmp/
--   docker exec <mysql컨테이너> sh -c --     'mysql --default-character-set=utf8mb4 -umoeum -p"$MYSQL_PASSWORD" moeum < /tmp/seed-demo.sql'
--
-- ⚠️ --default-character-set=utf8mb4 를 빼면 안 된다. 클라이언트가 latin1 로 붙어
--    한글이 이중 인코딩돼 들어간다 (실제로 한 번 겪어서 지우고 다시 넣었다).
--    비밀번호는 컨테이너 환경변수로만 참조한다 — 값을 명령줄에 적지 않는다.
--
-- 지우기: 맨 아래 주석의 DELETE 네 줄을 순서대로 실행한다.
-- =====================================================================

SET @slug = 'demo-store';

-- ---------------------------------------------------------------- 셀러
-- business_no_enc · settlement_acct_enc 는 비워 둔다. AES 컬럼이라 평문을 넣으면
-- 엔티티를 읽는 순간 복호화에서 터진다. 대표자 실명·연락처도 넣지 않는다 — 가짜 개인정보를
-- 운영 DB 에 만들 이유가 없다. 구매자에게 보이는 값은 public_contact 쪽이다.
INSERT INTO seller (kakao_id, store_slug, store_name, review_status,
                    shipping_fee, free_shipping_over,
                    bio, social_url, public_contact, approved_at)
VALUES ('demo-seller-kakao', @slug, '모음 데모 상점', 'APPROVED',
        3000, 50000,
        '굿즈 선주문 전문 · 프론트 개발용 데모 상점',
        'https://instagram.com/moeum',
        'demo@moeum.store',
        NOW(6));

SET @seller = LAST_INSERT_ID();

-- ---------------------------------------------------------------- 판매 폼
-- sold 를 직접 넣는다. 실제로는 결제가 끝나야 오르는 값이지만, 결제 없이
-- "모집 68/100" 카드를 보여주려면 이 방법뿐이다.
INSERT INTO sale_form (seller_id, title, slug, sale_type, status, stock_max, held, sold,
                       target_qty, max_per_user, opens_at, closes_at, shortfall_policy,
                       ship_start_text, min_order_amount, progress_public)
VALUES
 (@seller, '아크릴 스탠드 2차 공구', 'demo-acrylic-stand', 'GROUP', 'SELLING',
  100, 0, 68, 100, 2, NOW(6), DATE_ADD(NOW(6), INTERVAL 5 DAY), 'CANCEL',
  '8월 20일(월) 순차발송', 10000, 1),

 (@seller, '한정 포토카드 세트', 'demo-photocard', 'SOLO', 'SELLING',
  20, 0, 15, NULL, NULL, NOW(6), NULL, NULL,
  '결제 후 2~3일 내 발송', 0, 1),

 (@seller, '캐릭터 키링 공동구매', 'demo-keyring', 'GROUP', 'SELLING',
  50, 0, 12, 50, NULL, NOW(6), DATE_ADD(NOW(6), INTERVAL 12 DAY), 'PROCEED',
  '9월 초 순차발송', 0, 1),

 (@seller, '지난 시즌 엽서 세트', 'demo-postcard', 'GROUP', 'CLOSED',
  80, 0, 80, 60, NULL, DATE_SUB(NOW(6), INTERVAL 30 DAY), DATE_SUB(NOW(6), INTERVAL 3 DAY), 'PROCEED',
  '발송 완료', 0, 1),

 (@seller, '품절된 스티커팩', 'demo-sticker', 'SOLO', 'SELLING',
  10, 0, 10, NULL, NULL, NOW(6), NULL, NULL,
  '재입고 미정', 0, 1),

 (@seller, '준비 중인 신상품', 'demo-draft', 'GROUP', 'DRAFT',
  30, 0, 0, 30, NULL, NULL, DATE_ADD(NOW(6), INTERVAL 20 DAY), 'CANCEL',
  '미정', 0, 1);

-- ---------------------------------------------------------------- 상품 · 옵션
-- 카드의 대표가는 가장 싼 옵션의 1차금+2차금이다. 폼마다 옵션을 둘씩 둬서
-- 최저가가 제대로 골라지는지 프론트에서 확인할 수 있게 한다.
INSERT INTO product (sale_form_id, name, sort_order)
SELECT id, title, 0 FROM sale_form WHERE seller_id = @seller;

INSERT INTO product_option (product_id, name, deposit1_amount, deposit2_amount, sort_order)
SELECT p.id, '기본', 20000, 12000, 0 FROM product p
  JOIN sale_form f ON f.id = p.sale_form_id WHERE f.seller_id = @seller;

INSERT INTO product_option (product_id, name, deposit1_amount, deposit2_amount, sort_order)
SELECT p.id, '고급', 25000, 15000, 1 FROM product p
  JOIN sale_form f ON f.id = p.sale_form_id WHERE f.seller_id = @seller;

-- ---------------------------------------------------------------- 이미지
-- 절대 주소를 넣으면 서버가 그대로 내보낸다(ImageStorage.publicUrl).
-- S3 에 실제 파일을 올리지 않고도 썸네일이 뜬다.
--
-- 일부러 두 폼은 비워 둔다 — thumbnailUrl 이 null 일 때 프론트가 자리표시자를
-- 제대로 그리는지 확인할 수 있어야 한다.
INSERT INTO sale_form_image (sale_form_id, object_key, sort_order)
SELECT id, CONCAT('https://picsum.photos/seed/', slug, '/600/600'), 0
  FROM sale_form
 WHERE seller_id = @seller
   AND slug IN ('demo-acrylic-stand', 'demo-photocard', 'demo-keyring', 'demo-postcard');

-- =====================================================================
-- 지우기 (순서대로)
--
-- DELETE o FROM product_option o JOIN product p ON p.id = o.product_id
--   JOIN sale_form f ON f.id = p.sale_form_id
--   JOIN seller s ON s.id = f.seller_id WHERE s.store_slug = 'demo-store';
-- DELETE p FROM product p JOIN sale_form f ON f.id = p.sale_form_id
--   JOIN seller s ON s.id = f.seller_id WHERE s.store_slug = 'demo-store';
-- DELETE f FROM sale_form f JOIN seller s ON s.id = f.seller_id
--   WHERE s.store_slug = 'demo-store';          -- sale_form_image 는 ON DELETE CASCADE
-- DELETE FROM seller WHERE store_slug = 'demo-store';
--
-- ⚠️ 이 셀러의 상품으로 주문이 만들어진 뒤에는 위 DELETE 가 FK 에 걸린다.
--    그때는 지우지 말고 판매 폼 status 를 'CLOSED' 로 내려 숨긴다.
-- =====================================================================
