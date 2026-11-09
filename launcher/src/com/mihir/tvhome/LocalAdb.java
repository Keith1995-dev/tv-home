package com.mihir.tvhome;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Tiny ADB client that talks to the TV's own debugging service on 127.0.0.1:5555, so the launcher can run
 * shell commands (pm enable/disable) that a normal app isn't allowed to. The first connection makes the TV
 * ask "Allow network debugging?"; tick "Always allow" once. The key lives only in this app's private files.
 */
final class LocalAdb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // ASN.1 DigestInfo header for SHA-1; adbd checks a PKCS#1 v1.5 signature over the raw 20-byte token.
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private final File dir;

    LocalAdb(File dir) { this.dir = dir; }

    /** Runs one shell command and returns its output. Blocks; call off the main thread. */
    String shell(String cmd) throws Exception {
        KeyPair keys = keys();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, 0x01000001, 256 * 1024, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSig = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected ADB reply");
                if (!sentSig) {
                    sentSig = true;
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                } else {
                    // Key not trusted yet: offer it, and give the person time to answer the prompt on screen.
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) keys.getPublic()));
                    s.setSoTimeout(60000);
                }
            }
            s.setSoTimeout(15000);
            send(out, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) remote = m.arg0;
                else if (m.cmd == WRTE) { result.write(m.data); send(out, OKAY, 1, m.arg0, new byte[0]); }
                else if (m.cmd == CLSE) { send(out, CLSE, 1, remote, new byte[0]); break; }
            }
            return result.toString("UTF-8");
        }
    }

    private static final class Msg { int cmd, arg0; byte[] data; }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad ADB message");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** adbd's key format: mincrypt RSAPublicKey struct, base64, then a name and a NUL. */
    private static byte[] publicKeyBlob(RSAPublicKey pub) {
        int words = 64;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = pub.getModulus();
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private KeyPair keys() throws Exception {
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream f = new FileOutputStream(priv)) { f.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream f = new FileOutputStream(pub)) { f.write(kp.getPublic().getEncoded()); }
        return kp;
    }
}
