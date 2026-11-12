package com.shieldhotspot;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client (classic RSA auth, no TLS) used to talk to the device's own adbd
 * on 127.0.0.1:5555 ("Network debugging"). Lets the app run commands as the shell user.
 */
public final class AdbClient implements AutoCloseable {
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private AdbClient(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Connects and authenticates. On first use the TV shows "Allow USB debugging?" and the
     * user must accept (tick "Always allow"); authTimeoutMs bounds how long we wait for that.
     */
    public static AdbClient connect(File keyDir, String host, int port, int authTimeoutMs) throws Exception {
        KeyPair kp = loadOrCreateKey(keyDir);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(15000);
        AdbClient c = new AdbClient(s);
        try {
            c.send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = c.read();
                if (m.cmd == A_CNXN) return c;
                if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb msg " + Integer.toHexString(m.cmd));
                if (!sentSig) {
                    c.send(A_AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    c.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) kp.getPublic()));
                    sentKey = true;
                    s.setSoTimeout(authTimeoutMs); // waiting for the user to accept on screen
                } else {
                    throw new IOException("adb authorization rejected");
                }
            }
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    /** Runs a shell command and returns its combined output. */
    public String shell(String command, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        int local = nextLocalId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remote = m.arg0;
                buf.write(m.data);
                send(A_OKAY, local, remote, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                if (remote != 0) send(A_CLSE, local, remote, new byte[0]);
                break;
            }
        }
        return buf.toString("UTF-8");
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- wire format ----

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- keys ----

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SHA1_DIGEST_INFO);
        return c.doFinal(token);
    }

    /** Android's mincrypt RSAPublicKey struct, base64, plus a name, NUL-terminated. */
    private static byte[] publicKeyBlob(RSAPublicKey pub) {
        final int words = 2048 / 32;
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        BigInteger mask = r32.subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).and(mask).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).and(mask).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String s = android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP) + " shieldhotspot@shield\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static KeyPair loadOrCreateKey(File dir) throws Exception {
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int r;
            while ((r = is.read(b)) > 0) bo.write(b, 0, r);
            return bo.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
    }
}
