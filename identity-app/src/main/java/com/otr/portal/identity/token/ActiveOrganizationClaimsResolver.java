package com.otr.portal.identity.token;

import com.otr.portal.identity.api.profile.ActiveOrganizationResolver;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import java.util.Objects;
import java.util.Optional;

/**
 * Разрешение организации для claims JWT при выпуске/обмене. Источники по приоритету:
 * <ol>
 *     <li>claim {@code org_code} prior JWT (обмен/перевыпуск сохраняют выбор);</li>
 *     <li>cookie {@code ACTIVE_ORG} (первичная выдача, если проброшена portal-edge);</li>
 *     <li>единственная организация профиля — автовыбор.</li>
 * </ol>
 * Каждый кандидат валидируется по актуальному списку организаций профиля:
 * организация, исчезнувшая из списка, отбрасывается. Для мультиорганизационных
 * пользователей без источников организация отсутствует (UI получит 428).
 */
@Component
public class ActiveOrganizationClaimsResolver {

    public Optional<Organization> resolve(Profile profile, Jwt prior, ServerWebExchange exchange) {
        String priorCode = prior == null ? null : prior.getClaimAsString("org_code");
        Organization byPrior = findByCode(profile, priorCode);
        if (byPrior != null) {
            return Optional.of(byPrior);
        }
        String cookieCode = exchange == null
                ? null
                : ActiveOrganizationResolver.readActiveOrgCookie(exchange);
        Organization byCookie = findByCode(profile, cookieCode);
        if (byCookie != null) {
            return Optional.of(byCookie);
        }
        if (profile.hasSingleOrganization()) {
            return Optional.of(profile.organizations().getFirst());
        }
        return Optional.empty();
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
}
