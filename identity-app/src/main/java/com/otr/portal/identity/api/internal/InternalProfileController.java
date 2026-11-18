package com.otr.portal.identity.api.internal;

import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Внутренний API профиля — контур будущей интеграции с монолитом fk-portal
 * («прослойка между монолитом и USS/OSS», без JWT).
 * Проверкой общего ключа управляет свойство {@code identity.internal.require-api-key}.
 */
@RestController
@RequestMapping("/api/internal/v1/users")
@RequiredArgsConstructor
public class InternalProfileController {

    private final ProfileService profileService;

    @PostMapping("/{login}/profile")
    public Mono<Profile> profile(@PathVariable String login) {
        return profileService.load(login);
    }

    @PostMapping("/{login}/groups")
    public Mono<List<String>> groups(@PathVariable String login) {
        return profileService.getUserAuthorities(login);
    }

    @PostMapping("/{login}/custom-attributes")
    public Mono<ResponseEntity<Void>> updateCustomAttributes(@PathVariable String login,
                                                             @RequestBody Map<String, String> attributes) {
        return profileService.updateCustomFields(login, attributes)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/organizations/{login}")
    public Mono<List<Organization>> organizations(@PathVariable String login) {
        return profileService.getOrganizations(login);
    }
}
