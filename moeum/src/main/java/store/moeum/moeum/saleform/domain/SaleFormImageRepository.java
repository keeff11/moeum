package store.moeum.moeum.saleform.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 이미지는 {@link SaleForm} 을 통해서만 쓰고 지운다. 이 리포지토리는 <b>전수 조회 하나</b> 때문에 있다.
 */
public interface SaleFormImageRepository extends JpaRepository<SaleFormImage, Long> {

	/**
	 * 폼에 걸려 있는 모든 이미지 키. 고아 파일 청소가 "지우면 안 되는 것" 목록으로 쓴다.
	 *
	 * 폼 상태를 보지 않는다 — DRAFT 도 CLOSED 도 이미지를 쥐고 있다.
	 * 여기서 한 건이라도 빠지면 그 이미지가 삭제 대상이 된다.
	 */
	@Query("select i.objectKey from SaleFormImage i")
	List<String> findAllObjectKeys();
}
