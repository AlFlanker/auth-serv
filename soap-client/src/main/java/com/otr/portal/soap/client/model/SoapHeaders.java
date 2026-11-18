package com.otr.portal.soap.client.model;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Стандартные SOAP HTTP-заголовки.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SoapHeaders {
    public static final String SOAP_ACTION = "SOAPAction";
}
