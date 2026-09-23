import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ------------------------------------------------------------------------------
// 非机密的 DeepSeek 参数从项目根目录的 ai.properties 读，编译时注入 BuildConfig。
// 文件缺失时全部回落到默认值，构建不会因此失败。
//
// **API Key 不在这里。** 它由用户在 App 的设置页自己填，存在手机上，
// 所以安装包里没有任何凭据，也不需要为了换 key 重新编译。
// 见 ai/AiUserSettings.kt。
// ------------------------------------------------------------------------------
val aiProps = Properties().apply {
    val file = rootProject.file("ai.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun propString(key: String, fallback: String): String =
    (aiProps.getProperty(key) ?: fallback).trim()

fun propInt(key: String, fallback: Int): Int =
    propString(key, fallback.toString()).toIntOrNull() ?: fallback

fun propBool(key: String, fallback: Boolean): Boolean =
    propString(key, fallback.toString()).equals("true", ignoreCase = true)

/**
 * 转成能塞进 buildConfigField 的 Kotlin 字符串字面量。
 * 不转义的话，值里出现反斜杠或引号会让生成的 BuildConfig.kt 编译不过。
 */
fun String.asKotlinLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// ------------------------------------------------------------------------------
// release 签名材料从项目根目录的 keystore.properties 读。
//
// 为什么放根目录而不是用 gradle 的 signingConfigs 写死：那份配置要进版本库，
// 密码就得跟着进去。这里沿用 ai.properties 的做法 —— 单独一个文件，加进 .gitignore。
//
// 文件不存在时不报错，只是不签名（产物变成 app-release-unsigned.apk）。
// 这条很要紧：别人 clone 下来没有你的密钥，但仍然应该能构建、能跑测试。
// ------------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/** 密钥库是否真的齐了。三项缺一不可，只看文件存不存在很容易漏 */
val releaseKeystore: File? = run {
    val path = keystoreProps.getProperty("storeFile") ?: return@run null
    val store = rootProject.file(path)
    if (!store.exists()) return@run null
    if (keystoreProps.getProperty("storePassword").isNullOrBlank()) return@run null
    if (keystoreProps.getProperty("keyAlias").isNullOrBlank()) return@run null
    store
}

android {
    namespace = "com.example.explaindot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.explaindot"
        minSdk = 26
        // 刻意停在 36：API 37 收紧了前台服务的能力要求（while-in-use capability），
        // 这个 App 的圆点完全靠前台服务活着，先不踩这个坑。
        // Google Play 当前要求就是 36，够用；等 API 37 的强制期限明确再升，只改这一个数字。
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "DEEPSEEK_BASE_URL",
            propString("deepseek.baseUrl", "https://api.deepseek.com").asKotlinLiteral()
        )
        buildConfigField(
            "String", "DEEPSEEK_MODEL",
            propString("deepseek.model", "deepseek-flash").asKotlinLiteral()
        )
        buildConfigField(
            "boolean", "DEEPSEEK_USE_SYSTEM_PROXY",
            propBool("deepseek.useSystemProxy", false).toString()
        )
        buildConfigField(
            "int", "DEEPSEEK_CONNECT_TIMEOUT_SECONDS",
            propInt("deepseek.connectTimeoutSeconds", 15).toString()
        )
        buildConfigField(
            "int", "DEEPSEEK_READ_TIMEOUT_SECONDS",
            propInt("deepseek.readTimeoutSeconds", 90).toString()
        )
        buildConfigField(
            "int", "DEEPSEEK_IMAGE_QUALITY",
            propInt("deepseek.imageQuality", 85).toString()
        )
        buildConfigField(
            "String", "DEEPSEEK_THINKING",
            propString("deepseek.thinking", "off").asKotlinLiteral()
        )
        buildConfigField(
            "String", "DEEPSEEK_CHAT_THINKING",
            propString("deepseek.chatThinking", "high").asKotlinLiteral()
        )
        buildConfigField(
            "int", "DEEPSEEK_MAX_TOKENS",
            propInt("deepseek.maxTokens", 2048).toString()
        )
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                // PKCS12 不支持密钥密码跟库密码不同，所以两者取同一个值。
                // 留这一行是因为不写的话 AGP 会去拿 debug 的默认值，签名会失败。
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: keystoreProps.getProperty("storePassword")
                // 显式关掉 v1：minSdk 26 起只需要 v2/v3，v1 是给 Android 7 以下的老包兼容用的。
                // 关掉能让 APK 少一份重复的签名块，也避免某些工具链对 v1 的告警
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            // 关掉 R8。默认就是关的，写出来是因为这是个要留意的开关：
            // 开了之后包会小一截、启动也快一点，但会重命名类和方法名 ——
            // 这个 App 里没用什么反射，理论上开了没问题，但没必要为了几 MB
            // 去冒一个「上线后才发现某处崩了」的风险。真要开的话先跑一遍全流程。
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // 默认是关的，不开的话 BuildConfig 类根本不会生成
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // DeepSeek 调用。用 OkHttp 而不是 HttpURLConnection，是因为后面要用 SSE 流式输出，
    // 自己按行解析 chunk 比在 HttpURLConnection 上手搓 buffer 靠谱。
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    // org.json 在 Android 上是系统自带的，但单元测试跑在 JVM 上 ——
    // 那里既没有它，android.jar 里那份又是「一调用就抛」的桩。
    // 加一份真的到测试类路径：快照的编解码和 GitHub 响应的解析都是
    // 最该被单测盖住的一段（格式定下就不能再改），必须能离线验。
    // 只进测试，不进 APK。
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
