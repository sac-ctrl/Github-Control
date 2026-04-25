package com.githubcontrol.utils

/**
 * Single source of truth for "this build" metadata, mirrored from
 * `app/build.gradle.kts`. Kept here so we don't depend on `buildConfig = true`
 * being enabled in the Gradle module — keeps APK builds via GitHub Actions
 * resilient.
 */
object BuildInfo {
    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1

    /** owner/repo on github.com — used by the in-app updater + About links. */
    const val GITHUB_OWNER = "neet-ctrl"
    const val GITHUB_REPO  = "GitHub-Control"
    const val ISSUES_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/issues/new"
    const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val DEVELOPER_NAME = "Shakti Kumar"
    const val DEVELOPER_EMAIL = "coder847402@gmail.com"
}
