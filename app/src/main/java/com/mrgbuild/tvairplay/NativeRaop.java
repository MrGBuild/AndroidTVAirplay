package com.mrgbuild.tvairplay;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Legacy RAOP/ALAC bridge. Native sessions own their AudioTrack lifetime. */
public final class NativeRaop implements AutoCloseable {
    static { System.loadLibrary("tvaudio"); }
    public interface Listener { void onAudioState(boolean playing, String message); }
    private final Listener listener;
    private long handle;
    public NativeRaop(Context context, String deviceId, String password, Listener listener) throws IOException {
        PasswordStore.validate(password);
        this.listener = listener;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("airport.key")) {
            byte[] chunk = new byte[4096]; int n;
            while ((n = input.read(chunk)) != -1) data.write(chunk, 0, n);
        }
        String[] parts = deviceId.split(":"); byte[] address = new byte[6];
        for (int i = 0; i < 6; i++) address[i] = (byte)Integer.parseInt(parts[i], 16);
        handle = startNative(address, new String(data.toByteArray(), StandardCharsets.US_ASCII), password);
        if (handle == 0) throw new IOException("RAOP listener could not start");
    }
    public int port() { return portNative(handle); }
    @Override public void close() { if (handle != 0) { stopNative(handle); handle = 0; } }
    private native long startNative(byte[] address, String pem, String password);
    private native int portNative(long handle);
    private native void stopNative(long handle);
    @SuppressWarnings("unused") // JNI entry point
    private Session createSession(int bits, int channels, int rate) {
        try { return new Session(bits, channels, rate, listener); }
        catch (RuntimeException e) { listener.onAudioState(false, "TV audio output could not start"); return null; }
    }
    public static final class Session {
        private final AudioTrack track;
        private final Listener listener;
        Session(int bits, int channels, int rate, Listener listener) {
            if (bits != 16 || channels != 2 || rate < 8000 || rate > 192000) throw new IllegalArgumentException("Unsupported PCM format");
            this.listener = listener;
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalArgumentException("Unsupported audio rate");
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, rate * 4 / 5)).build();
            track.play(); listener.onAudioState(true, "Receiving legacy AirPlay audio • use your sender for controls");
        }
        @SuppressWarnings("unused") public void write(byte[] bytes) {
            int offset = 0;
            while (offset < bytes.length) {
                int n = track.write(bytes, offset, bytes.length - offset, AudioTrack.WRITE_BLOCKING);
                if (n <= 0) return; offset += n;
            }
        }
        @SuppressWarnings("unused") public void volume(float decibels) {
            if (Float.isFinite(decibels)) track.setVolume(decibels <= -144 ? 0 : (float)Math.pow(10, Math.min(0, decibels) / 20));
        }
        @SuppressWarnings("unused") public void flush() { track.pause(); track.flush(); track.play(); }
        @SuppressWarnings("unused") public void close() {
            track.stop(); track.release(); listener.onAudioState(false, "AirPlay audio disconnected");
        }
    }
}
