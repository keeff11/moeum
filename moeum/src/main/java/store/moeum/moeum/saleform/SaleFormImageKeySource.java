package store.moeum.moeum.saleform;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.storage.ImageKeySource;
import store.moeum.moeum.saleform.domain.SaleFormImageRepository;

import java.util.List;

/** 판매 폼이 쓰고 있는 이미지 키. 고아 파일 청소가 이 목록 밖의 객체를 지운다 */
@Component
@RequiredArgsConstructor
public class SaleFormImageKeySource implements ImageKeySource {

	private final SaleFormImageRepository saleFormImageRepository;

	@Override
	@Transactional(readOnly = true)
	public List<String> referencedImageKeys() {
		return saleFormImageRepository.findAllObjectKeys();
	}

	@Override
	public String sourceName() {
		return "sale_form_image";
	}
}
