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
    api ("cn.hutool:hutool-all:5.8.38")
    api(files("libs/shitboy-0.1.9.mirai2.jar"))
}