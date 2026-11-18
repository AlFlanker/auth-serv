package com.otr.portal.identity;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static lombok.AccessLevel.PRIVATE;

/**
 * Сервис аутентификации и профиля пользователя (identity-service).
 */
@Slf4j
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
