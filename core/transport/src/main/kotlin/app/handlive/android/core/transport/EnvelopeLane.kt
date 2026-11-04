package app.handlive.android.core.transport

/** Which physical lane an envelope travels on (0.4.7); ignored on a LAN or relay session ("single lane"). */
enum class EnvelopeLane {
    CONTROL,
    BULK,
}
