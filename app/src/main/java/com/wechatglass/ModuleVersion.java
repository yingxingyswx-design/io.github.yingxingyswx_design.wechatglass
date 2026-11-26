package com.wechatglass;

/**
 * Version constant shared by both build systems (Gradle and build.ps1).
 *
 * <p>{@code app/build.gradle.kts} must stay in sync; both builds verify it at
 * build time and fail on a mismatch, so the module can never claim a version it
 * was not built as.
 */
final class ModuleVersion {

    static final String NAME = "1.3.4.release";
    static final int CODE = 12;

    private ModuleVersion() {
    }
}
