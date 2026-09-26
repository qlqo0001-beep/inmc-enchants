plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.enchants"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-enchants"
}

dependencies {
    // PlaceholderExpansion 은 추상 클래스라 Proxy 로 못 만든다. compileOnly 가 필요하다.
    compileOnly(libs.placeholderapi) { isTransitive = false }

    // 셰이딩 대상은 bStats 하나뿐이다.
    implementation(libs.bstats.bukkit)
}

tasks.shadowJar {
    // bStats 는 relocate 가 필수다 (가이드 함정 4).
    relocate("org.bstats", "com.inmc.enchants.lib.bstats")
}
