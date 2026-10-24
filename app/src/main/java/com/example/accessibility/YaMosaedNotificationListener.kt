package com.example.accessibility

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class YaMosaedNotificationListener : NotificationListenerService() {

    companion object {
        private var instance: YaMosaedNotificationListener? = null

        private val _isNotificationListenerConnected = MutableStateFlow(false)
        val isNotificationListenerConnected: StateFlow<Boolean> = _isNotificationListenerConnected

        private val _currentMediaTitle = MutableStateFlow("")
        val currentMediaTitle: StateFlow<String> = _currentMediaTitle

        fun getInstance(): YaMosaedNotificationListener? = instance
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isNotificationListenerConnected.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isNotificationListenerConnected.value = false
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName
        if (pkg == "com.spotify.music" || pkg.contains("music", ignoreCase = true)) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val artist = extras.getCharSequence("android.text")?.toString() ?: ""
            if (title.isNotBlank()) {
                _currentMediaTitle.value = "$title - $artist"
            }
        }
    }
}
