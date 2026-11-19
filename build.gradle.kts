import org.springframework.boot.gradle.plugin.SpringBootPlugin
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    java
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

val springCloudVersion by extra("2025.1.2")

// document-web-services использует javax.xml.bind. Spring Boot BOM подменяет jaxb-runtime на jakarta 4.x.
val javaxJaxbVersion by extra("2.3.1")

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.otr.portal"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
        }
    }

    configurations.configureEach {
        // jakarta-реализация JAXB 4.x (com.sun.xml.bind) тянется jaxws-ri и в fat-jar
        // конфликтует с javax-реализацией (org.glassfish.jaxb 2.3.1): одинаковые пакеты
        // com.sun.xml.bind.*, и ContextFinder javax-API находит чужую фабрику.
        // В стеке javax она не нужна.
        exclude(group = "com.sun.xml.bind")
        resolutionStrategy.eachDependency {
            if (requested.group == "org.glassfish.jaxb" && requested.name == "jaxb-runtime") {
                useVersion(javaxJaxbVersion)
                because("document-web-services требует javax JAXB 2.x, а не jakarta 4.x из Spring Boot BOM")
            }
            if (requested.group == "org.glassfish.jaxb" && requested.name == "txw2") {
                useVersion(javaxJaxbVersion)
                because("javax JAXB 2.x stack")
            }
            if (requested.group == "com.sun.istack" && requested.name == "istack-commons-runtime") {
                useVersion("3.0.7")
                because("javax JAXB 2.x stack")
            }
        }
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
