package com.otr.portal.oss.model.usb;

import java.util.Map;

/**
 * Запрос к сервису USS {@code /authorization/v2/access/resources} для получения ролей/ресурсов.
 */
public record AccessResourcesRequest(
        DataBlock data,
        Subject subject
) {

    public record DataBlock(
            String applicationCode,
            boolean debugMode,
            Map<String, Object> fields,
            boolean previewMode,
            String resourceTypeCode,
            String operationCode
    ) {
    }

    public record Subject(
            String employeeId,
            int level,
            String token
    ) {
    }
}
