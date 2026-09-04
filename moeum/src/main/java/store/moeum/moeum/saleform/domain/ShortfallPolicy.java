package store.moeum.moeum.saleform.domain;

/** sale_form.shortfall_policy — 목표수량 미달 시 처리. SOLO 는 NULL */
public enum ShortfallPolicy {
	CANCEL,
	EXTEND,
	PROCEED
}
