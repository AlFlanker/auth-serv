package com.otr.portal.oss.connector;

import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

/**
 * Порт взаимодействия с источником профилей (ОШС/ЮСБ) — семантика монолитного {@code OssConnection}.
 */
public interface OssConnection {

    Mono<GetUserInfoResponse> getUserInfoV1(GetUserInfoRequest request);

    Mono<GetUserGroupsResponse> getUserGroupsV1(GetUserGroupsRequest request);

    Mono<SimpleResponse> manageUserCustomAttributesV1(ManageUserCustomAttributesRequest request);
}
