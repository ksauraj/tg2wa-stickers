package dev.ksauraj.tg2wa

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.ksauraj.tg2wa.security.SecureStore
import dev.ksauraj.tg2wa.store.PackStore
import dev.ksauraj.tg2wa.telegram.TelegramRepository

class App : Application() {
    lateinit var secure: SecureStore
        private set
    lateinit var telegram: TelegramRepository
        private set
    lateinit var packs: PackStore
        private set

    override fun onCreate() {
        super.onCreate()
        secure = SecureStore(this)
        telegram = TelegramRepository(this, secure)
        packs = PackStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW),
        )
        telegram.start()
    }

    companion object {
        const val SYNC_CHANNEL = "sync"
    }
}
