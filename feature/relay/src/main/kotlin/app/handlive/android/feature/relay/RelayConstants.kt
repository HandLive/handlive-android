package app.handlive.android.feature.relay

/** Constants of 0.10 the phone's relay client follows. */
object RelayConstants {
    /** `RELAY_IDLE_DISCONNECT`: the phone leaves the relay by itself after 5 idle minutes (CONN-03). */
    const val IDLE_DISCONNECT_MILLIS = 5 * 60 * 1000L

    /** How often the idle rule is checked. */
    const val IDLE_CHECK_MILLIS = 30_000L

    /** `RECONNECT_BACKOFF`: 0.5 → 1 → 2 → 4 → 8 → 16 → 30 s (doubling, capped), jitter ±20 %. */
    const val BACKOFF_FIRST_MILLIS = 500L
    const val BACKOFF_MAX_MILLIS = 30_000L
    const val BACKOFF_JITTER = 0.2

    /** PAIR-02 step 4: `GET /v1/pairs` at most once every 60 s. */
    const val PAIRS_CHECK_MIN_INTERVAL_MILLIS = 60_000L

    /** PAIR-02 API 1 logic 3: a pair the relay refused with 404 (the peer is not registered) is retried after 24 h. */
    const val PAIR_RETRY_AFTER_404_MILLIS = 24 * 60 * 60 * 1000L

    /** CONN-04 step 2: the push token goes to the relay again every 7 days. */
    const val PUSH_TOKEN_REFRESH_MILLIS = 7 * 24 * 60 * 60 * 1000L

    /** CONN-04 5b: an SMS push waits in `push_outbox` for 24 h at most. */
    const val SMS_PUSH_EXPIRY_MILLIS = 24 * 60 * 60 * 1000L

    /** `push_outbox` retries: 5 s, 15 s, 45 s… at most 5 minutes apart, until the push expires. */
    const val PUSH_RETRY_FIRST_MILLIS = 5_000L
    const val PUSH_RETRY_FACTOR = 3L
    const val PUSH_RETRY_MAX_MILLIS = 5 * 60 * 1000L
    const val PUSH_OUTBOX_BATCH = 20
}
