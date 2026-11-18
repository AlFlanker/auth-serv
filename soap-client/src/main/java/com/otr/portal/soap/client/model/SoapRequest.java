package com.otr.portal.soap.client.model;

import lombok.Builder;
import lombok.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;

import java.net.URI;

import static org.springframework.http.MediaType.TEXT_XML;

/**
 * Описание одного SOAP HTTP-запроса, готового к отправке транспортом.
 */
@Value
public class SoapRequest {
    URI endpointUri;
    String body;
    MediaType contentType;
    HttpHeaders headers;

    @Builder
    public SoapRequest(URI endpointUri,
                       String body,
                       MediaType contentType,
                       HttpHeaders headers) {
        Assert.notNull(endpointUri, "endpointUri must not be null");
        Assert.hasText(body, "body must not be blank");
        this.endpointUri = endpointUri;
        this.body = body;
        this.contentType = contentType == null ? TEXT_XML : contentType;

        var copy = new HttpHeaders();
        if (headers != null) {
            copy.addAll(headers);
        }
        this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
    }
}
