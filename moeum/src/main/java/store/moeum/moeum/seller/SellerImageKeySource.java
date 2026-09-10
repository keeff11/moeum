package store.moeum.moeum.seller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.storage.ImageKeySource;
import store.moeum.moeum.seller.domain.SellerRepository;

import java.util.List;

/**
 * 셀러 프로필 사진 키.
 *
 * 프로필은 폼 이미지와 같은 접두사에 올라가므로 청소 대상 범위에 그대로 들어온다.
 * 여기서 안 내놓으면 프로필 사진이 하루 뒤 사라진다.
 */
@Component
@RequiredArgsConstructor
public class SellerImageKeySource implements ImageKeySource {

	private final SellerRepository sellerRepository;

	@Override
	@Transactional(readOnly = true)
	public List<String> referencedImageKeys() {
		return sellerRepository.findAllProfileImageKeys();
	}

	@Override
	public String sourceName() {
		return "seller.profile_image_key";
	}
}
