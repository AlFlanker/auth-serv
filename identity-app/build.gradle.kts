plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "identity-app"

repositories {
    mavenCentral()
    maven {
        // Артефакты ОТР (например, ru.otr.ext:fk-services-sobi)
        url = uri("http://nexus.otr.ru/content/groups/repo/")
        isAllowInsecureProtocol = true
    }
}

dependencies {
    implementation(project(":profile-support"))
    implementation(project(":token-support"))
    implementation(project(":config-source"))
    implementation(project(":command-support"))
    implementation(project(":webclient-support"))
    implementation(project(":soap-client"))
    implementation(project(":soap-wsdl-resolver"))

    // JAXB (javax 2.x) нужен явно в app-модуле: IDE при Run не всегда подтягивает api-транзитив.
    val javaxJaxbVersion: String by rootProject.extra
    implementation("javax.xml.bind:jaxb-api:$javaxJaxbVersion")
    implementation("org.glassfish.jaxb:jaxb-runtime:$javaxJaxbVersion")
    implementation("javax.activation:javax.activation-api:1.2.0")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Регистр Prometheus: без него нет ни бина PrometheusMeterRegistry, ни эндпоинта /actuator/prometheus
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("io.projectreactor:reactor-test")
}

val rel = findProperty("releaseVersion") as String?
if (rel != null) {
    version = rel
}
