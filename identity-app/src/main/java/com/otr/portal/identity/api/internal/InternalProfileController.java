package com.otr.portal.identity.api.internal;

import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Внутренний API профиля — контур интеграции с монолитом fk-portal
 * («прослойка между монолитом и USS/OSS», без JWT).
 * Идентификатор пользователя и параметры передаются в теле запроса, пути статичные.
 * Защищён {@link InternalApiKeyWebFilter} (заголовок {@code X-Internal-Key}).
 */
@RestController
@RequestMapping("/api/internal/v1/users")
@RequiredArgsConstructor
public class InternalProfileController {

    private final ProfileService profileService;

    @PostMapping("/profile")
    public Mono<Profile> profile(@RequestBody UserRequest request) {
        return Boolean.TRUE.equals(request.bypassCache())
                ? profileService.loadWithoutCache(request.login())
                : profileService.load(request.login());
    }

    @PostMapping("/groups")
    public Mono<List<String>> groups(@RequestBody UserRequest request) {
        return profileService.getUserAuthorities(request.login());
    }

    @PostMapping("/custom-attributes")
    public Mono<ResponseEntity<Void>> updateCustomAttributes(@RequestBody UpdateCustomAttributesRequest request) {
        return profileService.updateCustomFields(request.login(), request.attributes())
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/organizations")
    public Mono<List<Organization>> organizations(@RequestBody UserRequest request) {
        return profileService.getOrganizations(request.login());
    }

    @PostMapping("/cache/invalidate")
    public Mono<ResponseEntity<Void>> invalidateCache() {
        return Mono.fromRunnable(profileService::invalidateAll)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
