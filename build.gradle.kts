plugins {
	id("jacoco")
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "br.andrew"
// Permite `./gradlew assemble -Pversion=1.2.3` no workflow de release.
version = if (project.hasProperty("version")) project.property("version") as String else "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Parser SQL usado para garantir que a instrucao e, de fato, um SELECT.
	implementation("com.github.jsqlparser:jsqlparser:5.3")
	// Driver JDBC do SAP HANA (equivalente JVM do driver ODBC HDBODBC).
	runtimeOnly("com.sap.cloud.db.jdbc:ngdbc:2.29.11")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// `./gradlew bootRun` ativa o perfil `local` automaticamente, lendo o
// application-local.yaml da raiz (ignorado pelo git). Assim o arquivo
// versionado nunca precisa conter senha.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	systemProperty("spring.profiles.active", "local")
}

// Relatorio de cobertura consumido pelo workflow de teste (.github/workflows/test.yml).
// O ReportGenerator la espera o XML em build/jacoco.xml.
tasks.named("jacocoTestReport", JacocoReport::class) {
	dependsOn(tasks.withType<Test>())
	group = "Reporting"
	reports {
		html.required.set(true)
		xml.required.set(true)
		csv.required.set(false)
		html.outputLocation.set(layout.buildDirectory.dir("jacocoHtml"))
		xml.outputLocation.set(layout.buildDirectory.file("jacoco.xml"))
	}
	classDirectories.from(
		files(classDirectories.files.map {
			fileTree(it) { exclude("**/*Test*.*") }
		})
	)
}
