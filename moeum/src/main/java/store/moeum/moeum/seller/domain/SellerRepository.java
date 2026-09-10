package store.moeum.moeum.seller.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

	Optional<Seller> findByKakaoId(String kakaoId);

	boolean existsByKakaoId(String kakaoId);

	boolean existsByStoreSlug(String storeSlug);

	/** 셀러 페이지(B0) 진입. store_slug 가 곧 공개 주소다 */
	Optional<Seller> findByStoreSlug(String storeSlug);

	/**
	 * 쓰이고 있는 프로필 사진 키 전부. 고아 파일 청소가 "지우면 안 되는 것" 목록으로 쓴다.
	 *
	 * 심사 상태를 보지 않는다 — 반려된 셀러도 사진을 쥐고 있고, 재신청하면 그대로 쓴다.
	 * 빈 문자열이 들어간 행이 있어도 키로 취급하지 않도록 여기서 거른다.
	 */
	@Query("""
			select s.profileImageKey from Seller s
			 where s.profileImageKey is not null and s.profileImageKey <> ''
			""")
	List<String> findAllProfileImageKeys();

	/**
	 * 심사 신청자 목록 (운영자).
	 *
	 * <b>신청이 오래된 것이 위에 온다.</b> 심사는 대기열이라 먼저 낸 사람이 먼저 받아야 한다 —
	 * 최신순으로 두면 늦게 낸 신청만 처리되고 오래된 것이 바닥에 깔린다.
	 *
	 * {@code status} 가 null 이면 상태로 거르지 않는다. 이미 처리한 건을 다시 보거나
	 * 반려한 건을 되짚을 때 쓴다.
	 */
	@Query("""
			select s from Seller s
			 where (:status is null or s.reviewStatus = :status)
			 order by s.createdAt asc, s.id asc
			""")
	Page<Seller> findApplicants(@Param("status") ReviewStatus status, Pageable pageable);
}
