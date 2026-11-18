package com.otr.portal.oss.backend;

import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

/**
 * REST-бэкенд ОШС (СОБИ) — заглушка: ОШС в текущий момент не поддерживает REST API
 * (поведение соответствует монолиту).
 */
public class SobiRestBackend implements OssBackend {

    private static final String MESSAGE = "REST-API ОШС не поддерживается (заглушка, как в монолите)";

    @Override
    public Mono<GetUserInfoResponse> getUserInfoV1(GetUserInfoRequest request) {
        return Mono.error(new UnsupportedOperationException(MESSAGE));
    }

    @Override
    public Mono<GetUserGroupsResponse> getUserGroupsV1(GetUserGroupsRequest request) {
        return Mono.error(new UnsupportedOperationException(MESSAGE));
    }

    @Override
    public Mono<SimpleResponse> manageUserCustomAttributesV1(ManageUserCustomAttributesRequest request) {
        return Mono.error(new UnsupportedOperationException(MESSAGE));
    }
}
