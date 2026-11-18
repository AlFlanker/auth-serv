plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "etcd-client"

dependencies {
    // jetcd тянет собственные slf4j/log4j- логирование обеспечивает приложение (Spring Boot)
    api("io.etcd:jetcd-core:0.8.6") {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j")
    }

    api("io.projectreactor:reactor-core")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("io.opentelemetry:opentelemetry-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
}
