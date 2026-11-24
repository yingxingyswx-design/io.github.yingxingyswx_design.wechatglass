package com.wechatglass;

import java.util.Locale;

/**
 * Build-time circuit breaker for development-only probes.
 *
 * <p>Development builds carry {@code dev} in their version name, release builds
 * carry {@code release} (e.g. {@code 1.3.1.dev} / {@code 1.3.1.release}). When
 * the version name contains {@code release}, every high-risk hook that only
 * exists to investigate WeChat internals (the wallet-page sampling hooks) is
 * refused at install time, so a shipped APK can never carry them by accident.
 */
final class BuildFlags {

    private BuildFlags() {
    }

    static String versionName() {
        try {
            return io.github.yingxingyswx_design.wechatglass.BuildConfig.VERSION_NAME;
        } catch (Throwable throwable) {
            return "";
        }
    }

    /** True when the version name marks this as a release build. */
    static boolean isReleaseBuild() {
        String name = versionName();
        return name != null && name.toLowerCase(Locale.ROOT).contains("release");
    }

    /** Development probes are only allowed outside release builds. */
    static boolean devProbesAllowed() {
        return !isReleaseBuild();
    }
}
