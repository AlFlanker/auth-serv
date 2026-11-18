package com.otr.portal.oss.model.usb;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Неизменяемый Jackson 3-маппер wire-контрактов USS: snake_case + исключение null-полей
 * (аналог {@code @JsonProperty} + {@code @JsonInclude(NON_NULL)} монолита).
 */
public final class UsbWireMapper {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(value ->
                    JsonInclude.Value.construct(
                            JsonInclude.Include.NON_NULL,
                            JsonInclude.Include.NON_NULL))
            .build();

    private UsbWireMapper() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Не удалось сериализовать запрос к USS: " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Не удалось десериализовать ответ USS в " + type.getSimpleName(), e);
        }
    }
}
