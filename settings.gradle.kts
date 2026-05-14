pluginManagement {
	repositories {
		maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
		maven { url = uri("https://maven.aliyun.com/repository/public") }
		mavenLocal()
		mavenCentral()
		gradlePluginPortal()
		maven { url = uri("https://maven.fabricmc.net/") }
		maven(url = "https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven(url = "https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.3"
	id("dev.kikugie.loom-back-compat") version "0.2"
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
	create(rootProject) {
		versions(
				"1.21.1",
				"1.21.4",
				"1.21.6",
				"1.21.7",
				"1.21.8",
				"1.21.9",
				"1.21.10",
				"1.21.11",
		)
	}
}

rootProject.name = "sguprofiler"
