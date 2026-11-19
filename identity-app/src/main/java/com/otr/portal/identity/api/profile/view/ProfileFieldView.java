package com.otr.portal.identity.api.profile.view;

/**
 * View-форма поля профиля — точный JSON-контракт монолитного {@code ProfileField}
 * (solutions/fk-portal, пакет ossuserinfo): {@code {"editable": ..., "value": ...}}.
 * Класс (а не record) — как в монолите: отсутствующее в теле запроса поле
 * {@code editable} получает значение по умолчанию {@code false}. Единственный
 * публичный конструктор — дефолтный: иначе Jackson 3 автоопределяет
 * параметризованный конструктор как creator и падает на null для примитива.
 * Поле {@code fieldName} наружу не сериализуется, как {@code @JsonIgnore} в монолите.
 */
public class ProfileFieldView<T> {

    private boolean editable;
    private T value;

    public static <T> ProfileFieldView<T> of(T value) {
        ProfileFieldView<T> field = new ProfileFieldView<>();
        field.setValue(value);
        return field;
    }

    public static <T> ProfileFieldView<T> editable(T value) {
        ProfileFieldView<T> field = new ProfileFieldView<>();
        field.setEditable(true);
        field.setValue(value);
        return field;
    }

    public boolean isEditable() {
        return editable;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }
}
