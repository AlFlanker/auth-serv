plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "webclient-support"

dependencies {
    api("org.springframework.boot:spring-boot-starter-webclient")
    implementation("io.netty:netty-resolver-dns")

    val osArch = System.getProperty("os.arch")
    val osName = System.getProperty("os.name").lowercase()
    if (osName.contains("mac")) {
        if (osArch.contains("aarch64") || osArch.contains("arm")) {
            runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
        } else {
            runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-x86_64")
        }
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
