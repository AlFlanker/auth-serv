package com.otr.portal.oss.model.usb;

/**
 * Сотрудник в ответе USS (identification v2).
 */
public record UsbEmployee(
        String actFrom,
        String actTo,
        String replacementEmployeeId,
        String replacementEmployeeFio,
        String position,
        UsbOrganization organization
) {
}
