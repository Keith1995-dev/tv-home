package com.mihir.tvhome;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A tiny Matter controller for one light: opens a CASE session with the bulb over UDP and sends On/Off,
 * Level Control and Color Control commands, so the TV drives the bulb itself with no computer in between.
 * It reuses a fabric that a full controller (matter.js, see tools/lights/README.md) set up when it paired the
 * bulb; the fabric's secrets live in secrets.xml as matter_bulb. Plain Java, so it also runs on a computer.
 *
 * The bulb is trusted because its Sigma2 only decrypts with the fabric's identity protection key and is signed
 * by the key in its operational certificate, whose node and fabric IDs are checked; the certificate chain
 * itself isn't re-verified.
 */
public final class MatterBulb {
    public static final int ON_OFF = 0x0006, LEVEL = 0x0008, COLOR = 0x0300;

    private final long fabricId, nodeId, peerId;
    private final int endpoint;
    private final byte[] ipk, rootPub, noc, icac;
    private final PrivateKey opKey;
    private final List<InetAddress> addrs = new ArrayList<>();
    private final SecureRandom rnd = new SecureRandom();

    private DatagramSocket sock;
    private InetAddress peerAddr;
    private byte[] encKey, decKey;
    private int peerSession, counter, exchange;
    private long peerCounterMax = -1;

    /** creds: fabric=;node=;peer=;ep=;ipk=;root=;noc=;icac=;key=;addr=a,b (ids decimal, bytes hex). */
    public MatterBulb(String creds) throws Exception {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : creds.split(";")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
        }
        fabricId = Long.parseUnsignedLong(m.get("fabric"));
        nodeId = Long.parseUnsignedLong(m.get("node"));
        peerId = Long.parseUnsignedLong(m.get("peer"));
        endpoint = Integer.parseInt(m.containsKey("ep") ? m.get("ep") : "1");
        ipk = hex(m.get("ipk"));
        rootPub = hex(m.get("root"));
        noc = hex(m.get("noc"));
        icac = m.containsKey("icac") && !m.get("icac").isEmpty() ? hex(m.get("icac")) : null;
        opKey = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, hex(m.get("key"))), p256()));
        for (String a : m.get("addr").split(",")) if (!a.trim().isEmpty()) addrs.add(InetAddress.getByName(a.trim()));
    }

    // ---------------------------------------------------------------- commands

    public synchronized boolean invoke(int cluster, int command, byte[] fields) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (encKey == null) connect();
                Tlv w = new Tlv().struct(-1).bool(0, false).bool(1, false).array(2)
                        .struct(-1).list(0).uint(0, endpoint).uint(1, cluster).uint(2, command).end();
                w.raw(fields == null ? new Tlv().struct(1).end().bytes() : fields);
                byte[] req = w.end().end().uint(0xFF, 12).end().bytes();
                Msg r = exchange(1, 0x08, req, 0x09);
                // InvokeResponses[0] is either CommandData or a CommandStatus whose status 0 means Success.
                Node st = Node.parse(r.payload).get(1).at(0).get(1);
                return st == null || st.get(1) == null || st.get(1).get(0).num() == 0;
            } catch (Exception e) {
                close(); // a stale session or a missed reply: open a fresh session once
                if (attempt == 1) log("invoke " + Integer.toHexString(cluster) + "/" + command + ": " + e);
            }
        }
        return false;
    }

    public boolean on() { return invoke(ON_OFF, 0x01, null); }
    public boolean off() { return invoke(ON_OFF, 0x00, null); }

    public boolean level(int level, int tenths) {
        return invoke(LEVEL, 0x00, new Tlv().struct(1).uint(0, level).uint(1, tenths).uint(2, 0).uint(3, 0).end().bytes());
    }

    public boolean hueSat(int hue, int sat, int tenths) {
        return invoke(COLOR, 0x06, new Tlv().struct(1).uint(0, hue).uint(1, sat).uint(2, tenths).uint(3, 0).uint(4, 0).end().bytes());
    }

    public boolean mireds(int mireds, int tenths) {
        return invoke(COLOR, 0x0A, new Tlv().struct(1).uint(0, mireds).uint(1, tenths).uint(2, 0).uint(3, 0).end().bytes());
    }

    /** The bulb's state: {on 0/1, level, colour mode, hue, saturation, mireds}; null when it doesn't answer. */
    public synchronized long[] state() {
        int[][] want = {{ON_OFF, 0}, {LEVEL, 0}, {COLOR, 8}, {COLOR, 0}, {COLOR, 1}, {COLOR, 7}};
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (encKey == null) connect();
                Tlv w = new Tlv().struct(-1).array(0);
                for (int[] a : want) w.list(-1).uint(2, endpoint).uint(3, a[0]).uint(4, a[1]).end();
                Msg r = exchange(1, 0x02, w.end().bool(3, true).uint(0xFF, 12).end().bytes(), 0x05);
                long[] out = new long[want.length];
                Arrays.fill(out, -1);
                Node report = Node.parse(r.payload);
                Node reports = report.get(1);
                for (int i = 0; reports != null && i < reports.kids.size(); i++) {
                    Node data = reports.at(i).get(1);
                    if (data == null || data.get(2) == null) continue;
                    Node path = data.get(1);
                    long cl = path.get(3).num(), at = path.get(4).num();
                    for (int k = 0; k < want.length; k++) {
                        if (want[k][0] == cl && want[k][1] == at) out[k] = data.get(2).num();
                    }
                }
                Node sup = report.get(4);
                if (sup == null || sup.num() == 0) { // the bulb wants a StatusResponse for the report
                    send(1, 0x01, new Tlv().struct(-1).uint(0, 0).uint(0xFF, 12).end().bytes(), r.exchangeId, false, r.counterIfReliable());
                }
                return out;
            } catch (Exception e) {
                close();
                if (attempt == 1) log("read: " + e);
            }
        }
        return null;
    }

    public synchronized void close() {
        encKey = decKey = null;
        if (sock != null) sock.close();
        sock = null;
    }

    // ---------------------------------------------------------------- CASE

    private void connect() throws Exception {
        sock = new DatagramSocket();
        sock.setSoTimeout(1500);
        Exception last = null;
        for (InetAddress a : addrs) {
            try {
                peerAddr = a;
                caseHandshake();
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        close();
        throw last != null ? last : new Exception("no address");
    }

    private void caseHandshake() throws Exception {
        encKey = null;
        long ephNode = rnd.nextLong();
        counter = rnd.nextInt() & 0x0FFFFFFF;
        exchange = rnd.nextInt() & 0xFFFF;
        int ourSession = 1 + (rnd.nextInt() & 0x7FFE);

        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair eph = g.generateKeyPair();
        byte[] ephPub = encodePoint(((ECPublicKey) eph.getPublic()).getW());
        byte[] random = new byte[32];
        rnd.nextBytes(random);

        byte[] destId = hmac(ipk, cat(random, rootPub, le(fabricId, 8), le(peerId, 8)));
        byte[] sigma1 = new Tlv().struct(-1).bytes(1, random).uint(2, ourSession).bytes(3, destId).bytes(4, ephPub).end().bytes();
        Msg m2 = unsecured(ephNode, 0x30, sigma1, 0x31, -1);
        Node s2 = Node.parse(m2.payload);
        byte[] peerRandom = s2.get(1).bin(), peerEph = s2.get(3).bin(), enc2 = s2.get(4).bin();
        int peerSessionId = (int) s2.get(2).num();

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(eph.getPrivate());
        ka.doPhase(publicKey(peerEph), true);
        byte[] shared = ka.generateSecret();

        byte[] k2 = hkdf(shared, cat(ipk, peerRandom, peerEph, sha256(sigma1)), "Sigma2", 16);
        Node tbe2 = Node.parse(ccm(false, k2, "NCASE_Sigma2N".getBytes("UTF-8"), enc2, new byte[0]));
        byte[] peerNoc = tbe2.get(1).bin();
        byte[] peerIcac = tbe2.get(2) == null ? null : tbe2.get(2).bin();
        Tlv tbs2 = new Tlv().struct(-1).bytes(1, peerNoc);
        if (peerIcac != null) tbs2.bytes(2, peerIcac);
        tbs2.bytes(3, peerEph).bytes(4, ephPub).end();
        Node cert = Node.parse(peerNoc);
        Node subject = cert.get(6);
        if (subject.get(17).num() != peerId || subject.get(21).num() != fabricId) throw new Exception("not our bulb");
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(publicKey(cert.get(9).bin()));
        v.update(tbs2.bytes());
        if (!v.verify(derSig(tbe2.get(3).bin()))) throw new Exception("bad Sigma2 signature");

        byte[] k3 = hkdf(shared, cat(ipk, sha256(cat(sigma1, m2.payload))), "Sigma3", 16);
        Tlv tbs3 = new Tlv().struct(-1).bytes(1, noc);
        if (icac != null) tbs3.bytes(2, icac);
        tbs3.bytes(3, ephPub).bytes(4, peerEph).end();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(opKey);
        s.update(tbs3.bytes());
        Tlv tbe3 = new Tlv().struct(-1).bytes(1, noc);
        if (icac != null) tbe3.bytes(2, icac);
        tbe3.bytes(3, rawSig(s.sign())).end();
        byte[] enc3 = ccm(true, k3, "NCASE_Sigma3N".getBytes("UTF-8"), tbe3.bytes(), new byte[0]);
        byte[] sigma3 = new Tlv().struct(-1).bytes(1, enc3).end().bytes();
        Msg st = unsecured(ephNode, 0x32, sigma3, 0x40, m2.counter);
        // StatusReport: general code 0 and protocol code 0 (SessionEstablishmentSuccess).
        if (le16(st.payload, 0) != 0 || le16(st.payload, 6) != 0) throw new Exception("CASE refused " + hex(st.payload));
        sendUnsecuredAck(ephNode, st);

        byte[] keys = hkdf(shared, cat(ipk, sha256(cat(sigma1, m2.payload, sigma3))), "SessionKeys", 48);
        encKey = Arrays.copyOfRange(keys, 0, 16);
        decKey = Arrays.copyOfRange(keys, 16, 32);
        peerSession = peerSessionId;
        peerCounterMax = -1;
        counter = rnd.nextInt() & 0x0FFFFFFF;
    }

    // ---------------------------------------------------------------- messages

    private static final class Msg {
        int exchangeFlags, opcode, exchangeId = -1, protocol;
        long counter, ackCounter = -1;
        byte[] payload;
        long counterIfReliable() { return (exchangeFlags & 0x04) != 0 ? counter : -1; }
    }

    /** One request/response on an unsecured exchange (CASE). */
    private Msg unsecured(long ephNode, int opcode, byte[] payload, int want, long ack) throws Exception {
        int ctr = counter++;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x04); // version 0, source node ID present
        b.write(le(0, 2));
        b.write(0x00);
        b.write(le(ctr, 4));
        b.write(le(ephNode, 8));
        b.write(appHeader(0, opcode, exchange, ack));
        b.write(payload);
        byte[] pkt = b.toByteArray();
        for (int tries = 0; tries < 4; tries++) {
            sock.send(new DatagramPacket(pkt, pkt.length, peerAddr, 5540));
            long until = System.currentTimeMillis() + 1500 + tries * 1000L;
            while (System.currentTimeMillis() < until) {
                Msg m = receive(true);
                if (m == null) break;
                if (m.exchangeId != exchange || m.protocol != 0) continue;
                if (m.opcode == want) return m;
                if (m.opcode == 0x40) throw new Exception("CASE status " + hex(m.payload));
                if (m.opcode == 0x10) until = System.currentTimeMillis() + 8000; // acked, the bulb is working on it
            }
        }
        throw new SocketTimeoutException("no answer to opcode " + Integer.toHexString(opcode));
    }

    private void sendUnsecuredAck(long ephNode, Msg st) throws Exception {
        if ((st.exchangeFlags & 0x04) == 0) return;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x04);
        b.write(le(0, 2));
        b.write(0x00);
        b.write(le(counter++, 4));
        b.write(le(ephNode, 8));
        b.write(appHeader(0, 0x10, exchange, st.counter));
        byte[] pkt = b.toByteArray();
        sock.send(new DatagramPacket(pkt, pkt.length, peerAddr, 5540));
    }

    /** One Interaction Model request on a new exchange of the secure session; returns the answer we want. */
    private Msg exchange(int protocol, int opcode, byte[] payload, int want) throws Exception {
        int ex = (++exchange) & 0xFFFF;
        byte[] pkt = secure(protocol, opcode, payload, ex, true, -1);
        for (int tries = 0; tries < 4; tries++) {
            sock.send(new DatagramPacket(pkt, pkt.length, peerAddr, 5540));
            long until = System.currentTimeMillis() + 1500 + tries * 1000L;
            while (System.currentTimeMillis() < until) {
                Msg m = receive(false);
                if (m == null) break;
                if (m.exchangeId != ex) continue;
                if (m.protocol == protocol && m.opcode == want) {
                    if (want != 0x05 || !hasStatusTodo(m)) ack(m);
                    return m;
                }
                if (m.protocol == protocol && m.opcode == 0x01) { ack(m); throw new Exception("IM status " + hex(m.payload)); }
                if (m.protocol == 0 && m.opcode == 0x40) throw new Exception("session closed");
                if (m.protocol == 0 && m.opcode == 0x10) until = System.currentTimeMillis() + 8000;
            }
        }
        throw new SocketTimeoutException("no answer");
    }

    private static boolean hasStatusTodo(Msg m) {
        try {
            Node sup = Node.parse(m.payload).get(4);
            return sup == null || sup.num() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void ack(Msg m) throws Exception {
        if ((m.exchangeFlags & 0x04) == 0) return;
        byte[] pkt = secure(0, 0x10, new byte[0], m.exchangeId, false, m.counter);
        sock.send(new DatagramPacket(pkt, pkt.length, peerAddr, 5540));
    }

    /** Sends one secured message on an exchange we started (ack piggybacked when given). */
    private void send(int protocol, int opcode, byte[] payload, int ex, boolean reliable, long ack) throws Exception {
        byte[] pkt = secure(protocol, opcode, payload, ex, reliable, ack);
        sock.send(new DatagramPacket(pkt, pkt.length, peerAddr, 5540));
    }

    private byte[] secure(int protocol, int opcode, byte[] payload, int ex, boolean reliable, long ack) throws Exception {
        int ctr = counter++;
        byte[] header = cat(new byte[] {0x00}, le(peerSession, 2), new byte[] {0x00}, le(ctr, 4));
        ByteArrayOutputStream app = new ByteArrayOutputStream();
        byte[] ah = appHeader(protocol, opcode, ex, ack);
        if (reliable) ah[0] |= 0x04;
        app.write(ah);
        app.write(payload);
        byte[] nonce = cat(new byte[] {0x00}, le(ctr, 4), le(nodeId, 8));
        return cat(header, ccm(true, encKey, nonce, app.toByteArray(), header));
    }

    /** Exchange header for messages we send as the exchange's initiator. */
    private static byte[] appHeader(int protocol, int opcode, int ex, long ack) {
        int flags = 0x01 | (protocol == 0 && opcode >= 0x30 && opcode <= 0x32 ? 0x04 : 0) | (ack >= 0 ? 0x02 : 0);
        byte[] h = cat(new byte[] {(byte) flags, (byte) opcode}, le(ex, 2), le(protocol, 2));
        return ack >= 0 ? cat(h, le(ack, 4)) : h;
    }

    private Msg receive(boolean unsecuredOk) throws Exception {
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            sock.receive(p);
        } catch (SocketTimeoutException e) {
            return null;
        }
        byte[] d = Arrays.copyOf(buf, p.getLength());
        int flags = d[0] & 0xFF, sec = d[3] & 0xFF;
        int session = le16(d, 1);
        long ctr = le32(d, 4);
        int off = 8;
        if ((flags & 0x04) != 0) off += 8;
        if ((flags & 0x03) == 1) off += 8;
        else if ((flags & 0x03) == 2) off += 2;
        byte[] app;
        if (session == 0) {
            if (!unsecuredOk) return new Msg();
            app = Arrays.copyOfRange(d, off, d.length);
        } else {
            if (decKey == null) return new Msg();
            byte[] nonce = cat(new byte[] {(byte) sec}, le(ctr, 4), le(peerId, 8));
            app = ccm(false, decKey, nonce, Arrays.copyOfRange(d, off, d.length), Arrays.copyOf(d, off));
        }
        Msg m = new Msg();
        m.counter = ctr;
        m.exchangeFlags = app[0] & 0xFF;
        m.opcode = app[1] & 0xFF;
        m.exchangeId = le16(app, 2);
        int o = 4;
        if ((m.exchangeFlags & 0x10) != 0) o += 2;
        m.protocol = le16(app, o);
        o += 2;
        if ((m.exchangeFlags & 0x02) != 0) { m.ackCounter = le32(app, o); o += 4; }
        if ((m.exchangeFlags & 0x08) != 0) o += 2 + le16(app, o);
        m.payload = Arrays.copyOfRange(app, o, app.length);
        if (session != 0) {
            if (ctr <= peerCounterMax) { // a retransmission we already handled
                ack(m);
                m.exchangeId = -1;
                return m;
            }
            peerCounterMax = ctr;
        }
        return m;
    }

    // ---------------------------------------------------------------- TLV

    /** A Matter TLV writer for the few element types the commands need. */
    static final class Tlv {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private Tlv tag(int tag, int type) {
            if (tag < 0) out.write(type);
            else { out.write(0x20 | type); out.write(tag); }
            return this;
        }

        Tlv struct(int tag) { return tag(tag, 0x15); }
        Tlv array(int tag) { return tag(tag, 0x16); }
        Tlv list(int tag) { return tag(tag, 0x17); }
        Tlv end() { out.write(0x18); return this; }
        Tlv bool(int tag, boolean v) { return tag(tag, v ? 0x09 : 0x08); }

        Tlv uint(int tag, long v) {
            if (v < 0x100) { tag(tag, 0x04); out.write((int) v); }
            else if (v < 0x10000) { tag(tag, 0x05); out.write(le(v, 2), 0, 2); }
            else if (v < 0x100000000L) { tag(tag, 0x06); out.write(le(v, 4), 0, 4); }
            else { tag(tag, 0x07); out.write(le(v, 8), 0, 8); }
            return this;
        }

        Tlv bytes(int tag, byte[] v) {
            if (v.length < 0x100) { tag(tag, 0x10); out.write(v.length); }
            else { tag(tag, 0x11); out.write(le(v.length, 2), 0, 2); }
            out.write(v, 0, v.length);
            return this;
        }

        Tlv raw(byte[] v) { out.write(v, 0, v.length); return this; }
        byte[] bytes() { return out.toByteArray(); }
    }

    /** A parsed TLV element: tag (-1 when anonymous), and a number, bytes or child elements. */
    static final class Node {
        int tag = -1;
        long num;
        byte[] bin;
        final List<Node> kids = new ArrayList<>();

        long num() { return num; }
        byte[] bin() { return bin; }
        Node at(int i) { return kids.get(i); }

        Node get(int tag) {
            for (Node k : kids) if (k.tag == tag) return k;
            return null;
        }

        static Node parse(byte[] d) {
            int[] pos = {0};
            return read(d, pos);
        }

        private static Node read(byte[] d, int[] pos) {
            Node n = new Node();
            int c = d[pos[0]++] & 0xFF, type = c & 0x1F, tagCtl = c >> 5;
            if (tagCtl == 1) n.tag = d[pos[0]++] & 0xFF;
            else if (tagCtl == 2 || tagCtl == 4) { n.tag = le16(d, pos[0]); pos[0] += tagCtl == 2 ? 2 : 2; }
            else if (tagCtl == 3 || tagCtl == 5) { n.tag = (int) le32(d, pos[0]); pos[0] += 4; }
            else if (tagCtl == 6) { pos[0] += 6; }
            else if (tagCtl == 7) { pos[0] += 8; }
            if (type <= 0x07) {
                int len = 1 << (type & 3);
                long v = 0;
                for (int i = len - 1; i >= 0; i--) v = (v << 8) | (d[pos[0] + i] & 0xFF);
                if (type < 0x04 && len < 8 && (v & (1L << (len * 8 - 1))) != 0) v -= 1L << (len * 8);
                n.num = v;
                pos[0] += len;
            } else if (type == 0x08 || type == 0x09) {
                n.num = type - 0x08;
            } else if (type == 0x0A) {
                pos[0] += 4;
            } else if (type == 0x0B) {
                pos[0] += 8;
            } else if (type >= 0x0C && type <= 0x13) {
                int lenBytes = 1 << (type & 3);
                long len = 0;
                for (int i = lenBytes - 1; i >= 0; i--) len = (len << 8) | (d[pos[0] + i] & 0xFF);
                pos[0] += lenBytes;
                n.bin = Arrays.copyOfRange(d, pos[0], pos[0] + (int) len);
                pos[0] += (int) len;
            } else if (type == 0x14) {
                n.num = -1; // null
            } else if (type >= 0x15 && type <= 0x17) {
                while ((d[pos[0]] & 0xFF) != 0x18) n.kids.add(read(d, pos));
                pos[0]++;
            }
            return n;
        }
    }

    // ---------------------------------------------------------------- crypto

    private static ECParameterSpec p256() throws Exception {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        return p.getParameterSpec(ECParameterSpec.class);
    }

    private static PublicKey publicKey(byte[] point) throws Exception {
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)), new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, p256()));
    }

    private static byte[] encodePoint(ECPoint w) {
        return cat(new byte[] {0x04}, fixed(w.getAffineX(), 32), fixed(w.getAffineY(), 32));
    }

    private static byte[] fixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) return b;
        byte[] out = new byte[len];
        int n = Math.min(b.length, len);
        System.arraycopy(b, b.length - n, out, len - n, n);
        return out;
    }

    /** DER ECDSA signature to Matter's raw r||s. */
    private static byte[] rawSig(byte[] der) {
        int o = 2 + ((der[1] & 0x80) != 0 ? (der[1] & 0x7F) : 0);
        int rl = der[o + 1];
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(der, o + 2, o + 2 + rl));
        o += 2 + rl;
        int sl = der[o + 1];
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(der, o + 2, o + 2 + sl));
        return cat(fixed(r, 32), fixed(s, 32));
    }

    private static byte[] derSig(byte[] raw) {
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        return cat(new byte[] {0x30, (byte) (4 + r.length + s.length), 0x02, (byte) r.length}, r, new byte[] {0x02, (byte) s.length}, s);
    }

    private static byte[] sha256(byte[] d) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(d);
    }

    private static byte[] hmac(byte[] key, byte[] d) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(d);
    }

    private static byte[] hkdf(byte[] ikm, byte[] salt, String info, int len) throws Exception {
        byte[] prk = hmac(salt, ikm);
        ByteArrayOutputStream okm = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; okm.size() < len; i++) {
            t = hmac(prk, cat(t, info.getBytes("UTF-8"), new byte[] {(byte) i}));
            okm.write(t);
        }
        return Arrays.copyOf(okm.toByteArray(), len);
    }

    /** AES-128-CCM with a 13-byte nonce and a 16-byte tag (what Matter uses); decrypt checks the tag. */
    static byte[] ccm(boolean encrypt, byte[] key, byte[] nonce, byte[] in, byte[] aad) throws Exception {
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        int len = encrypt ? in.length : in.length - 16;
        byte[] data = Arrays.copyOf(in, len);
        byte[] ctrBlock = new byte[16];
        ctrBlock[0] = 1; // L = 2
        System.arraycopy(nonce, 0, ctrBlock, 1, 13);
        byte[] plain = encrypt ? data : xorStream(aes, ctrBlock, data);
        // CBC-MAC over B0, the associated data and the plaintext.
        byte[] b0 = new byte[16];
        b0[0] = (byte) ((aad.length > 0 ? 0x40 : 0) | (7 << 3) | 1);
        System.arraycopy(nonce, 0, b0, 1, 13);
        b0[14] = (byte) (len >> 8);
        b0[15] = (byte) len;
        byte[] x = aes.doFinal(b0);
        if (aad.length > 0) x = macBlocks(aes, x, cat(new byte[] {(byte) (aad.length >> 8), (byte) aad.length}, aad));
        x = macBlocks(aes, x, plain);
        byte[] s0 = aes.doFinal(ctrBlock);
        byte[] tag = new byte[16];
        for (int i = 0; i < 16; i++) tag[i] = (byte) (x[i] ^ s0[i]);
        if (encrypt) return cat(xorStream(aes, ctrBlock, plain), tag);
        if (!MessageDigest.isEqual(tag, Arrays.copyOfRange(in, len, in.length))) throw new SecurityException("bad MIC");
        return plain;
    }

    private static byte[] macBlocks(Cipher aes, byte[] x, byte[] d) throws Exception {
        for (int o = 0; o < d.length; o += 16) {
            byte[] blk = x.clone();
            for (int i = 0; i < 16 && o + i < d.length; i++) blk[i] ^= d[o + i];
            x = aes.doFinal(blk);
        }
        return x;
    }

    private static byte[] xorStream(Cipher aes, byte[] ctr0, byte[] d) throws Exception {
        byte[] out = new byte[d.length];
        byte[] ctr = ctr0.clone();
        for (int o = 0, n = 1; o < d.length; o += 16, n++) {
            ctr[14] = (byte) (n >> 8);
            ctr[15] = (byte) n;
            byte[] ks = aes.doFinal(ctr);
            for (int i = 0; i < 16 && o + i < d.length; i++) out[o + i] = (byte) (d[o + i] ^ ks[i]);
        }
        return out;
    }

    // ---------------------------------------------------------------- bytes

    static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    static int le16(byte[] d, int o) { return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8; }
    static long le32(byte[] d, int o) { return le16(d, o) | (long) le16(d, o + 2) << 16; }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void log(String s) {
        try {
            Class.forName("android.util.Log").getMethod("w", String.class, String.class).invoke(null, "MatterBulb", s);
        } catch (Exception e) {
            System.err.println("MatterBulb: " + s);
        }
    }
}
