plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "soap-wsdl-resolver"

dependencies {
    api("org.springframework:spring-web")
    api("org.springframework:spring-webflux")
    api("io.projectreactor:reactor-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
