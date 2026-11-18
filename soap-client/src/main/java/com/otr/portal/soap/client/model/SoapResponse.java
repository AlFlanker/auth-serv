package com.otr.portal.soap.client.model;

import lombok.Builder;
import lombok.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/**
 * Результат выполнения SOAP HTTP-запроса.
 */
@Value
@Builder
public class SoapResponse {
    HttpStatusCode statusCode;
    HttpHeaders headers;
    String body;
}
