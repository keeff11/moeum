package store.moeum.moeum.global.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 presigner 하나만 둔다. 파일을 서버가 중계하지 않으므로 S3Client 는 필요 없다.
 *
 * 자격증명은 기본 체인이 찾는다 — 운영에서는 EC2 인스턴스 역할({@code moeum-ec2})이다.
 * <b>액세스 키를 설정에 두지 않는다.</b> 이 프로젝트는 배포도 OIDC 로 하고 시크릿도
 * Parameter Store 에서 받는다 (D-017). 여기만 키를 박으면 그 원칙이 깨진다.
 *
 * 리전이 설정에 없으면 기본 체인이 찾는다. 로컬처럼 아무것도 없는 환경을 위해 마지막에 서울을 둔다 —
 * 버킷이 비어 있으면 업로드 기능 자체가 꺼지므로 이 값이 쓰일 일은 없다.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

	@Bean
	public S3Presigner s3Presigner() {
		return S3Presigner.builder()
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
