package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.api.profile.view.OssUserInfoView;
import com.otr.portal.identity.api.profile.view.OssUserWorkPhoneView;
import com.otr.portal.identity.api.profile.view.OrganizationView;
import com.otr.portal.identity.api.profile.view.SetActiveOrganizationRequest;
import com.otr.portal.identity.token.TokenRefreshService;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import com.otr.portal.token.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Публичные профильные эндпоинты — перенос {@code OSSUserProfileController}
 * монолита (solutions/fk-portal). Контракты JSON/статусов сохранены; аутентификация —
 * Bearer JWT identity-service (см. {@link JwtAuthenticationWebFilter}).
 */
@RestController
@RequestMapping("/user/current/profile")
@RequiredArgsConstructor
public class ProfileViewController {

    private final ProfileService profileService;
    private final CurrentUserResolver currentUserResolver;
    private final ActiveOrganizationResolver activeOrganizationResolver;
    private final ProfileViewMapper viewMapper;
    private final TokenRefreshService tokenRefreshService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<OssUserInfoView>> getUser(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> profileService.load(user.login())
                        .flatMap(profile -> activeOrganizationResolver.resolve(profile, user, exchange)
                                .map(organization -> viewMapper.toUserInfo(profile, organization))))
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache())
                        .body(view));
    }

    @GetMapping(path = "/workphone", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<OssUserWorkPhoneView>> getUserWorkPlace(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> profileService.loadWithoutCache(user.login()))
                .map(viewMapper::toWorkPhone)
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache())
                        .body(view));
    }

    @ResponseStatus(HttpStatus.OK)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> saveUser(ServerWebExchange exchange, @RequestBody OssUserInfoView ossUserInfo) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> profileService.updateCustomFields(user.login(),
                        Map.of(ProfileViewMapper.PHONE_WORKPLACE, extractNewPhone(ossUserInfo))));
    }

    @GetMapping(path = "/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<OrganizationView>> getAvailableOrgList(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> profileService.getOrganizations(user.login()))
                .map(organizations -> organizations.stream()
                        .map(viewMapper::toOrganizationView)
                        .toList());
    }

    @GetMapping(path = "/organizations/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<OrganizationView> getCurrentOrg(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> profileService.load(user.login())
                        .flatMap(profile -> activeOrganizationResolver.resolve(profile, user, exchange)))
                .map(viewMapper::toOrganizationView);
    }

    @PutMapping(path = "/organizations/active", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TokenRefreshService.TokenView>> setActiveOrganization(ServerWebExchange exchange,
                                                                                     @RequestBody SetActiveOrganizationRequest request) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> profileService.load(user.login())
                        .flatMap(profile -> selectOrganization(profile, request)
                                .flatMap(selected -> reissueWithOrganization(exchange, user, profile, selected))));
    }

    /**
     * Смена активной организации — редкий сценарий: токен перевыпускается с новыми
     * claims {@code org_guid}/{@code org_code} (ролей в claims нет, время входа
     * сохраняется). Старый jti гасится в реестре. Cookie {@code ACTIVE_ORG} —
     * переходная обратная совместимость с монолитом; portal-edge по ответу заменяет
     * хранимый JWT (контракт катофа, см. документацию).
     */
    private Mono<ResponseEntity<TokenRefreshService.TokenView>> reissueWithOrganization(ServerWebExchange exchange,
                                                                                         AuthenticatedUser user,
                                                                                         Profile profile,
                                                                                         Organization selected) {
        Jwt prior = exchange.getAttribute(JwtAuthenticationWebFilter.JWT_ATTRIBUTE);
        if (prior == null) {
            return Mono.error(new UnauthorizedException());
        }
        var claims = new TokenClaims(user.login(),
                selected.orgGUID(), selected.orgSvrCode(), user.authSource());
        return tokenRefreshService.reissue(claims, prior)
                .switchIfEmpty(Mono.error(new UnauthorizedException()))
                .onErrorResume(BadJwtException.class, ignored -> Mono.error(new UnauthorizedException()))
                .map(issued -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.SET_COOKIE, activeOrgCookie(selected).toString())
                        .body(TokenRefreshService.toView(issued)));
    }

    private String extractNewPhone(OssUserInfoView ossUserInfo) {
        if (ossUserInfo == null || ossUserInfo.employee() == null
                || ossUserInfo.employee().phoneWorkplace() == null
                || ossUserInfo.employee().phoneWorkplace().getValue() == null) {
            throw new BadRequestException("Не задано значение phoneWorkplace");
        }
        return ossUserInfo.employee().phoneWorkplace().getValue();
    }

    private Mono<Organization> selectOrganization(Profile profile, SetActiveOrganizationRequest request) {
        String code = request == null ? null : request.organizationCode();
        if (code == null || code.isBlank()) {
            return Mono.error(new BadRequestException("Не задан код организации"));
        }
        return profile.organizations().stream()
                .filter(organization -> Objects.equals(organization.orgSvrCode(), code))
                .findFirst()
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new OrganizationNotFoundException(profile.login(), code)));
    }

    private ResponseCookie activeOrgCookie(Organization organization) {
        return ResponseCookie.from(ActiveOrganizationResolver.ACTIVE_ORG_COOKIE,
                        URLEncoder.encode(organization.orgSvrCode(), StandardCharsets.UTF_8))
                .path("/")
                .httpOnly(true)
                .sameSite("Strict")
                .build();
    }
}
