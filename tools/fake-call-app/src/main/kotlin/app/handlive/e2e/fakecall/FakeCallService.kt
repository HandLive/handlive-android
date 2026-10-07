package app.handlive.e2e.fakecall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat

/**
 * One fake call at a time, shaped like Telegram's (CALL-05 spike T3.2): ringing is a `CallStyle` incoming notification
 * of this phoneCall foreground service with decline and answer intents; in call, the service's notification is an
 * ordinary ongoing one with a single Hang Up action, and the audio mode is `MODE_IN_COMMUNICATION` with a voice stream
 * of silence playing (AudioService gives the mode back to `MODE_NORMAL` after 6 s when its owner plays and records
 * nothing, which a real call never does). An upload is an
 * unrelated ongoing notification whose single action is Cancel. Every intent HandLive sends is logged ([FakeCallLog]).
 */
class FakeCallService : Service() {
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var voice: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        channels()
        when (intent?.action) {
            RING -> ring()
            ANSWER -> answer()
            DECLINE -> end("decline_received")
            HANG_UP -> end("hang_up_received")
            HANG_UP_HERE -> end("hung_up")
            UPLOAD -> upload()
            CANCEL_UPLOAD -> clearUpload("upload_cancel_received")
            CLEAR_UPLOAD -> clearUpload("upload_cleared")
        }
        return START_NOT_STICKY
    }

    private fun ring() {
        val caller = Person.Builder().setName(getString(R.string.caller)).build()
        val ringing =
            NotificationCompat
                .Builder(this, CHANNEL_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, pending(DECLINE), pending(ANSWER)))
                .build()
        foreground(RING_ID, ringing)
        FakeCallLog.event("ringing")
    }

    private fun answer() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        voice = voice ?: silence().also { it.play() }
        val inCall =
            NotificationCompat
                .Builder(this, CHANNEL_OTHER)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(getString(R.string.in_call))
                .setOngoing(true)
                .addAction(0, getString(R.string.hang_up), pending(HANG_UP))
                .build()
        foreground(IN_CALL_ID, inCall)
        notifications.cancel(RING_ID)
        FakeCallLog.event("in_call", "mode" to audio.mode)
    }

    private fun end(event: String) {
        voice?.run {
            stop()
            release()
        }
        voice = null
        audio.mode = AudioManager.MODE_NORMAL
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.cancel(RING_ID)
        notifications.cancel(IN_CALL_ID)
        FakeCallLog.event(event, "mode" to audio.mode)
        stopSelf()
    }

    /** One second of silence on a voice-communication stream, looped until the call ends. */
    private fun silence(): AudioTrack {
        val frames = SAMPLE_RATE
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)
        return track
    }

    private fun upload() {
        val upload =
            NotificationCompat
                .Builder(this, CHANNEL_OTHER)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.upload))
                .setOngoing(true)
                .addAction(0, getString(R.string.cancel), pending(CANCEL_UPLOAD))
                .build()
        notifications.notify(UPLOAD_ID, upload)
        FakeCallLog.event("upload")
    }

    private fun clearUpload(event: String) {
        notifications.cancel(UPLOAD_ID)
        FakeCallLog.event(event)
    }

    private fun foreground(
        id: Int,
        notification: android.app.Notification,
    ) = ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)

    /** A PendingIntent this app created itself, as HandLive requires before it sends one. */
    private fun pending(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, FakeCallService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun channels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH),
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_OTHER, getString(R.string.channel_other), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val RING = "app.handlive.e2e.fakecall.RING"
        const val ANSWER = "app.handlive.e2e.fakecall.ANSWER"
        const val DECLINE = "app.handlive.e2e.fakecall.DECLINE"
        const val HANG_UP = "app.handlive.e2e.fakecall.HANG_UP"
        const val HANG_UP_HERE = "app.handlive.e2e.fakecall.HANG_UP_HERE"
        const val UPLOAD = "app.handlive.e2e.fakecall.UPLOAD"
        const val CANCEL_UPLOAD = "app.handlive.e2e.fakecall.CANCEL_UPLOAD"
        const val CLEAR_UPLOAD = "app.handlive.e2e.fakecall.CLEAR_UPLOAD"

        private const val CHANNEL_CALLS = "calls"
        private const val CHANNEL_OTHER = "other"
        private const val RING_ID = 1
        private const val IN_CALL_ID = 2
        private const val UPLOAD_ID = 3
        private const val SAMPLE_RATE = 16_000
    }
}
