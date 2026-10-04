package app.handlive.spike.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Downlink capture probe for the call-audio spike (CALL-04 / plan D10).
 *
 * Runs under the shell uid via `app_process` (no app installed), which holds CAPTURE_AUDIO_OUTPUT and
 * CAPTURE_VOICE_COMMUNICATION_OUTPUT. It opens an {@link AudioRecord} on the source named by argv[0] and writes
 * raw 16-bit little-endian mono PCM to stdout, so the Mac can play it with ffplay. argv[1] is the sample rate.
 *
 * It never writes to a file and prints only diagnostics (source, state, a rough RMS level) to stderr.
 */
public final class AudioSpike {
    public static void main(String[] args) throws IOException {
        String sourceName = args.length > 0 ? args[0] : "VOICE_COMMUNICATION";
        int sampleRate = args.length > 1 ? Integer.parseInt(args[1]) : 16000;
        int source = sourceFor(sourceName);

        int minBuffer = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) minBuffer = sampleRate; // fall back to ~0.5 s of 16-bit mono
        int bufferBytes = Math.max(minBuffer, sampleRate); // headroom

        AudioRecord record;
        try {
            record = new AudioRecord(source, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        } catch (IllegalArgumentException e) {
            System.err.println("spike: cannot build AudioRecord for " + sourceName + ": " + e.getMessage());
            System.exit(2);
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            System.err.println("spike: AudioRecord not initialized for " + sourceName
                    + " (state=" + record.getState() + ")");
            record.release();
            System.exit(3);
            return;
        }

        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            System.err.println("spike: recording did not start for " + sourceName
                    + " (state=" + record.getRecordingState() + ")");
            record.release();
            System.exit(4);
            return;
        }
        System.err.println("spike: capturing " + sourceName + " at " + sampleRate + " Hz; Ctrl-C to stop");

        byte[] buffer = new byte[bufferBytes];
        OutputStream out = System.out;
        long frames = 0;
        long nextReport = System.currentTimeMillis() + 1000;
        while (true) {
            int read = record.read(buffer, 0, buffer.length);
            if (read < 0) {
                System.err.println("spike: read error " + read);
                break;
            }
            if (read == 0) continue;
            out.write(buffer, 0, read);
            out.flush();
            frames += read / 2;
            long now = System.currentTimeMillis();
            if (now >= nextReport) {
                System.err.println("spike: " + sourceName + " rms=" + rms(buffer, read)
                        + " frames=" + frames);
                nextReport = now + 1000;
            }
        }
        record.stop();
        record.release();
    }

    /** A coarse RMS of the PCM block, to tell silence from real audio in the stderr log. */
    private static long rms(byte[] buffer, int length) {
        long sum = 0;
        int samples = length / 2;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
            sum += (long) sample * sample;
        }
        return samples == 0 ? 0 : (long) Math.sqrt((double) sum / samples);
    }

    private static int sourceFor(String name) {
        switch (name) {
            case "MIC": return MediaRecorder.AudioSource.MIC;
            case "VOICE_CALL": return MediaRecorder.AudioSource.VOICE_CALL;        // 4
            case "VOICE_DOWNLINK": return MediaRecorder.AudioSource.VOICE_DOWNLINK; // 6
            case "VOICE_COMMUNICATION": return MediaRecorder.AudioSource.VOICE_COMMUNICATION; // 7
            case "REMOTE_SUBMIX": return MediaRecorder.AudioSource.REMOTE_SUBMIX;   // 8
            case "VOICE_RECOGNITION": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            default:
                throw new IllegalArgumentException("unknown source " + name);
        }
    }

    private AudioSpike() {}
}
