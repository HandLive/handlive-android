package app.handlive.android.core.transport

/** A session socket with a separate bulk lane (the Bluetooth link); other sockets carry both lanes as text. */
interface BulkLaneSocket {
    /** Queues [envelope] on channel `0x02`; suspends while 0.4.7 bulk rule 4 holds the queue full. */
    suspend fun sendBulk(envelope: String)
}
