package com.otr.portal.soap.wsdl;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Метаданные SOAP-сервиса, извлечённые из WSDL.
 *
 * @author alexraspopin
 * @since 25.12.2025
 */
@Value
@Builder
public class ResolvedSoapServiceMetadata {
    String endpointUrl;
    Map<String, String> soapActionsByOperation;
    @Nullable
    String targetNamespace;
}
