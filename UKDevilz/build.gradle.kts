apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")

cloudstream {
    // You can customize the name and description for the app menu here
    name = "UKDevilz"
    description = "UKDevilz provider"
    authors = listOf("PartyTyme87")
    types = listOf("NSFW")
}

dependencies {
    implementation("androidx.preference:preference-ktx:1.2.1")
}
