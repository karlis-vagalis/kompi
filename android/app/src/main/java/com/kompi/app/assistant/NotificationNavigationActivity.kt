package com.kompi.app.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.kompi.app.MainActivity
import java.net.URI
import com.kompi.app.protocol.generated.NOTIFICATION_URL_MAX_CHARS

/** Nonexported, user-invoked navigation only. Never forwards incoming Intent flags or extras. */
internal class NotificationNavigationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ReplyNoticeNavigation.fromNotification(intent)?.let { target ->
                ReplyNoticeNavigation.open(target)
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return
            }
            safeHttps(intent.getStringExtra(URL_EXTRA))?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (_: ActivityNotFoundException) {
            // A browser need not be installed. No fallback can grant another capability.
        } catch (_: SecurityException) {
            // System or browser policy can deny navigation.
        } finally {
            finish()
        }
    }

    companion object {
        const val URL_EXTRA = "com.kompi.app.notification.HTTPS_URL"
        fun safeHttps(value: String?): String? {
            if (value == null || value.length > NOTIFICATION_URL_MAX_CHARS || value.any { it.isISOControl() || it.isWhitespace() } ||
                Regex("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f)").containsMatchIn(value)) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            return value.takeIf {
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.port in -1..65535 && !uri.rawAuthority.orEmpty().contains('@')
            }
        }
    }
}
