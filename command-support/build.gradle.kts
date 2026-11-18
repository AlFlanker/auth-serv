plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "command-support"

dependencies {
    api("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    api("io.github.resilience4j:resilience4j-bulkhead")
    api("io.github.resilience4j:resilience4j-retry")
    api("io.github.resilience4j:resilience4j-micrometer")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
