package com.otr.portal.oss.backend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Операции SOAP-сервиса ОШС (аналог монолитного {@code SoapActionType}):
 * имя операции для резолва WSDL и fallback-значение заголовка SOAPAction.
 */
@RequiredArgsConstructor
public enum SoapActionType {
    GET_USER_INFO("getUserInfo", "getUserInfo"),
    GET_USER_GROUPS("getUserGroups", "getUserGroupsRequest"),
    MANAGE_USER_CUSTOM_ATTRIBUTES("manageUserCustomAttributes", "manageUserCustomAttributesRequest");

    @Getter
    private final String operationName;

    @Getter
    private final String actionName;
}
