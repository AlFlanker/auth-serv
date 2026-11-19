plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "profile-support"

repositories {
    mavenCentral()
    maven {
        // Артефакты ОТР: ru.otr.ext:fk-services-sobi (классы SOAP-контракта ОШС/СОБИ)
        url = uri("http://nexus.otr.ru/content/groups/repo/")
        isAllowInsecureProtocol = true
    }
}

dependencies {
    api("ru.otr.ext:fk-services-sobi:2.0.2")
    api("org.springframework:spring-web")
    api("io.projectreactor:reactor-core")

    // javax JAXB 2.x — классы fk-services-sobi аннотированы javax.xml.bind.
    // Реализация нужна и на этапе компиляции: SoapSupport вызывает фабрику ContextFactory
    // напрямую (обход TCCL-зависимого lookup ContextFinder в fat-jar).
    val javaxJaxbVersion: String by rootProject.extra
    api("javax.xml.bind:jaxb-api:$javaxJaxbVersion")
    implementation("org.glassfish.jaxb:jaxb-runtime:$javaxJaxbVersion")
    runtimeOnly("javax.activation:javax.activation-api:1.2.0")

    implementation(project(":command-support"))
    implementation(project(":soap-client"))
    implementation(project(":soap-wsdl-resolver"))
    implementation(project(":config-source"))
    implementation(project(":etcd-client"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.micrometer:micrometer-core")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.wiremock:wiremock-standalone")
    testImplementation("org.glassfish.jaxb:jaxb-runtime:$javaxJaxbVersion")
}
