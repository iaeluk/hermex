package com.iaeluk.hermex.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens http(s) links in an in-app Custom Tab, falling back to the default browser. */
internal fun openExternalUrl(context: Context, uri: Uri) {
    val openedInCustomTab = runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }.isSuccess
    if (!openedInCustomTab) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
