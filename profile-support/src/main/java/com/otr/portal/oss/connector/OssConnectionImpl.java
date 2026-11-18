package com.otr.portal.oss.connector;

import com.otr.portal.oss.backend.OssBackendFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

/**
 * Реализация {@link OssConnection}: резолвит бэкенд по текущей конфигурации (ossType/ossUrl)
 * и делегирует вызовы ему. Бэкенд кэшируется фабрикой до изменения конфигурации.
 */
@Component
@RequiredArgsConstructor
public class OssConnectionImpl implements OssConnection {

    private final OssBackendFactory backendFactory;

    @Override
    public Mono<GetUserInfoResponse> getUserInfoV1(GetUserInfoRequest request) {
        return backendFactory.getBackend()
                .flatMap(backend -> backend.getUserInfoV1(request));
    }

    @Override
    public Mono<GetUserGroupsResponse> getUserGroupsV1(GetUserGroupsRequest request) {
        return backendFactory.getBackend()
                .flatMap(backend -> backend.getUserGroupsV1(request));
    }

    @Override
    public Mono<SimpleResponse> manageUserCustomAttributesV1(ManageUserCustomAttributesRequest request) {
        return backendFactory.getBackend()
                .flatMap(backend -> backend.manageUserCustomAttributesV1(request));
    }
}
