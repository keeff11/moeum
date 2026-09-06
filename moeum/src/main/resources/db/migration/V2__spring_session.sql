-- =====================================================================
-- V2__spring_session.sql
-- 세션 저장소를 톰캣 인메모리에서 MySQL 로 옮긴다 (D-020).
--
-- Spring Session 3.5.x 의 공식 스키마
-- (spring-session-jdbc-3.5.7.jar!/org/springframework/session/jdbc/schema-mysql.sql)
-- 를 그대로 옮겼다. 손대지 않는다 — 라이브러리가 이 컬럼명·타입을 그대로 쿼리한다.
--
-- 스키마는 Flyway 가 소유하므로 spring.session.jdbc.initialize-schema=never 다.
-- 라이브러리가 자기 스키마를 스스로 만들게 두면 이 파일과 두 주인이 생긴다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 세션 한 줄. 시각은 전부 epoch millis (BIGINT) 다.
-- 만료 정리 스케줄러가 EXPIRY_TIME 으로 훑으므로 IX2 가 그 인덱스다.
-- ---------------------------------------------------------------------
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL COMMENT '초 단위. server.servlet.session.timeout 이 들어온다',
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100)     NULL,
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE = InnoDB
  ROW_FORMAT = DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

-- ---------------------------------------------------------------------
-- 세션에 담긴 값들. 지금은 moeum.loginUser 한 행뿐이다.
-- ATTRIBUTE_BYTES 는 자바 직렬화 바이트라 사람이 읽을 수 있는 형태가 아니다.
-- 그래서 카카오 access token 은 여기 넣지 않는다 (D-020).
--
-- 세션이 지워지면 속성도 같이 지워진다 (ON DELETE CASCADE).
-- ---------------------------------------------------------------------
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB
  ROW_FORMAT = DYNAMIC;
