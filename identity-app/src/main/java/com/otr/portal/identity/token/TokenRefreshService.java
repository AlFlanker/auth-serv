package com.otr.portal.identity.token;

import com.otr.portal.identity.users.LocalUserService;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import com.otr.portal.token.TokenClaims;
import com.otr.portal.token.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/**
 * Перевыпуск JWT: перечитывание профиля (актуальность учётки и организаций)
 * с сохранением времени входа и атомарная замена записи в реестре (старый jti
 * гасится). Роли в claims не входят и при обмене не перечитываются — они отдаются
 * внутренним API ({@code /api/internal/v1/users/groups}, {@code /profile}).
 * Используется обменом ({@link TokenController#exchange}) и сменой активной
 * организации ({@code ProfileViewController.setActiveOrganization}).
 */
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

    private final ProfileService profileService;
    private final LocalUserService localUserService;
    private final TokenService tokenService;
    private final TokenRegistry tokenRegistry;
    private final AuthenticationProperties authentication;
    private final ActiveOrganizationClaimsResolver activeOrganizationClaimsResolver;

    /**
     * Полный обмен: профиль перечитывается из источника (актуальность учётки),
     * организация — из prior JWT (с валидацией по списку организаций) или
     * автовыбором единственной. Роли в токен не попадают.
     */
    public Mono<TokenService.IssuedToken> refresh(Jwt prior) {
        return refreshClaims(prior).flatMap(claims -> reissue(claims, prior));
    }

    /**
     * Перевыпуск с готовыми claims (например, при смене активной организации):
     * старый jti гасится, новый регистрируется в общем реестре.
     */
    public Mono<TokenService.IssuedToken> reissue(TokenClaims claims, Jwt prior) {
        return tokenService.reissue(claims, prior)
                .filterWhen(issued -> tokenRegistry.exchange(prior.getId(), issued.jti(), issued.expiresAt()));
    }

    /**
     * Claims для первичной выдачи по SSO: организация — cookie {@code ACTIVE_ORG}
     * (если проброшена) или автовыбор единственной организации профиля.
     */
    public Mono<TokenClaims> loadSsoClaims(String username, String source, ServerWebExchange exchange) {
        return profileService.loadWithoutCache(username)
                .filter(profile -> username.equals(profile.login()))
                .map(profile -> toClaims(profile, source, null, exchange))
                .onErrorResume(ProfileNotFoundException.class, ignored -> Mono.empty());
    }

    private Mono<TokenClaims> refreshClaims(Jwt jwt) {
        String username = jwt.getSubject();
        String source = jwt.getClaimAsString("auth_source");
        if (TokenController.AUTH_SOURCE_PASSWORD.equals(source)) {
            if (!authentication.localPasswordEnabled()) {
                return Mono.empty();
            }
            return Mono.fromCallable(() -> localUserService.authorities(username))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(ignored -> new TokenClaims(username, null, null, source));
        }
        return profileService.loadWithoutCache(username)
                .filter(profile -> username.equals(profile.login()))
                .map(profile -> toClaims(profile, source, jwt, null))
                .onErrorResume(ProfileNotFoundException.class, ignored -> Mono.empty());
    }

    private TokenClaims toClaims(Profile profile, String source, Jwt prior, ServerWebExchange exchange) {
        Optional<Organization> active = activeOrganizationClaimsResolver.resolve(profile, prior, exchange);
        return active
                .map(organization -> new TokenClaims(profile.login(),
                        organization.orgGUID(), organization.orgSvrCode(), source))
                .orElseGet(() -> new TokenClaims(profile.login(), null, null, source));
    }

    /** Wire-форма ответа выпуска/обмена/перевыпуска (как {@code /api/v1/token}). */
    public record TokenView(String accessToken, String tokenType, long expiresInSeconds) {
        @Override
        public String toString() {
            return "TokenView[redacted]";
        }
    }

    public static TokenView toView(TokenService.IssuedToken token) {
        return new TokenView(token.value(), "Bearer", token.expiresInSeconds());
    }
}
