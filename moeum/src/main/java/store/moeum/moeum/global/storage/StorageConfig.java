package store.moeum.moeum.global.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * presigner 와 client 를 둘 다 둔다.
 *
 * presigner 는 업로드용이다 — 파일 바이트는 서버를 지나가지 않는다.
 * client 는 <b>지우려고</b> 있다. 고아 파일 청소({@code OrphanImageSweepBatch})가 버킷을 훑고
 * 참조되지 않는 객체를 삭제하는데, 그건 서명으로 못 하고 서버가 직접 호출해야 한다.
 * 그래서 EC2 역할에 {@code s3:ListBucket} 과 {@code s3:DeleteObject} 가 필요하다.
 *
 * 자격증명은 기본 체인이 찾는다 — 운영에서는 EC2 인스턴스 역할({@code moeum-ec2})이다.
 * <b>액세스 키를 설정에 두지 않는다.</b> 이 프로젝트는 배포도 OIDC 로 하고 시크릿도
 * Parameter Store 에서 받는다 (D-017). 여기만 키를 박으면 그 원칙이 깨진다.
 *
 * 리전이 설정에 없으면 기본 체인이 찾는다. 로컬처럼 아무것도 없는 환경을 위해 마지막에 서울을 둔다 —
 * 버킷이 비어 있으면 업로드 기능 자체가 꺼지므로 이 값이 쓰일 일은 없다.
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, OrphanSweepProperties.class})
public class StorageConfig {

	@Bean
	public S3Presigner s3Presigner() {
		return S3Presigner.builder()
				.region(resolveRegion())
				.build();
	}

	/**
	 * 목록 조회와 삭제에만 쓴다. 여기로 파일을 올리거나 내려받지 않는다.
	 *
	 * 자격증명이 없어도 빈 생성은 성공한다 — 실제 호출 시점에야 확인한다.
	 * 로컬에서는 버킷이 비어 있어 청소 배치가 호출까지 가지 않는다.
	 */
	@Bean
	public S3Client s3Client() {
		return S3Client.builder()
				.region(resolveRegion())
				.build();
	}

	private static Region resolveRegion() {
		try {
			return DefaultAwsRegionProviderChain.builder().build().getRegion();
		} catch (RuntimeException e) {
			return Region.AP_NORTHEAST_2;
		}
	}
}
