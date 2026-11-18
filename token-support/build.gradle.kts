plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "token-support"

dependencies {
    api("org.springframework.security:spring-security-oauth2-jose")
    api("org.springframework.security:spring-security-core")
    api("io.projectreactor:reactor-core")
    api("org.springframework:spring-web")

    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
