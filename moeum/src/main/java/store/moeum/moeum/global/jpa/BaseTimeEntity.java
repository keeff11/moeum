package store.moeum.moeum.global.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * created_at · updated_at 을 가진 테이블의 공통 매핑.
 *
 * DDL 에도 DEFAULT CURRENT_TIMESTAMP(6) / ON UPDATE 가 걸려 있다. 그쪽은 네이티브 쿼리·배치가
 * 직접 건드릴 때를 위한 안전망이고, JPA 로 들어오는 경로는 여기 감사 기능이 채운다.
 * 시각은 {@link JpaAuditingConfig} 가 KST 로 고정한다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
