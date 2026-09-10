package store.moeum.moeum.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;
import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 심사 신청자 목록 (운영자).
 *
 * <b>{@link SellerResponse} 와 따로 두는 이유는 사업자번호 때문이다.</b> 그쪽은
 * "사업자번호 · 정산계좌는 담지 않는다" 를 규칙으로 삼는다 — 셀러 본인과 구매자가 보는
 * 응답이라 담을 이유가 없다. 반면 심사는 <b>사업자번호를 확인하는 일 그 자체</b>라,
 * 그 값이 없으면 이 API 로는 승인 여부를 판단할 수 없다.
 *
 * <b>정산계좌는 여기에도 담지 않는다.</b> 심사에 필요한 값이 아니다 — 돈을 보낼 때
 * 쓰는 값이고, 그 시점은 심사보다 한참 뒤다. 필요한 것만 꺼내는 것이 규칙이다.
 *
 * 이 응답이 나가는 {@code /admin/*} 은 프록시가 404 로 막고 있다 (deploy/Caddyfile).
 * 앱에 운영자 인증이 붙기 전까지 그 차단이 유일한 방어선이라는 점은 그대로다.
 */
@Schema(description = "셀러 심사 신청자 목록")
public record SellerApplicantPageResponse(

		@Schema(description = "신청자 목록. 먼저 낸 신청이 위에 온다")
		List<Applicant> items,

		@Schema(description = "페이지 정보")
		PageInfo page
) {

	/** 심사 화면 한 줄 */
	@Schema(description = "심사 신청자")
	public record Applicant(

			@Schema(description = "셀러 id. 승인 · 반려를 부를 때 쓰는 값이다", example = "1")
			Long id,

			@Schema(description = "판매공간 주소. moeum.store/{이 값}", example = "moeum-store")
			String storeSlug,

			@Schema(description = "상점 이름. 온보딩에서 받지 않았으면 null", example = "모음 상점")
			String storeName,

			@Schema(description = "대표자 실명", example = "김대표")
			String representativeName,

			@Schema(description = "사업자등록번호. 심사의 판단 근거라 여기서만 내보낸다",
					example = "1234567890")
			String businessNo,

			@Schema(description = "심사 · 정산용 연락처", example = "01099990000")
			String phone,

			@Schema(description = "심사 · 정산용 이메일", example = "seller@example.com")
			String email,

			@Schema(description = "심사 상태")
			ReviewStatus reviewStatus,

			@Schema(description = "온보딩을 제출한 시각. 이 순서로 처리한다",
					example = "2026-09-01T10:03:11")
			LocalDateTime appliedAt,

			@Schema(description = "승인된 시각. 아직이거나 반려됐으면 null")
			LocalDateTime approvedAt
	) {
	}

	@Schema(description = "페이지 정보")
	public record PageInfo(

			@Schema(description = "현재 페이지 번호. 0부터 시작한다", example = "0")
			int page,

			@Schema(description = "실제 적용된 페이지 크기", example = "20")
			int size,

			@Schema(description = "조건에 맞는 전체 신청 수", example = "37")
			long totalElements,

			@Schema(description = "전체 페이지 수", example = "2")
			int totalPages,

			@Schema(description = "다음 페이지가 있는가", example = "true")
			boolean hasNext
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static SellerApplicantPageResponse of(Page<Seller> sellers) {
		return new SellerApplicantPageResponse(
				sellers.getContent().stream().map(SellerApplicantPageResponse::applicantOf).toList(),
				new PageInfo(sellers.getNumber(), sellers.getSize(), sellers.getTotalElements(),
						sellers.getTotalPages(), sellers.hasNext()));
	}

	private static Applicant applicantOf(Seller seller) {
		return new Applicant(
				seller.getId(),
				seller.getStoreSlug(),
				seller.getStoreName(),
				seller.getRepresentativeName(),
				seller.getBusinessNo(),
				seller.getPhone(),
				seller.getEmail(),
				seller.getReviewStatus(),
				seller.getCreatedAt(),
				seller.getApprovedAt());
	}
}
