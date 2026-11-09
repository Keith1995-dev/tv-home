package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder for short text such as a URL: byte mode, error correction level M,
 * versions 1 to 6 (up to 106 bytes). Follows ISO/IEC 18004; structure after Nayuki's reference encoder.
 */
final class QrCode {

    // Per version (index 1..6), level M: total codewords, EC codewords per block, number of blocks.
    private static final int[] TOTAL = {0, 26, 44, 70, 100, 134, 172};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] ALIGN = {0, 0, 18, 22, 26, 30, 34}; // second alignment coordinate (first is 6)

    final int size;
    int mask;
    private final boolean[][] dark, isFunction;

    private QrCode(int version) {
        size = version * 4 + 17;
        dark = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    boolean get(int x, int y) { return dark[y][x]; }

    /** Encodes text; throws if it's longer than a version 6 code holds. */
    static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 6 && dataCodewords(version) < 2 + data.length) version++; // 4-bit mode + 8-bit length + data
        if (version > 6) throw new IllegalArgumentException("text too long for a QR code: " + data.length);
        int capacity = dataCodewords(version);

        // Bit stream: mode 0100 (byte), length, data, terminator, pad to a byte, then alternating pad bytes.
        BitBuffer bb = new BitBuffer();
        bb.append(4, 4);
        bb.append(data.length, 8);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[capacity];
        for (int i = 0; i < bb.length; i++) if (bb.bit(i)) codewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));

        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns(version);
        qr.drawCodewords(qr.interleave(version, codewords));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            qr.applyMask(m);
            qr.drawFormatBits(m);
            long p = qr.penalty();
            if (p < bestPenalty) { best = m; bestPenalty = p; }
            qr.applyMask(m); // XOR again to undo
        }
        qr.applyMask(best);
        qr.drawFormatBits(best);
        qr.mask = best;
        return qr;
    }

    /** The code as a bitmap with a 4-module quiet zone, each module scale pixels. */
    Bitmap toBitmap(int scale, int fg, int bg) {
        int n = (size + 8) * scale;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int mx = x / scale - 4, my = y / scale - 4;
                boolean on = mx >= 0 && my >= 0 && mx < size && my < size && dark[my][mx];
                px[y * n + x] = on ? fg : bg;
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    static Bitmap bitmap(String text, int scale) { return encode(text).toBitmap(scale, Color.BLACK, Color.WHITE); }

    private static int dataCodewords(int version) { return TOTAL[version] - EC_PER_BLOCK[version] * BLOCKS[version]; }

    // ---------------------------------------------------------------- function patterns

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) { // timing
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        if (version >= 2) drawAlignment(ALIGN[version], ALIGN[version]); // the only one not overlapping a finder
        drawFormatBits(0); // reserve the area; real bits are drawn after masking
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFunction(x, y, d != 2 && d != 4);
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private void drawFormatBits(int mask) {
        int data = (0 << 3) | mask; // level M is 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true); // the dark module
    }

    private void setFunction(int x, int y, boolean on) {
        dark[y][x] = on;
        isFunction[y][x] = true;
    }

    // ---------------------------------------------------------------- data

    /** Splits data into blocks, adds Reed-Solomon EC to each, and interleaves them. */
    private byte[] interleave(int version, byte[] data) {
        int blocks = BLOCKS[version], ecLen = EC_PER_BLOCK[version], total = TOTAL[version];
        int shortBlocks = blocks - total % blocks, shortLen = total / blocks;
        byte[] divisor = rsDivisor(ecLen);
        byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int dataLen = shortLen - ecLen + (i < shortBlocks ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + dataLen);
            k += dataLen;
            byte[] block = java.util.Arrays.copyOf(dat, shortLen + 1);
            byte[] ec = rsRemainder(dat, divisor);
            System.arraycopy(ec, 0, block, block.length - ecLen, ecLen);
            out[i] = block;
        }
        byte[] result = new byte[total];
        int r = 0;
        for (int i = 0; i < out[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                // Short blocks have no byte at index shortLen - ecLen (the padding slot).
                if (i != shortLen - ecLen || j >= shortBlocks) result[r++] = out[j][i];
            }
        }
        return result;
    }

    /** Zigzag placement in two-module columns, right to left, skipping the vertical timing column. */
    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!isFunction[y][x] && invert) dark[y][x] = !dark[y][x];
            }
        }
    }

    /** Spec penalty rules 1 (runs), 2 (2x2 blocks) and 4 (dark balance); good enough to pick a readable mask. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean last = false;
                for (int b = 0; b < size; b++) {
                    boolean c = pass == 0 ? dark[a][b] : dark[b][a];
                    if (b > 0 && c == last) {
                        run++;
                        if (run == 5) p += 3; else if (run > 5) p++;
                    } else {
                        run = 1;
                        last = c;
                    }
                }
            }
        }
        int darkCount = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) darkCount++;
                if (x < size - 1 && y < size - 1) {
                    boolean c = dark[y][x];
                    if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    // ---------------------------------------------------------------- Reed-Solomon over GF(256), polynomial 0x11D

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        int length;

        void append(int val, int len) {
            for (int i = len - 1; i >= 0; i--) bits.set(length++, ((val >>> i) & 1) != 0);
        }

        boolean bit(int i) { return bits.get(i); }
    }
}
