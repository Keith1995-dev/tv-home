package com.mihir.tvhome;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Plays an internet radio stream (AAC in ADTS frames, or MP3). The TV's MediaPlayer tries to sniff a radio stream
 * as if it were a file and often stalls, so this reads the frames itself, hands them to the hardware decoder and
 * writes the sound straight to an AudioTrack.
 */
final class RadioPlayer {
    interface Listener { void onFailed(String why); }

    private final String url;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile java.net.HttpURLConnection conn;
    private volatile AudioTrack track;
    private volatile float volume = 1f;

    RadioPlayer(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(this::run, "radio");
        t.setPriority(Thread.MAX_PRIORITY - 1);
        t.start();
    }

    void stop() {
        stopped = true;
        java.net.HttpURLConnection c = conn;
        if (c != null) new Thread(c::disconnect).start(); // unblocks a read in progress
    }

    /** Silenced (not stopped) while another app briefly has the sound, e.g. voice search. */
    void setVolume(float v) {
        volume = v;
        AudioTrack t = track;
        if (t != null) t.setVolume(v);
    }

    private void run() {
        MediaCodec codec = null;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn = c;
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
            byte[] head = new byte[7];
            byte[] frame = new byte[8192];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!stopped) {
                // Find the next frame header: 0xFFF sync word, then ADTS (AAC) or an MPEG audio layer III header.
                int b = in.read();
                if (b < 0) throw new EOFException("stream ended");
                if (b != 0xFF) continue;
                int b1 = in.read();
                if (b1 < 0) throw new EOFException("stream ended");
                head[0] = (byte) 0xFF;
                head[1] = (byte) b1;
                boolean adts = (b1 & 0xF6) == 0xF0;
                boolean mp3 = (b1 & 0xE0) == 0xE0 && ((b1 >> 1) & 3) == 1; // layer III
                if (!adts && !mp3) continue;
                readFully(in, head, 2, adts ? 5 : 2);
                int len, rate, channels;
                MediaFormat fmt = null;
                if (adts) {
                    int profile = (head[2] >> 6) & 3, srIdx = (head[2] >> 2) & 0xF;
                    channels = ((head[2] & 1) << 2) | ((head[3] >> 6) & 3);
                    len = ((head[3] & 3) << 11) | ((head[4] & 0xFF) << 3) | ((head[5] & 0xFF) >> 5);
                    int headLen = (head[1] & 1) == 0 ? 9 : 7; // with or without a CRC
                    if (srIdx >= AAC_RATES.length || channels == 0 || len <= headLen) continue;
                    rate = AAC_RATES[srIdx];
                    if (headLen == 9) readFully(in, new byte[2], 0, 2);
                    len -= headLen;
                    if (codec == null) {
                        fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
                        int asc = ((profile + 1) << 11) | (srIdx << 7) | (channels << 3);
                        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{(byte) (asc >> 8), (byte) asc}));
                    }
                } else {
                    int version = (b1 >> 3) & 3; // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
                    int brIdx = (head[2] >> 4) & 0xF, srIdx = (head[2] >> 2) & 3, pad = (head[2] >> 1) & 1;
                    if (version == 1 || brIdx == 0 || brIdx == 15 || srIdx == 3) continue;
                    int kbps = (version == 3 ? MP3_KBPS_V1 : MP3_KBPS_V2)[brIdx];
                    rate = MP3_RATES[srIdx] >> (version == 3 ? 0 : version == 2 ? 1 : 2);
                    channels = ((head[3] >> 6) & 3) == 3 ? 1 : 2;
                    len = (version == 3 ? 144000 : 72000) * kbps / rate + pad;
                    // The decoder wants whole frames, header included.
                    System.arraycopy(head, 0, frame, 0, 4);
                    if (codec == null) fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, rate, channels);
                }
                int off = adts ? 0 : 4;
                if (len > frame.length) continue;
                readFully(in, frame, off, len - off);
                if (codec == null) {
                    codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                    codec.configure(fmt, null, null, 0);
                    codec.start();
                }
                int ii = codec.dequeueInputBuffer(200_000);
                if (ii >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(ii);
                    ib.clear();
                    ib.put(frame, 0, len);
                    codec.queueInputBuffer(ii, 0, len, 0, 0);
                }
                drain(codec, info, rate, channels);
            }
        } catch (Exception e) {
            if (!stopped) {
                android.util.Log.e("tvhome", "radio failed: " + e + " " + url);
                listener.onFailed(String.valueOf(e.getMessage()));
            }
        } finally {
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            AudioTrack t = track;
            track = null;
            if (t != null) { try { t.stop(); } catch (RuntimeException ignored) { } t.release(); }
            java.net.HttpURLConnection c = conn;
            if (c != null) c.disconnect();
        }
    }

    /** Decoded sound to the speakers; the blocking write is what paces reading the stream. */
    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, int rate, int channels) {
        while (!stopped) {
            int oi = codec.dequeueOutputBuffer(info, 0);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                openTrack(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                continue;
            }
            if (oi < 0) return;
            if (track == null) openTrack(rate, channels);
            ByteBuffer ob = codec.getOutputBuffer(oi);
            if (info.size > 0 && ob != null) {
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                track.write(ob, info.size, AudioTrack.WRITE_BLOCKING);
            }
            codec.releaseOutputBuffer(oi, false);
        }
    }

    private void openTrack(int rate, int channels) {
        AudioTrack old = track;
        if (old != null && old.getSampleRate() == rate && old.getChannelCount() == channels) return;
        if (old != null) old.release();
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min * 4, rate * channels)) // about half a second
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        t.setVolume(volume);
        t.play();
        track = t;
    }

    private static void readFully(InputStream in, byte[] b, int off, int n) throws IOException {
        while (n > 0) {
            int r = in.read(b, off, n);
            if (r < 0) throw new EOFException("stream ended");
            off += r;
            n -= r;
        }
    }

    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    private static final int[] MP3_RATES = {44100, 48000, 32000};
    private static final int[] MP3_KBPS_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] MP3_KBPS_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
}
