import java.net.URI

plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

val releaseApiUrl = providers.gradleProperty("photographApiBaseUrl").orElse("").get()
val debugApiUrl = providers.gradleProperty("photographDebugApiBaseUrl").orElse("http://10.0.2.2:8080/").get()
require(URI(debugApiUrl).let {
    it.scheme == "http" && it.host == "10.0.2.2" && it.userInfo == null && it.path == "/" &&
        it.rawQuery == null && it.rawFragment == null
}) { "photographDebugApiBaseUrl must point to the emulator host" }

val releaseVersionCode = providers.gradleProperty("photographVersionCode").orElse("1").get().toIntOrNull()
require(releaseVersionCode != null && releaseVersionCode in 1..2_100_000_000) {
    "photographVersionCode must be a positive Android version code"
}
val releaseVersionName = providers.gradleProperty("photographVersionName").orElse("0.1.0").get()
require(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(releaseVersionName)) {
    "photographVersionName must look like 1.2.3"
}

val filingNumber = providers.gradleProperty("photographFilingNumber").orElse("").get()
require(filingNumber.length <= 50 && filingNumber.all { it.isLetterOrDigit() || it == '-' }) {
    "photographFilingNumber contains unsupported characters"
}
val supportUrl = providers.gradleProperty("photographSupportUrl").orElse("").get()
val privacyUrl = providers.gradleProperty("photographPrivacyUrl").orElse("").get()
val communityEnabled = providers.gradleProperty("photographCommunityEnabled").orElse("true").get().toBooleanStrict()
for ((name, value) in mapOf("photographSupportUrl" to supportUrl, "photographPrivacyUrl" to privacyUrl)) {
    require(value.isEmpty() || URI(value).let {
        it.scheme == "https" && it.host != null && it.userInfo == null && it.toASCIIString() == value
    } && value.none { it == '"' || it == '\\' }) { "$name must be a valid HTTPS URL" }
}
val mapStyleUrl = providers.gradleProperty("photographMapStyleUrl")
    .orElse("https://tiles.openfreemap.org/styles/liberty").get()
require(URI(mapStyleUrl).let { it.scheme == "https" && it.host != null && it.userInfo == null && it.toASCIIString() == mapStyleUrl }
    && mapStyleUrl.none { it == '"' || it == '\\' }) { "photographMapStyleUrl must be a valid HTTPS URL" }
require(releaseApiUrl.isEmpty() || URI(releaseApiUrl).let { url ->
    url.scheme == "https" && url.host != null && url.userInfo == null &&
        url.rawQuery == null && url.rawFragment == null && url.path.endsWith("/")
}) { "photographApiBaseUrl must be an HTTPS base URL ending with /" }

android {
    namespace = "com.shiguang.photograph"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shiguang.photograph"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "API_BASE_URL", "\"$releaseApiUrl\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"$mapStyleUrl\"")
        buildConfigField("String", "SUPPORT_URL", "\"$supportUrl\"")
        buildConfigField("String", "PRIVACY_URL", "\"$privacyUrl\"")
        buildConfigField("boolean", "COMMUNITY_ENABLED", communityEnabled.toString())
        buildConfigField("String", "FILING_NUMBER", "\"$filingNumber\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val missingReleaseProperties = mapOf(
    "photographApiBaseUrl" to releaseApiUrl,
    "photographPrivacyUrl" to privacyUrl,
    "photographSupportUrl" to supportUrl,
).filterValues(String::isBlank).keys.joinToString(", ")
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(missingReleaseProperties.isEmpty()) { "Missing release properties: $missingReleaseProperties" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
}
