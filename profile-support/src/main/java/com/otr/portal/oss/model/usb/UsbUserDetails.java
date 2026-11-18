package com.otr.portal.oss.model.usb;

import java.util.List;

/**
 * Ответ сервиса USS (identification v2) с данными пользователя.
 * Snake-case маппинг обеспечивает {@link UsbWireMapper}.
 */
public record UsbUserDetails(
        String login,
        String firstName,
        String middleName,
        String lastName,
        String fullName,
        String snils,
        String email,
        String phone,
        Integer state,
        String stateComment,
        String stateFrom,
        String stateTo,
        Integer confidentialityLevel,
        List<UsbEmployee> employees
) {
}
