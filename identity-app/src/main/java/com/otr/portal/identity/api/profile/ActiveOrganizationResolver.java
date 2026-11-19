package com.otr.portal.identity.api.profile;

import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Разрешение активной организации пользователя для публичных профильных эндпоинтов —
 * реактивный аналог связки монолитных {@code ActiveOrgResolverFilter} +
 * {@code initializeActiveOrganizationIfSingle}. Порядок источников:
 * <ol>
 *     <li>claim {@code org_code} проверенного JWT (автовыбор единственной организации при выдаче);</li>
 *     <li>cookie {@code ACTIVE_ORG} (выбор пользователя через PUT /organizations/active, URL-encoded);</li>
 *     <li>единственная организация профиля — автовыбор.</li>
 * </ol>
 * Иначе — {@link OrgNotSelectedException} (HTTP 428), как {@code OrganizationGuardFilter} монолита.
 */
@Component
public class ActiveOrganizationResolver {

    public static final String ACTIVE_ORG_COOKIE = "ACTIVE_ORG";

    public Mono<Organization> resolve(Profile profile, AuthenticatedUser user, ServerWebExchange exchange) {
        Organization byClaim = findByCode(profile, user.orgCode());
        if (byClaim != null) {
            return Mono.just(byClaim);
        }
        Organization byCookie = findByCode(profile, readCookie(exchange));
        if (byCookie != null) {
            return Mono.just(byCookie);
        }
        if (profile.hasSingleOrganization()) {
            return Mono.just(profile.organizations().getFirst());
        }
        return Mono.error(new OrgNotSelectedException(buildMessage(profile)));
    }

    private String buildMessage(Profile profile) {
        if (profile.fullName() == null) {
            return "Для выполнения запроса необходимо выбрать организацию";
        }
        return String.format("Пользователь [%s] не выбрал(-a) организацию для работы", profile.fullName());
    }

    private static Organization findByCode(Profile profile, String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return profile.organizations().stream()
                .filter(organization -> Objects.equals(organization.orgSvrCode(), code))
                .findFirst()
                .orElse(null);
    }

    private static String readCookie(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(ACTIVE_ORG_COOKIE);
        if (cookie == null) {
            return null;
        }
        // Как ActiveOrgResolverFilter монолита: значение cookie хранится URL-encoded.
        return URLDecoder.decode(cookie.getValue(), UTF_8);
    }
}
