package at.jku.ins.chat;

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service;
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.tor_chat.MessagingClient
import uniffi.tor_chat.generateKey


class MessagingService : Service() {


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle start command if needed

        if (isServiceRunning) {
            return START_STICKY
        }

        System.loadLibrary("tor_chat")

        CoroutineScope(Dispatchers.IO).launch {
            chatService = MessagingClient(cacheDir.absolutePath)

            ownOnionAddress = chatService!!.onionServiceFromSk(generateKey())+".onion"
        }

        if (intent != null) {
            println(intent)
            println(intent.`package`)
        }
        val channel = NotificationChannel(
            "torchat", "Foreground Service Channel", NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(
            NotificationManager::class.java
        )
        manager.createNotificationChannel(channel)


        // Start the service in the foreground
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent("at.jku.ins.chat.ACTION_KILL_SERVICE").apply {
            `package` = "at.jku.ins.chat"
        }
        val stopPendingIntent =
            PendingIntent.getBroadcast(this, 1234, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        // Create the notification
        val builder =
            NotificationCompat
                .Builder(this, "torchat")
                .setContentTitle("TorChat")
                .setContentText("Running...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(R.drawable.ic_launcher_foreground, "Kill Service", stopPendingIntent)

        // Start the service in the foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                builder.build(),
                FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
        isServiceRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources if needed
        isServiceRunning = false
    }

    public companion object {
        private var isServiceRunning = false
        public var ownOnionAddress: String? = null

        public var chatService: MessagingClient? = null
    }

}