plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "config-source"

dependencies {
    api("io.projectreactor:reactor-core")

    // ETCD-реализация.
    implementation(project(":etcd-client"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.github.ben-manes.caffeine:caffeine")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
