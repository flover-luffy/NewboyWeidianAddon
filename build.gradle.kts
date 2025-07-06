plugins {
    val kotlinVersion = "2.0.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "net.lawaxi"
version = "0.1.1-test7"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://mcl.repo.mamoe.net/")
    mavenCentral()
}

dependencies {
    api("cn.hutool:hutool-all:5.8.38")
    api(files("libs/shitboy-0.1.11-dev7.jar"))
    
    // 新增依赖
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("ch.qos.logback:logback-classic:1.2.6")
}
