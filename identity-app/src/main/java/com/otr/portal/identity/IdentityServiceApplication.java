package com.otr.portal.identity;

import com.otr.portal.identity.token.AuthenticationProperties;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static lombok.AccessLevel.PRIVATE;

/**
 * Сервис аутентификации и профиля пользователя (identity-service).
 */
@Slf4j
@EnableConfigurationProperties(AuthenticationProperties.class)
@SpringBootApplication(scanBasePackages = {
        "com.otr.portal.identity",
        "com.otr.portal.oss",
        "com.otr.portal.token"
})
@NoArgsConstructor(access = PRIVATE)
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
