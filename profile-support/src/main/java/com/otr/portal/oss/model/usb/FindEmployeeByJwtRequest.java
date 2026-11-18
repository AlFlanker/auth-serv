package com.otr.portal.oss.model.usb;

/**
 * Тело запроса identification v2 {@code POST /employee/findByJwt}.
 */
public record FindEmployeeByJwtRequest(
        String jwt,
        boolean includeRoles
) {

    public static FindEmployeeByJwtRequest forJwt(String jwt) {
        return new FindEmployeeByJwtRequest(jwt, false);
    }
}
