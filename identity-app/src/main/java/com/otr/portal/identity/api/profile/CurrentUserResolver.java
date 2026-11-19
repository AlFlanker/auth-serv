package com.otr.portal.identity.api.profile;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Доступ к {@link AuthenticatedUser}, положенному в exchange фильтром
 * {@link JwtAuthenticationWebFilter}. Отсутствие пользователя на защищённом пути —
 * ошибка 401 (в рабочем приложении фильтр её не пропускает).
 */
@Component
public class CurrentUserResolver {

    public static final String USER_ATTRIBUTE = CurrentUserResolver.class.getName() + ".authenticatedUser";

    public Mono<AuthenticatedUser> requireUser(ServerWebExchange exchange) {
        AuthenticatedUser user = exchange.getAttribute(USER_ATTRIBUTE);
        return user == null
                ? Mono.error(new UnauthorizedException())
                : Mono.just(user);
    }
}
