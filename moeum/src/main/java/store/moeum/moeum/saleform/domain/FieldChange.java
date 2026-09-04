package store.moeum.moeum.saleform.domain;

/** 판매 폼에서 실제로 바뀐 필드 하나. sale_form_history 한 행이 된다 */
public record FieldChange(String field, Object oldValue, Object newValue) {
}
