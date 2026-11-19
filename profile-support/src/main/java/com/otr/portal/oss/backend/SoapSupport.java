package com.otr.portal.oss.backend;

import com.otr.portal.soap.client.codec.JaxbFragmentMarshaller;
import com.otr.portal.soap.client.codec.StaxSoapBodyUnmarshaller;
import com.otr.portal.soap.client.port.SoapPort;
import com.otr.portal.soap.wsdl.ResolvedSoapEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;

/**
 * Сборка SOAP-портов к ОШС СОБИ: JAXB-сериализация (javax 2.x — контракт {@code fk-services-sobi}),
 * конверт SOAP 1.1, десериализация ответа из Body.
 */
@Slf4j
public final class SoapSupport {

    /** Namespace контракта ОШС (как в монолитном {@code SobiSoapXml}). */
    public static final String NAMESPACE_URI = "http://www.otr.ru/hr/ws/organization/v3";

    private static final String GET_USER_INFO_REQUEST = "getUserInfoRequest";
    private static final String GET_USER_GROUPS_REQUEST = "getUserGroupsRequest";
    private static final String MANAGE_USER_CUSTOM_ATTRIBUTES_REQUEST = "manageUserCustomAttributesRequest";

    private static final JAXBContext JAXB;

    static {
        // Не используем JAXBContext.newInstance(String): javax-овый ContextFinder ищет фабрику
        // через Thread Context ClassLoader, который на реактивных потоках может не видеть
        // BOOT-INF/lib fat-jar (и не находит реализацию). Фабрика glassfish 2.3.1 вызывается
        // напрямую — класс однозначно из нашего classpath (jakarta-реализация исключена в build).
        try {
            JAXB = new com.sun.xml.bind.v2.ContextFactory().createContext(
                    "ru.otr.hr.ws.organization.v3",
                    SoapSupport.class.getClassLoader(),
                    java.util.Collections.emptyMap());
        } catch (JAXBException e) {
            throw new IllegalStateException("Не удалось инициализировать JAXBContext контракта ОШС", e);
        }
    }

    private SoapSupport() {
    }

    public static SoapPort<GetUserInfoRequest, GetUserInfoResponse> userInfoPort(WebClient webClient,
                                                                                 ResolvedSoapEndpoint endpoint) {
        return SoapPort.<GetUserInfoRequest, GetUserInfoResponse>builder()
                .transport(new WebClientSoapTransport(webClient))
                .endpointUri(endpoint.getEndpointUrl())
                .soapAction(endpoint.getSoapAction())
                .serializer(request ->
                        envelope(marshal(GET_USER_INFO_REQUEST, GetUserInfoRequest.class, request)))
                .deserializer(xml ->
                        StaxSoapBodyUnmarshaller.unmarshalBody(xml, JAXB, GetUserInfoResponse.class))
                .build();
    }

    public static SoapPort<GetUserGroupsRequest, GetUserGroupsResponse> userGroupsPort(WebClient webClient,
                                                                                       ResolvedSoapEndpoint endpoint) {
        return SoapPort.<GetUserGroupsRequest, GetUserGroupsResponse>builder()
                .transport(new WebClientSoapTransport(webClient))
                .endpointUri(endpoint.getEndpointUrl())
                .soapAction(endpoint.getSoapAction())
                .serializer(request ->
                        envelope(marshal(GET_USER_GROUPS_REQUEST, GetUserGroupsRequest.class, request)))
                .deserializer(xml ->
                        StaxSoapBodyUnmarshaller.unmarshalBody(xml, JAXB, GetUserGroupsResponse.class))
                .build();
    }

    public static SoapPort<ManageUserCustomAttributesRequest, SimpleResponse> manageAttributesPort(
            WebClient webClient, ResolvedSoapEndpoint endpoint) {
        return SoapPort.<ManageUserCustomAttributesRequest, SimpleResponse>builder()
                .transport(new WebClientSoapTransport(webClient))
                .endpointUri(endpoint.getEndpointUrl())
                .soapAction(endpoint.getSoapAction())
                .serializer(request ->
                        envelope(marshal(MANAGE_USER_CUSTOM_ATTRIBUTES_REQUEST,
                                ManageUserCustomAttributesRequest.class, request)))
                .deserializer(xml ->
                        StaxSoapBodyUnmarshaller.unmarshalBody(xml, JAXB, SimpleResponse.class))
                .build();
    }

    private static <T> String marshal(String localName, Class<T> type, T payload) {
        return JaxbFragmentMarshaller.marshal(JAXB, NAMESPACE_URI, localName, type, payload);
    }

    private static String envelope(String body) {
        return "<?xml version='1.0' encoding='UTF-8'?>" +
                "<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<S:Body>" +
                body +
                "</S:Body>" +
                "</S:Envelope>";
    }
}
