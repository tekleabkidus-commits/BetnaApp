plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
fun setting(key: String, fallback: String = "") = providers.gradleProperty(key).orElse(providers.environmentVariable(key)).getOrElse(fallback)
fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
android {
    namespace = "com.appcontrol.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = setting("APP_ID", "com.appcontrol.mobile")
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = setting("VERSION_CODE", "3").toInt()
        versionName = setting("VERSION_NAME", "0.3.0")
        resValue("string", "app_name", setting("APP_NAME", "Betna"))
        buildConfigField("String", "API_URLS", quote(setting("API_URLS")))
        buildConfigField("String", "CONFIG_PUBLIC_KEY", quote(setting("CONFIG_PUBLIC_KEY")))
        buildConfigField("String", "WEBSITE_URL", quote(setting("WEBSITE_URL", "https://example.com")))
        buildConfigField("String", "FIREBASE_APPLICATION_ID", quote(setting("FIREBASE_APPLICATION_ID")))
        buildConfigField("String", "FIREBASE_API_KEY", quote(setting("FIREBASE_API_KEY")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quote(setting("FIREBASE_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quote(setting("FIREBASE_SENDER_ID")))
    }
    signingConfigs {
        if (setting("RELEASE_STORE_FILE").isNotBlank()) create("owner") {
            storeFile = file(setting("RELEASE_STORE_FILE")); storePassword = setting("RELEASE_STORE_PASSWORD")
            keyAlias = setting("RELEASE_KEY_ALIAS"); keyPassword = setting("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".preview" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("owner") != null) signingConfig = signingConfigs.getByName("owner")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
tasks.register("verifyReleaseConfiguration") {
    doLast {
        require(setting("API_URLS").startsWith("https://")) { "Set API_URLS for production" }
        require(setting("CONFIG_PUBLIC_KEY").isNotBlank()) { "Pin the server signing public key" }
        require(setting("WEBSITE_URL").startsWith("https://") && !setting("WEBSITE_URL").contains("example.com")) { "Set the actual website URL" }
        require(setting("RELEASE_STORE_FILE").isNotBlank()) { "Owner signing key is required" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("verifyReleaseConfiguration") }
