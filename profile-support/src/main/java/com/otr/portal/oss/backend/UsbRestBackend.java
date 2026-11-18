package com.otr.portal.oss.backend;

import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbProperties;
import com.otr.portal.oss.exception.OssUnavailableException;
import com.otr.portal.oss.model.usb.AccessResourcesRequest;
import com.otr.portal.oss.model.usb.AccessResourcesResponse;
import com.otr.portal.oss.model.usb.FindEmployeeByJwtRequest;
import com.otr.portal.oss.model.usb.UsbUserDetails;
import com.otr.portal.oss.model.usb.UsbWireMapper;
import io.netty.channel.ConnectTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.SimpleResponse;
import ru.otr.hr.ws.organization.v3.TEmployee;
import ru.otr.hr.ws.organization.v3.TOrganization;
import ru.otr.hr.ws.organization.v3.TUserInfo;
import ru.otr.hr.ws.organization.v3.TUserInfos;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.util.CollectionUtils.isEmpty;
import static org.springframework.util.StringUtils.hasText;

/**
 * REST-бэкенд ЮСБ/USS: запросы identification/authorization v2, перепаковка в формат ОШС
 * (перенос логики монолитного {@code UsbOssRestBackend}).
 */
@Slf4j
public class UsbRestBackend implements OssBackend {

    private final WebClient webClient;
    private final String baseUrl;
    private final UsbConfig usbConfig;
    private final UsbProperties usbProperties;

    public UsbRestBackend(WebClient webClient, String baseUrl, UsbConfig usbConfig, UsbProperties usbProperties) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.usbConfig = usbConfig;
        this.usbProperties = usbProperties;
    }

    @Override
    public Mono<GetUserInfoResponse> getUserInfoV1(GetUserInfoRequest userRequest) {
        var login = userRequest.getLogin();
        return requestUserDetails(login)
                .map(response -> map(response, login));
    }

    @Override
    public Mono<GetUserGroupsResponse> getUserGroupsV1(GetUserGroupsRequest userGroupsRequest) {
        var url = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path(resolvePath(usbConfig.userGroupPath(), UsbConfig.DEFAULT_USER_GROUP_PATH))
                .build()
                .encode(UTF_8)
                .toUriString();

        var request = new AccessResourcesRequest(
                new AccessResourcesRequest.DataBlock(
                        usbProperties.applicationCode(), false, null, false,
                        usbProperties.resourceTypeCode(), usbProperties.operationCode()),
                new AccessResourcesRequest.Subject(null, 0, userGroupsRequest.getLogin()));

        return retrieveBody(webClient.post()
                        .uri(url)
                        .contentType(APPLICATION_JSON)
                        .bodyValue(UsbWireMapper.write(request)),
                url, AccessResourcesResponse.class)
                .map(response -> {
                    List<String> allowed = isEmpty(response.allowedResources())
                            ? emptyList()
                            : response.allowedResources();
                    var groups = new GetUserGroupsResponse();
                    groups.getGroup().addAll(allowed);
                    return groups;
                });
    }

    @Override
    public Mono<SimpleResponse> manageUserCustomAttributesV1(ManageUserCustomAttributesRequest request) {
        return Mono.error(
                new UnsupportedOperationException("УСБ-ОТР не поддерживает редактирование атрибутов пользователя"));
    }

    private Mono<UsbUserDetails> requestUserDetails(String login) {
        if (usbConfig.apiVersion() == UsbConfig.ApiVersion.V2) {
            return requestUserDetailsV2(login);
        }
        return requestUserDetailsV1(login);
    }

    private Mono<UsbUserDetails> requestUserDetailsV1(String login) {
        var url = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path(resolvePath(usbConfig.userInfoPath(), UsbConfig.DEFAULT_USER_INFO_PATH))
                .queryParam("jwt_token", login)
                .build()
                .encode(UTF_8)
                .toUriString();
        return retrieveBody(webClient.get().uri(url), url, UsbUserDetails.class);
    }

    private Mono<UsbUserDetails> requestUserDetailsV2(String login) {
        var urlBuilder = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path(resolvePath(usbConfig.userInfoPath(), UsbConfig.DEFAULT_USER_INFO_PATH));
        if (hasText(usbProperties.applicationCode())) {
            urlBuilder.queryParam("application_code", usbProperties.applicationCode());
        }
        var url = urlBuilder.build().encode(UTF_8).toUriString();
        return retrieveBody(webClient.post()
                        .uri(url)
                        .contentType(APPLICATION_JSON)
                        .bodyValue(UsbWireMapper.write(FindEmployeeByJwtRequest.forJwt(login))),
                url, UsbUserDetails.class);
    }

    private <T> Mono<T> retrieveBody(WebClient.RequestHeadersSpec<?> request, String url, Class<T> bodyType) {
        return request
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response -> handleServerError(url, response))
                .bodyToMono(String.class)
                .map(json -> UsbWireMapper.read(json, bodyType))
                .onErrorMap(ConnectTimeoutException.class,
                        e -> new OssUnavailableException(
                                "Ошибка подключения к " + url + ".\n Http-status: 503", e));
    }

    private Mono<? extends Throwable> handleServerError(String url, ClientResponse response) {
        return response
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new OssUnavailableException(
                        url + "\nОшибка запроса к сервису USS (HTTP "
                                + response.statusCode().value() + "): " + body));
    }

    private static String resolvePath(String configuredPath, String defaultPath) {
        return hasText(configuredPath) ? configuredPath : defaultPath;
    }

    private static GetUserInfoResponse map(UsbUserDetails userInfo, String login) {
        final GetUserInfoResponse response = new GetUserInfoResponse();
        final TEmployee employee = new TEmployee();
        employee.setFullName(userInfo.fullName());
        employee.setLogin(login);
        employee.setPhoneWorkplace(userInfo.phone());
        employee.setEmail(userInfo.email());
        employee.setSnils(userInfo.snils());
        employee.setSamAccountName(userInfo.login());

        List<com.otr.portal.oss.model.usb.UsbEmployee> usbEmployees = userInfo.employees();
        if (!isEmpty(usbEmployees)) {
            var usbEmployee = usbEmployees.get(0);
            employee.setTitleName(usbEmployee.position());
            final TOrganization organization = new TOrganization();
            if (usbEmployee.organization() != null) {
                employee.setDepartmentName(usbEmployee.organization().code());
                employee.setOrganizationName(usbEmployee.organization().name());
                organization.setFullName(usbEmployee.organization().fullName());
                organization.setGuid(usbEmployee.organization().fullPathCodes());
            }
            TUserInfos tUserInfos = new TUserInfos();
            TUserInfo tUserInfo = new TUserInfo();
            tUserInfo.setEmployee(employee);
            tUserInfo.setOrganization(organization);
            tUserInfos.getUserInfo().add(tUserInfo);
            response.setUserInfos(tUserInfos);
        }
        return response;
    }
}
