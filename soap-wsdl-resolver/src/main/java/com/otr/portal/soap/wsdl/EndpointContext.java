package com.otr.portal.soap.wsdl;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Нейтральные входные данные для резолва SOAP endpoint по WSDL.
 *
 * @author alexraspopin
 * @since 25.12.2025
 */
@Value
@Builder
@EqualsAndHashCode
public class EndpointContext {
    @Builder.Default
    boolean isSoap = true;
    String url;
    String serviceName;
    @Nullable
    String operationName;
}
