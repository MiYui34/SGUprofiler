import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
	id("dev.kikugie.loom-back-compat")
}

version = "${sc.properties["mod.version"] as String}+mc${sc.current.version}"
base.archivesName.set(sc.properties["mod.id"] as String)

val requiredJava = JavaVersion.VERSION_21

repositories {
	maven { url = uri("https://maven.aliyun.com/repository/public") }
	mavenCentral()
	maven { url = uri("https://maven.fabricmc.net/") }
	exclusiveContent {
		forRepository {
			maven(url = "https://api.modrinth.com/maven") { name = "Modrinth" }
		}
		filter { includeGroup("maven.modrinth") }
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")
	mappings("net.fabricmc:yarn:${sc.properties["deps.yarn_mappings"] as String}:v2")
	modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"] as String}")
	modImplementation(
			"net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"] as String}")
	modImplementation("maven.modrinth:carpet:${sc.properties["deps.carpet"] as String}")
}

loom {
	runConfigs.all {
		runDir = "../../run"
	}
}

java {
	sourceCompatibility = requiredJava
	targetCompatibility = requiredJava
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
		vendor.set(JvmVendorSpec.ADOPTIUM)
	}
}

tasks.processResources {
	val props =
			mapOf(
					"version" to (sc.properties["mod.version"] as String),
					"minecraft_version" to sc.current.version,
			)
	inputs.property("version", props["version"]!!)
	inputs.property("minecraft_version", props["minecraft_version"]!!)
	filesMatching("fabric.mod.json") { expand(props) }
}

tasks.register<Copy>("buildAndCollect") {
	group = "build"
	from(loomx.modJar.map { it.archiveFile })
	into(rootProject.layout.buildDirectory.dir("libs/${sc.properties["mod.version"] as String}"))
	dependsOn("build")
}
