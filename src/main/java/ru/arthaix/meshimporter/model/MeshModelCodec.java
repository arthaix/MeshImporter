package ru.arthaix.meshimporter.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import ru.arthaix.meshimporter.common.MaterialSetup;

/**
 * The .mim file: a deflated stream with the materials, textures, vertex arrays and index ranges of a {@link MeshModel}.
 * The file's SHA-1 identifies the model everywhere (server store, client cache, packets).
 */
public final class MeshModelCodec {

    private static final int MAGIC = 0x4D494D31; // "MIM1"
    private static final int MAX_VERTICES = 100_000_000;

    private MeshModelCodec() {}

    public static byte[] encode(MeshModel m) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 16, m.estimatedBytes() / 2 + 1024));
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bos, new Deflater(Deflater.BEST_SPEED), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(m.materials.length);
            for (MeshModel.Material mat : m.materials) {
                out.writeUTF(mat.name);
                out.writeByte(mat.mode.ordinal());
                out.writeUTF(mat.blockId);
                out.writeInt(mat.color);
                out.writeByte(mat.glow ? 1 : 0);
                out.writeInt(mat.texture);
            }
            out.writeInt(m.textures.length);
            for (byte[] t : m.textures) {
                out.writeInt(t.length);
                out.write(t);
            }
            out.writeInt(m.vertexCount);
            writeFloats(out, m.positions, 3 * m.vertexCount);
            out.write(m.normals, 0, 3 * m.vertexCount);
            out.writeBoolean(m.uvs != null);
            if (m.uvs != null) writeFloats(out, m.uvs, 2 * m.vertexCount);
            out.writeInt(m.triangleCount);
            writeInts(out, m.indices, 3 * m.triangleCount);
            for (int i = 0; i < m.materials.length; i++) {
                out.writeInt(m.matStart[i]);
                out.writeInt(m.matCount[i]);
            }
            for (int i = 0; i < 6; i++) out.writeDouble(m.bounds[i]);
        }
        byte[] bytes = bos.toByteArray();
        m.hash = sha1(bytes);
        m.encodedBytes = bytes.length;
        return bytes;
    }

    public static MeshModel decode(byte[] bytes) throws IOException {
        MeshModel m = decode(new ByteArrayInputStream(bytes));
        m.hash = sha1(bytes);
        m.encodedBytes = bytes.length;
        return m;
    }

    public static MeshModel decode(InputStream raw) throws IOException {
        MeshModel m = new MeshModel();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(raw, new java.util.zip.Inflater(), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not a MeshImporter model file");
            int nm = in.readInt();
            if (nm < 0 || nm > 100_000) throw new IOException("corrupt model (materials)");
            m.materials = new MeshModel.Material[nm];
            for (int i = 0; i < nm; i++) {
                MeshModel.Material mat = new MeshModel.Material();
                mat.name = in.readUTF();
                mat.mode = MaterialSetup.Mode.fromOrdinal(in.readByte());
                mat.blockId = in.readUTF();
                mat.color = in.readInt();
                mat.glow = in.readByte() != 0;
                mat.texture = in.readInt();
                m.materials[i] = mat;
            }
            int nt = in.readInt();
            if (nt < 0 || nt > 100_000) throw new IOException("corrupt model (textures)");
            m.textures = new byte[nt][];
            for (int i = 0; i < nt; i++) {
                int len = in.readInt();
                if (len < 0 || len > (256 << 20)) throw new IOException("corrupt model (texture size)");
                m.textures[i] = new byte[len];
                in.readFully(m.textures[i]);
            }
            m.vertexCount = in.readInt();
            if (m.vertexCount < 0 || m.vertexCount > MAX_VERTICES) throw new IOException("corrupt model (vertices)");
            m.positions = readFloats(in, 3 * m.vertexCount);
            m.normals = new byte[3 * m.vertexCount];
            in.readFully(m.normals);
            if (in.readBoolean()) m.uvs = readFloats(in, 2 * m.vertexCount);
            m.triangleCount = in.readInt();
            if (m.triangleCount < 0 || m.triangleCount > MAX_VERTICES) throw new IOException("corrupt model (triangles)");
            m.indices = readInts(in, 3 * m.triangleCount);
            m.matStart = new int[nm];
            m.matCount = new int[nm];
            for (int i = 0; i < nm; i++) {
                m.matStart[i] = in.readInt();
                m.matCount[i] = in.readInt();
            }
            for (int i = 0; i < 6; i++) m.bounds[i] = in.readDouble();
            for (int i = 0; i < 3 * m.triangleCount; i++)
                if (m.indices[i] < 0 || m.indices[i] >= m.vertexCount) throw new IOException("corrupt model (index)");
        }
        return m;
    }

    private static void writeFloats(DataOutputStream out, float[] a, int n) throws IOException {
        byte[] buf = new byte[Math.min(n, 1 << 16) * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int i = 0;
        while (i < n) {
            int k = Math.min(n - i, 1 << 16);
            bb.clear();
            bb.asFloatBuffer().put(a, i, k);
            out.write(buf, 0, 4 * k);
            i += k;
        }
    }

    private static void writeInts(DataOutputStream out, int[] a, int n) throws IOException {
        byte[] buf = new byte[Math.min(n, 1 << 16) * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int i = 0;
        while (i < n) {
            int k = Math.min(n - i, 1 << 16);
            bb.clear();
            bb.asIntBuffer().put(a, i, k);
            out.write(buf, 0, 4 * k);
            i += k;
        }
    }

    private static float[] readFloats(DataInputStream in, int n) throws IOException {
        float[] a = new float[n];
        byte[] buf = new byte[Math.min(n, 1 << 16) * 4];
        int i = 0;
        while (i < n) {
            int k = Math.min(n - i, 1 << 16);
            in.readFully(buf, 0, 4 * k);
            ByteBuffer.wrap(buf, 0, 4 * k).asFloatBuffer().get(a, i, k);
            i += k;
        }
        return a;
    }

    private static int[] readInts(DataInputStream in, int n) throws IOException {
        int[] a = new int[n];
        byte[] buf = new byte[Math.min(n, 1 << 16) * 4];
        int i = 0;
        while (i < n) {
            int k = Math.min(n - i, 1 << 16);
            in.readFully(buf, 0, 4 * k);
            ByteBuffer.wrap(buf, 0, 4 * k).asIntBuffer().get(a, i, k);
            i += k;
        }
        return a;
    }

    public static String sha1(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean validHash(String s) {
        if (s == null || s.length() != 40) return false;
        for (int i = 0; i < 40; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
