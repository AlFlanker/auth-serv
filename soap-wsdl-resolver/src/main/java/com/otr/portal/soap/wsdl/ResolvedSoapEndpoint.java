package com.otr.portal.soap.wsdl;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Результат резолва SOAP endpoint: адрес вызова, {@code SOAPAction} и целевой namespace.
 *
 * @author alexraspopin
 * @since 25.12.2025
 */
@Value
@Builder
public class ResolvedSoapEndpoint {
    String endpointUrl;
    @Nullable
    String soapAction;
    @Nullable
    String targetNamespace;
}
