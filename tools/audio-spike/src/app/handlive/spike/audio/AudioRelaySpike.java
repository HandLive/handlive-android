package app.handlive.spike.audio;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Spike for the call-audio relay (plan §13 D10, AUDIO-02): runs as the shell user through
 * {@code app_process} (adb now, a HandLive-started helper later) and moves the audio of the
 * user's own VoIP call between the phone and the Mac.
 *
 * <ul>
 *   <li>{@code down}: captures players with usage VOICE_COMMUNICATION (the far end of the call)
 *   while the phone keeps rendering them, and writes 16 kHz mono s16le PCM to stdout. Apps that
 *   opt out of capture (allowedCapturePolicy) stay opted out: privileged capture is not requested.
 *   <li>{@code up}: reads 16 kHz mono s16le PCM from stdin and feeds it to recorders with capture
 *   preset VOICE_COMMUNICATION (the call's microphone), like a headset microphone.
 * </ul>
 *
 * Nothing is stored; one status line per second goes to stderr (levels only, no audio).
 */
public final class AudioRelaySpike {
    private static final int RATE = 16000;
    private static final int FRAME_BYTES = RATE / 50 * 2; // 20 ms mono s16le

    // android.media.audiopolicy constants (system API, read through reflection).
    private static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    private static final int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x2;
    private static final int MIX_ROLE_PLAYERS = 0;
    private static final int MIX_ROLE_INJECTOR = 1;
    private static final int ROUTE_FLAG_RENDER = 0x1;
    private static final int ROUTE_FLAG_LOOP_BACK = 0x2;

    private AudioRelaySpike() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("down") || args[0].equals("up"))) {
            System.err.println("usage: app_process / app.handlive.spike.audio.AudioRelaySpike down|up");
            System.exit(2);
        }
        Looper.prepareMainLooper();
        Context context = shellContext();
        if (args[0].equals("down")) {
            down(context);
        } else {
            up(context);
        }
    }

    private static void down(Context context) throws Exception {
        AudioAttributes call = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        Object ruleBuilder = newInstance("android.media.audiopolicy.AudioMixingRule$Builder");
        invoke(ruleBuilder, "setTargetMixRole", new Class<?>[] {int.class}, MIX_ROLE_PLAYERS);
        invoke(ruleBuilder, "addMixRule", new Class<?>[] {int.class, Object.class},
                RULE_MATCH_ATTRIBUTE_USAGE, call);
        // VOICE_COMMUNICATION players