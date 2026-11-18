package com.otr.portal.identity.token;

import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Извлечение логина из SSO-заголовка внешнего прокси — контракт монолитного
 * {@code RequestHeaderPreauthFilter}: имя заголовка из OSSConfig.headerName,
 * fallback на cookie с тем же именем, отрезание суффикса {@code ^M} (служебные учётки).
 */
@Component
@RequiredArgsConstructor
public class SsoPrincipalExtractor {

    private static final List<String> SERVICE_SUFFIXES = List.of("^M");

    private final ConfigSource configSource;
    private final ConfigKey<OssConfig> ossConfigKey;
    private final AuthenticationProperties authentication;

    /**
     * @return логин пользователя или {@link Mono#empty()}, если SSO-заголовок отсутствует
     */
    public Mono<SsoPrincipal> extract(ServerWebExchange exchange) {
        return configSource.get(ossConfigKey)
                .flatMap(config -> {
                    var headerName = config.headerName();
                    if (headerName == null || headerName.isBlank()) {
                        return Mono.empty();
                    }
                    if (TokenController.AUTH_SOURCE_PASSWORD.equalsIgnoreCase(headerName)) {
                        return Mono.error(new IllegalStateException("Имя SSO-заголовка зарезервировано"));
                    }
                    var request = exchange.getRequest();
                    var headers = request.getHeaders().get(headerName);
                    if (headers != null && headers.size() > 1) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                    }
                    var value = request.getHeaders().getFirst(headerName);
                    if ((value == null || value.isBlank()) && authentication.ssoCookieEnabled()) {
                        value = readCookie(request.getCookies(), headerName);
                    }
                    if (value == null || value.isBlank()) {
                        return Mono.empty();
                    }
                    String login = dropServiceSuffix(value);
                    if (login.isBlank() || login.contains(",")) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                    }
                    return Mono.just(new SsoPrincipal(login, headerName));
                });
    }

    private String readCookie(org.springframework.util.MultiValueMap<String, org.springframework.http.HttpCookie> cookies,
                              String name) {
        var cookie = cookies.getFirst(name);
        return cookie == null ? null : cookie.getValue();
    }

    private String dropServiceSuffix(String value) {
        return SERVICE_SUFFIXES.stream()
                .filter(value::endsWith)
                .findFirst()
                .map(suffix -> value.substring(0, value.length() - suffix.length()))
                .orElse(value);
    }

    /**
     * SSO-принципал: логин и имя заголовка, по которому произошла аутентификация.
     */
    public record SsoPrincipal(String login, String headerName) {
    }
}
