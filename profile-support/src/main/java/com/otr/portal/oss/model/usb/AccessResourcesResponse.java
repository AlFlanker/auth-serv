package com.otr.portal.oss.model.usb;

import java.util.List;

/**
 * Ответ сервиса USS {@code /authorization/v2/access/resources}.
 */
public record AccessResourcesResponse(
        List<String> allowedResources,
        String debugInfo
) {
}
