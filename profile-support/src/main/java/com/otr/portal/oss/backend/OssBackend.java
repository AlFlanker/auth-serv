package com.otr.portal.oss.backend;

import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

/**
 * Бэкенд источника профилей (СОБИ SOAP/REST, USS REST) — аналог монолитного {@code OssBackend}.
 */
public interface OssBackend {

    Mono<GetUserInfoResponse> getUserInfoV1(GetUserInfoRequest request);

    Mono<GetUserGroupsResponse> getUserGroupsV1(GetUserGroupsRequest request);

    Mono<SimpleResponse> manageUserCustomAttributesV1(ManageUserCustomAttributesRequest request);
}
