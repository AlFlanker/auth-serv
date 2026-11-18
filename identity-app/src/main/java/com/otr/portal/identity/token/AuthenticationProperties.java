package com.otr.portal.identity.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Режимы входа для контуров, где включены дополнительные источники идентичности. */
@ConfigurationProperties("identity.auth")
public record AuthenticationProperties(boolean localPasswordEnabled, boolean ssoCookieEnabled) {
}
