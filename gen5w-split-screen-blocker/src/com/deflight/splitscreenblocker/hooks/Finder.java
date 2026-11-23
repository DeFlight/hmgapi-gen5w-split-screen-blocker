package com.deflight.splitscreenblocker.hooks;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the split-screen app's methods by what they do, so no list of software builds is needed.
 * The app's code names (k.r, bh, f) are scrambled differently in each build, but each
 * method still holds log text that no other method in its class has. This reads the app's
 * dex (inside its odex, or the apk) and picks, in the class SplitScreenHome is built on
 * (the one whose parent is android.app.Service), the one method with the right shape that
 * holds that text.
 */
final class Finder {
    static final String HOME = "Lcom/mobis/splitscreen/right/SplitScreenHome;";
    static final String SERVICE = "Landroid/app/Service;";

    /** what was found: class name (dots) and method names; null where nothing matched exactly once */
    static final class Found {
        String base, open, tab;
        String why = "";
    }

    final byte[] b;
    final int dex;
    int strSize, strOff, typeOff, protoOff, methodOff, classSize, classOff;

    Finder(byte[] file) {
        b = file;
        dex = (b[0] == 'd' && b[1] == 'e' && b[2] == 'y') ? h4(8) : 0;   // odex: the dex starts here
        strSize = u4(0x38); strOff = u4(0x3c);
        typeOff = u4(0x44); protoOff = u4(0x4c);
        methodOff = u4(0x5c);
        classSize = u4(0x60); classOff = u4(0x64);
    }

    static Found find(byte[] file) {
        return new Finder(file).run();
    }

    Found run() {
        Found f = new Found();
        int home = classDef(HOME);
        if (home < 0) { f.why = "no SplitScreenHome"; return f; }
        // up from SplitScreenHome to the class whose parent is Service
        int c = home;
        for (int i = 0; i < 8 && c >= 0; i++) {
            String sup = typeName(u4(classOff + c * 32 + 8));
            if (SERVICE.equals(sup)) break;
            c = classDef(sup);
        }
        if (c < 0) { f.why = "no base class"; return f; }
        String d = typeName(u4(classOff + c * 32));
        f.base = d.substring(1, d.length() - 1).replace('/', '.');
        f.open = method(c, "VZ", "setSplitSeenShow mbToggle : ");
        f.tab = method(c, "ZZZ", "setButtonShow isOn = ");
        return f;
    }

    /** the name of the one method of class def c with this shorty that loads string s; null if none or several */
    String method(int c, String shorty, String s) {
        int sid = stringId(s);
        if (sid < 0) return null;
        int data = u4(classOff + c * 32 + 24);
        if (data == 0) return null;
        int[] p = {data};
        int sf = uleb(p), inf = uleb(p), dm = uleb(p), vm = uleb(p);
        for (int i = 0; i < sf + inf; i++) { uleb(p); uleb(p); }
        String hit = null;
        int idx = 0;
        for (int i = 0; i < dm + vm; i++) {
            if (i == dm) idx = 0;
            idx += uleb(p);
            uleb(p);
            int code = uleb(p);
            if (code == 0) continue;
            int mo = methodOff + idx * 8;
            if (!shorty.equals(string(u4(protoOff + u2(mo + 2) * 12)))) continue;
            if (!loads(code, sid)) continue;
            if (hit != null) return null;   // two matches: not sure which, leave it
            hit = string(u4(mo + 4));
        }
        return hit;
    }

    /** does the code at off have a const-string (or /jumbo) of string id sid */
    boolean loads(int off, int sid) {
        int n = u4(off + 12);
        int at = off + 16;
        for (int i = 0; i < n; i++) {
            int op = b[dex + at + i * 2] & 0xff;
            if (op == 0x1a && i + 1 < n && u2(at + i * 2 + 2) == sid) return true;
            if (op == 0x1b && i + 2 < n && u4(at + i * 2 + 2) == sid) return true;
        }
        return false;
    }

    int classDef(String descriptor) {
        if (descriptor == null) return -1;
        for (int i = 0; i < classSize; i++)
            if (descriptor.equals(typeName(u4(classOff + i * 32)))) return i;
        return -1;
    }

    String typeName(int t) {
        if (t < 0) return null;
        return string(u4(typeOff + t * 4));
    }

    int stringId(String s) {
        for (int i = 0; i < strSize; i++) if (s.equals(string(i))) return i;
        return -1;
    }

    String string(int id) {
        int[] p = {u4(strOff + id * 4)};
        int len = uleb(p);
        StringBuilder sb = new StringBuilder(len);
        for (int i = dex + p[0]; b[i] != 0; i++) sb.append((char) (b[i] & 0xff));
        return sb.toString();
    }

    int uleb(int[] p) {
        int r = 0, s = 0, x;
        do { x = b[dex + p[0]++] & 0xff; r |= (x & 0x7f) << s; s += 7; } while ((x & 0x80) != 0);
        return r;
    }

    /** dex-relative reads */
    int u2(int o) { o += dex; return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    int u4(int o) { return h4(dex + o); }

    /** file-relative read */
    int h4(int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24; }

    /** the app's dex: its odex next to the apk, else classes.dex inside the apk */
    static byte[] load(String apk) throws Exception {
        java.io.File odex = new java.io.File(apk.replaceAll("\\.apk$", ".odex"));
        if (odex.isFile()) return read(new FileInputStream(odex));
        ZipFile z = new ZipFile(apk);
        try {
            ZipEntry e = z.getEntry("classes.dex");
            return e == null ? null : read(z.getInputStream(e));
        } finally {
            z.close();
        }
    }

    static byte[] read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
            byte[] x = new byte[65536];
            for (int n; (n = in.read(x)) > 0; ) o.write(x, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }
}
