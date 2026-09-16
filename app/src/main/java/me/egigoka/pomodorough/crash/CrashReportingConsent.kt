package me.egigoka.pomodorough.crash

import android.content.Context

object CrashReportingConsent {
    const val PREFS_NAME = "pomodorough_crash_reporting"
    const val KEY_ENABLED = "crash_reporting_enabled"
    const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun shouldStart(dsn: String, enabled: Boolean): Boolean =
        shouldStart(
            dsn,
            enabled,
            isInstrumentedTest = isRunningInstrumentedTest(),
            isCiOnEmulator = isCiOnEmulator(),
        )

    fun shouldStart(
        dsn: String,
        enabled: Boolean,
        isInstrumentedTest: Boolean,
        isCiOnEmulator: Boolean,
    ): Boolean = dsn.isNotBlank() && enabled && !isInstrumentedTest && !isCiOnEmulator

    fun isCiBuild(ciValue: String?): Boolean =
        ciValue.equals("true", ignoreCase = true)

    fun isEmulatorBuild(
        fingerprint: String?,
        hardware: String?,
        model: String?,
        manufacturer: String?,
        brand: String?,
        device: String?,
        product: String?,
    ): Boolean {
        val signature = listOf(fingerprint, hardware, model, manufacturer, brand, device, product)
            .joinToString("\n") { (it ?: "").lowercase() }
        return signature.contains("generic") ||
            signature.contains("emulator") ||
            signature.contains("sdk_gphone") ||
            signature.contains("google_sdk") ||
            signature.contains("goldfish") ||
            signature.contains("ranchu") ||
            signature.contains("vbox") ||
            signature.contains("genymotion")
    }

    private fun isRunningInstrumentedTest(): Boolean =
        // androidTest uses AndroidJUnitRunner + InstrumentationRegistry
        // (compose/espresso tests); none ship in the app APK.
        hasTestClass("androidx.test.platform.app.InstrumentationRegistry") ||
            hasTestClass("androidx.test.espresso.Espresso")

    private fun hasTestClass(name: String): Boolean = try {
        Class.forName(name)
        true
        // expected-silent: test-class probe defaults to non-test when absent
    } catch (_: Throwable) {
        false
    }

    private fun isCiOnEmulator(): Boolean {
        if (!isCiBuild(System.getenv("CI"))) return false
        return try {
            isEmulatorBuild(
                buildField("FINGERPRINT"),
                buildField("HARDWARE"),
                buildField("MODEL"),
                buildField("MANUFACTURER"),
                buildField("BRAND"),
                buildField("DEVICE"),
                buildField("PRODUCT"),
            )
            // expected-silent: emulator probe fails closed to real-device reporting
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildField(name: String): String? = try {
        Class.forName("android.os.Build").getField(name).get(null) as? String
        // expected-silent: missing Build field means non-Android JVM, treated as non-emulator
    } catch (_: Throwable) {
        null
    }
}
