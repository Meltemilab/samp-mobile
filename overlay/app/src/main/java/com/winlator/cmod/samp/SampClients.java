package com.winlator.cmod.samp;

import android.content.Context;

import com.winlator.cmod.core.FileUtils;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * SA-MP client versions.
 *
 * The version of a samp.dll is identified by its PE entry point, the same way
 * SA-MP plugins do it. A "client pack" is a copy of one version's client files
 * kept in app storage, so the user can switch the GTA folder between versions.
 */
public class SampClients {
    /** Files that make up a SA-MP client in the GTA folder. */
    public static final String[] CLIENT_FILES = {
        "samp.dll", "samp.exe", "samp.saa", "gtaweap3.ttf", "sampaux3.ttf",
        "sampgui.png", "rcon.exe", "samp_debug.exe", "bass.dll"
    };
    /** Folder with SA-MP models/maps; differs between versions (0.3.DL adds models). */
    public static final String CLIENT_DIR = "SAMP";

    public static String versionFromEntryPoint(long entry) {
        if (entry == 0x31DF13L) return "0.3.7-R1";
        if (entry == 0x3195DDL) return "0.3.7-R2";
        if (entry == 0xCC4D0L) return "0.3.7-R3";
        if (entry == 0xCBCB0L) return "0.3.7-R4";
        if (entry == 0xCBCD0L) return "0.3.7-R4-2";
        if (entry == 0xCBC90L) return "0.3.7-R5";
        if (entry == 0xFDB60L) return "0.3.DL-R1";
        return null;
    }

    /** Reads the PE entry point of a DLL, or -1. */
    public static long readEntryPoint(File dll) {
        if (!dll.isFile()) return -1;
        try (RandomAccessFile f = new RandomAccessFile(dll, "r")) {
            f.seek(0x3C);
            long peOffset = readU32(f);
            if (peOffset <= 0 || peOffset > f.length() - 0x40) return -1;
            f.seek(peOffset);
            if (readU32(f) != 0x4550) return -1; /* "PE\0\0" */
            f.seek(peOffset + 0x28);
            return readU32(f);
        }
        catch (Exception e) {
            return -1;
        }
    }

    private static long readU32(RandomAccessFile f) throws java.io.IOException {
        int b0 = f.read(), b1 = f.read(), b2 = f.read(), b3 = f.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new java.io.EOFException();
        return (b0 | (b1 << 8) | (b2 << 16) | ((long)b3 << 24)) & 0xFFFFFFFFL;
    }

    /** Human readable version of the client installed in a folder, or null if no samp.dll. */
    public static String detectVersion(File folder) {
        File dll = new File(folder, "samp.dll");
        if (!dll.isFile()) return null;
        long entry = readEntryPoint(dll);
        String v = versionFromEntryPoint(entry);
        if (v != null) return v;
        return "unknown-" + Long.toHexString(entry);
    }

    public static File getPacksDir(Context context) {
        File dir = new File(context.getFilesDir(), "samp_clients");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Versions saved as client packs. */
    public static ArrayList<String> listPacks(Context context) {
        ArrayList<String> list = new ArrayList<>();
        File[] dirs = getPacksDir(context).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (new File(d, "samp.dll").isFile()) list.add(d.getName());
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Copies the client files from a folder (a GTA folder or an extracted client)
     * into a pack named after the detected version. Returns the version or null.
     */
    public static String importPack(Context context, File source) {
        String version = detectVersion(source);
        if (version == null) return null;
        File dst = new File(getPacksDir(context), version);
        FileUtils.delete(dst);
        dst.mkdirs();
        for (String name : CLIENT_FILES) {
            File f = findCaseInsensitive(source, name);
            if (f != null && f.isFile()) FileUtils.copy(f, new File(dst, name));
        }
        File dir = findCaseInsensitive(source, CLIENT_DIR);
        if (dir != null && dir.isDirectory()) FileUtils.copy(dir, new File(dst, CLIENT_DIR));
        return new File(dst, "samp.dll").isFile() ? version : null;
    }

    /**
     * Installs a pack into the GTA folder. The client currently in the folder is
     * saved as a pack first (if it isn't saved yet), so nothing is lost.
     */
    public static boolean installPack(Context context, String version, File gtaDir) {
        File pack = new File(getPacksDir(context), version);
        if (!new File(pack, "samp.dll").isFile()) return false;

        String current = detectVersion(gtaDir);
        if (current != null && !current.equals(version) && !listPacks(context).contains(current)) {
            importPack(context, gtaDir);
        }

        for (String name : CLIENT_FILES) {
            File src = new File(pack, name);
            if (!src.isFile()) continue;
            File existing = findCaseInsensitive(gtaDir, name);
            File dst = existing != null ? existing : new File(gtaDir, name);
            if (!FileUtils.copy(src, dst)) return false;
        }
        File srcDir = new File(pack, CLIENT_DIR);
        if (srcDir.isDirectory()) {
            File existing = findCaseInsensitive(gtaDir, CLIENT_DIR);
            File dstDir = existing != null ? existing : new File(gtaDir, CLIENT_DIR);
            String[] names = srcDir.list();
            if (names != null) {
                for (String n : names) {
                    File e = findCaseInsensitive(dstDir, n);
                    if (!FileUtils.copy(new File(srcDir, n), e != null ? e : new File(dstDir, n))) return false;
                }
            }
        }
        return version.equals(detectVersion(gtaDir));
    }

    public static boolean removePack(Context context, String version) {
        return FileUtils.delete(new File(getPacksDir(context), version));
    }

    /** Windows is case-insensitive, Android isn't: find "SAMP" vs "samp" etc. */
    public static File findCaseInsensitive(File dir, String name) {
        File exact = new File(dir, name);
        if (exact.exists()) return exact;
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) return new File(dir, n);
        }
        return null;
    }

    public static boolean isGtaFolder(File dir) {
        return dir != null && findCaseInsensitive(dir, "gta_sa.exe") != null;
    }

    public static ArrayList<String> knownVersions() {
        return new ArrayList<>(Arrays.asList("0.3.7-R1", "0.3.7-R2", "0.3.7-R3", "0.3.7-R4", "0.3.7-R4-2", "0.3.7-R5", "0.3.DL-R1"));
    }
}
