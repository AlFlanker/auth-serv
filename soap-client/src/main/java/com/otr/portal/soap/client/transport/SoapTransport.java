package com.otr.portal.soap.client.transport;

import com.otr.portal.soap.client.model.SoapRequest;
import com.otr.portal.soap.client.model.SoapResponse;
import reactor.core.publisher.Mono;

/**
 * <p>
 * Абстрагирует транспортный слой: реализация принимает готовый {@link SoapRequest}
 * и возвращает сырой {@link SoapResponse} для любого HTTP-статуса.
 *
 * @author alexraspopin
 */
@FunctionalInterface
public interface SoapTransport {

    Mono<SoapResponse> exchange(SoapRequest request);
}
