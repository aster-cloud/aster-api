import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    id("java")
    // Using latest Quarkus 3.28.3 - testing Gradle 9.0 compatibility
    id("io.quarkus") version "3.30.2"
    id("io.gatling.gradle") version "3.13.1"
}

extra["reportsDir"] = layout.buildDirectory.dir("reports/gatling").get().asFile

repositories {
    mavenCentral()
    mavenLocal()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Quarkus BOM (Bill of Materials) for dependency management
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.30.2"))

    // Quarkus核心依赖 - Reactive REST endpoints (quarkus-rest already includes reactive support)
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // OpenAPI & Health checks
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-smallrye-health")

    // Metrics
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")

    // 链路追踪 - OpenTelemetry
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.opentelemetry:opentelemetry-exporter-logging")

    // Caching - Caffeine cache + Redis for distributed invalidation
    implementation("io.quarkus:quarkus-cache")
    implementation("io.quarkus:quarkus-redis-cache")

    // WebSocket support for Live Preview
    implementation("io.quarkus:quarkus-websockets")

    // GraphQL支持
    implementation("io.quarkus:quarkus-smallrye-graphql")
    implementation("commons-codec:commons-codec:1.17.1")

    // Persistence - Hibernate Panache + PostgreSQL + Flyway + Reactive Inbox
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    implementation("io.quarkus:quarkus-hibernate-reactive-panache")
    implementation("io.quarkus:quarkus-reactive-pg-client")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")

    // Scheduler - for workflow cleanup tasks
    implementation("io.quarkus:quarkus-scheduler")

    // Aster 核心模块 - 使用独立项目 (cloud.aster-lang 命名空间)
    implementation("cloud.aster-lang:aster-lang-core:0.0.1")
    implementation("cloud.aster-lang:aster-lang-runtime:0.0.1")
    implementation("cloud.aster-lang:aster-lang-truffle:0.0.1")

    // Aster 验证模块 - 独立项目
    implementation("cloud.aster-lang:aster-lang-validation:0.0.1")

    // GraalVM Polyglot SDK (用于动态 CNL 执行)
    implementation("org.graalvm.polyglot:polyglot:25.0.1")
    implementation("org.graalvm.sdk:graal-sdk:25.0.1")
    // truffle-api 需要作为 implementation 以便访问 VirtualFrame（DynamicCnlExecutor 需要）
    implementation("org.graalvm.truffle:truffle-api:25.0.1")
    runtimeOnly("org.graalvm.truffle:truffle-runtime:25.0.1")
    runtimeOnly("org.graalvm.truffle:truffle-compiler:25.0.1")
    runtimeOnly("org.graalvm.compiler:compiler:25.0.1")

    // 测试依赖
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.quarkus:quarkus-test-vertx")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.smallrye.reactive:smallrye-mutiny-vertx-junit5")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.2.1")

    // Testcontainers - PostgreSQL 测试环境（Phase 3.4）
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("com.redis:testcontainers-redis:2.0.1")
}

configurations.configureEach {
    exclude(group = "org.jboss.slf4j", module = "slf4j-jboss-logmanager")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.remove("-Werror") // Override global setting from reproducible-builds.gradle.kts
    // 排除 classfile 警告（MicroProfile Config API 缺少 OSGi 注解依赖）
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-classfile"))
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    systemProperty("quarkus.test.flat-class-path", "true")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
