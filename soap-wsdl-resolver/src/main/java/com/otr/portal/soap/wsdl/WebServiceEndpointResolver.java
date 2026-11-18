package com.otr.portal.soap.wsdl;

import reactor.core.publisher.Mono;

/**
 * Определяет URL, SoapAction
 * и целевой namespace SOAP web-сервиса по его WSDL.
 * <b>Интерфейс создал для удобной отладки при оптимизациях</b>
 *
 * @author alexraspopin
 * @since 14.07.2026
 */
public interface WebServiceEndpointResolver {
    Mono<ResolvedSoapEndpoint> resolveEndpointMetadata(EndpointContext request);
}
