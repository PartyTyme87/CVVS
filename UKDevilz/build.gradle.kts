version = 16

cloudstream {
    authors     = listOf("PartyTyme87")
    language    = "en"
    description = "UKDevilz"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://ixiporn.org&size=16"
}

dependencies {
    compileOnly("androidx.preference:preference-ktx:1.2.0")
    compileOnly("androidx.appcompat:appcompat:1.6.1")
}
