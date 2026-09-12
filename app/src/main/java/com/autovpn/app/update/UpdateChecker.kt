package com.autovpn.app.update

import com.autovpn.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Compares the git commit this build was compiled from (BuildConfig.GIT_SHA, baked in
 * by the GitHub Actions workflow) against the "version.txt" file published alongside
 * every build in the repo's "latest" GitHub release. If they differ, a newer build
 * exists on GitHub than what's installed on the phone.
 */
object UpdateChecker {

    // >>> Change this if you fork the repo under a different owner/name <<<
    private const val GITHUB_REPO = "sayidbagdeli7-art/Sebvpn2"

    private const val VERSION_URL = "https://github.com/$GITHUB_REPO/releases/latest/download/version.txt"
    const val APK_URL = "https://github.com/$GITHUB_REPO/releases/latest/download/AutoVPN.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun isUpdateAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.GIT_SHA == "dev") return@withContext false // local build, nothing to compare against

        val remoteSha = try {
            val req = Request.Builder().url(VERSION_URL).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim() else null
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext false

        remoteSha.isNotBlank() && remoteSha != BuildConfig.GIT_SHA
    }
}
