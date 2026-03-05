plugins {
    id("java")
    application
    kotlin("jvm") version "2.3.10"
}

group = "pw.chew"
version = "1.0-SNAPSHOT"

repositories {
    maven {
        url = uri("https://m2.chew.pro/releases/")
        content {
            includeGroup("pw.chew")
        }
    }

    mavenCentral()
}

dependencies {
    implementation("net.dv8tion", "JDA", "6.3.0")
    implementation("pw.chew", "jda-chewtils", "2.2.1")
    implementation("org.json", "json", "20250517")
    implementation("ch.qos.logback", "logback-classic", "1.5.31")
    implementation("mysql", "mysql-connector-java", "8.0.33")
    implementation("org.mapdb", "mapdb", "3.1.0")
    implementation("org.hibernate", "hibernate-core", "7.2.6.Final")
    implementation("com.github.ben-manes.caffeine", "caffeine", "3.2.3")

    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

//tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
//    kotlinOptions.jvmTarget = "21"
//    kotlinOptions.apiVersion = "2.2"
//}

application {
    mainClass.set("pw.chew.mlb.MLBBot")
}
