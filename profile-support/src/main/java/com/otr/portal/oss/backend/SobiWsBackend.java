package com.otr.portal.oss.backend;

import com.otr.portal.soap.client.port.SoapPort;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

/**
 * SOAP-бэкенд ОШС (СОБИ): запросы через {@link SoapPort} с JAXB-сериализацией контракта.
 */
@RequiredArgsConstructor
public class SobiWsBackend implements OssBackend {

    private final SoapPort<GetUserInfoRequest, GetUserInfoResponse> userInfoPort;
    private final SoapPort<GetUserGroupsRequest, GetUserGroupsResponse> userGroupsPort;
    private final SoapPort<ManageUserCustomAttributesRequest, SimpleResponse> manageAttributesPort;

    @Override
    public Mono<GetUserInfoResponse> getUserInfoV1(GetUserInfoRequest request) {
        return userInfoPort.execute(request);
    }

    @Override
    public Mono<GetUserGroupsResponse> getUserGroupsV1(GetUserGroupsRequest request) {
        return userGroupsPort.execute(request);
    }

    @Override
    public Mono<SimpleResponse> manageUserCustomAttributesV1(ManageUserCustomAttributesRequest request) {
        return manageAttributesPort.execute(request);
    }
}
