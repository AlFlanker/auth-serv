plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "soap-client"

dependencies {
    api("org.springframework:spring-web")
    api("io.projectreactor:reactor-core")

    val javaxJaxbVersion: String by rootProject.extra
    api("javax.xml.bind:jaxb-api:$javaxJaxbVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.glassfish.jaxb:jaxb-runtime:$javaxJaxbVersion")
    testImplementation("javax.activation:javax.activation-api:1.2.0")
}
