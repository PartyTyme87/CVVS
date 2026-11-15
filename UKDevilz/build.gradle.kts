apply(plugin = "com.android.library")

ext {
    set("extClass", "com.ukdevilz.UKDevilzPlugin")
    set("extName", "UKDevilz")
    set("extVersionCode", 1)
    set("extVersionName", "1.0")
    set("tvTypes", listOf("NSFW"))
}

dependencies {
    implementation("androidx.preference:preference-ktx:1.2.1")
}
