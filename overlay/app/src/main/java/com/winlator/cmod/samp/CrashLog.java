package com.winlator.cmod.samp;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Crash logs for SA-MP Mobile.
 *
 * Three kinds of log files are written to Download/SA-MP-Mobile/logs:
 *   app-crash-*.txt  a Java crash of the app (stack trace + the app's logcat)
 *   app-exit-*.txt   a crash Android reported for the previous run (native crash, ANR, killed...)
 *   game-*.txt       one per Connect: phone/GPU, container settings, the GTA folder,
 *                    then all Wine / Box86 / Box64 / DXVK output, and how the game ended
 */
public class CrashLog {
    private static final String PREFS = "samp_crashlog";
    private static final int MAX_FILES = 25;
    private static final long MAX_SESSION_BYTES = 24L * 1024 * 1024;
    private static boolean handlerInstalled = false;

    /** Lines worth showing at the top of a shared log. */
    private static final String[] KEY_PATTERNS = {
        "unhandled", "page fault", "exception", "sigsegv", "sigbus", "sigill", "segfault", "segv",
        "backtrace", "crash", "fatal", "abort", "err:", "error", "failed", "not found", "could not",
        "cannot", "unimplemented", "missing", "dxvk: ", "vulkan", "adreno", "turnip", "virgl",
        "samp", "gta_sa", "exit status", "window mapped"
    };

    public static File getLogsDir(Context context) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SA-MP-Mobile/logs");
        if (!dir.isDirectory()) dir.mkdirs();
        if (dir.isDirectory() && dir.canWrite()) return dir;
        File fallback = new File(context.getExternalFilesDir(null), "logs");
        if (!fallback.isDirectory()) fallback.mkdirs();
        return fallback;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** All log files, newest first. */
    public static ArrayList<File> listLogs(Context context) {
        ArrayList<File> list = new ArrayList<>();
        File[] files = getLogsDir(context).listFiles((dir, name) -> name.endsWith(".txt"));
        if (files != null) list.addAll(Arrays.asList(files));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    private static void prune(Context context) {
        ArrayList<File> list = listLogs(context);
        for (int i = MAX_FILES; i < list.size(); i++) list.get(i).delete();
    }

    /* ------------------------------------------------------------ device */

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        }
        catch (Exception e) {
            return "";
        }
    }

    private static String cpuHardware() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Hardware")) return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        catch (Exception e) {}
        return "";
    }

    /** "Adreno825", "Adreno740v2"..., or null when this isn't (or can't tell it's) an Adreno. */
    public static String gpuModel() {
        String model = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_model");
        return model.isEmpty() ? null : model;
    }

    /** Adreno GPUs expose /sys/class/kgsl; Winlator 7.1's Turnip supports Adreno 6xx/7xx. */
    public static String gpuInfo() {
        String model = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_model");
        if (!model.isEmpty()) {
            return model + " (Adreno - Turnip through Adrenotools)";
        }
        if (new File("/sys/class/kgsl").exists()) return "Adreno (model unreadable)";
        String other = readFirstLine("/sys/kernel/gpu/gpu_model");
        if (!other.isEmpty()) return other + " (not Adreno - Turnip won't work, pick another driver in Advanced settings)";
        return "unknown, no /sys/class/kgsl (probably not Adreno)";
    }

    public static String deviceInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        String appVersion = "?";
        try { appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception e) {}
        sb.append("App: SA-MP Mobile (Winlator Ludashi ").append(appVersion).append(")\n");
        sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Chip: hardware=").append(Build.HARDWARE).append(" board=").append(Build.BOARD);
        String cpu = cpuHardware();
        if (!cpu.isEmpty()) sb.append(" cpuinfo=").append(cpu);
        sb.append("\nABIs: ").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("GPU: ").append(gpuInfo()).append('\n');
        ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append("RAM: ").append(mi.totalMem / (1024 * 1024)).append(" MB total, ")
              .append(mi.availMem / (1024 * 1024)).append(" MB free").append(mi.lowMemory ? " (LOW)" : "").append('\n');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------- Java crashes */

    /** Saves a log when the app itself crashes (any thread), then lets Android handle it as usual. */
    public static synchronized void install(Context context) {
        if (handlerInstalled) return;
        handlerInstalled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                StringBuilder sb = new StringBuilder();
                sb.append("=== SA-MP Mobile: the app crashed ===\n");
                sb.append("Time: ").append(now()).append('\n');
                sb.append("Thread: ").append(thread.getName()).append('\n');
                sb.append(deviceInfo(app)).append('\n');
                sb.append("--- Stack trace ---\n").append(trace).append('\n');
                sb.append("--- App logcat ---\n").append(appLogcat());
                File f = new File(getLogsDir(app), "app-crash-" + stamp() + ".txt");
                writeFile(f, sb.toString());
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("pending_notice", f.getPath()).commit();
            }
            catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
            else System.exit(2);
        });
    }

    private static String appLogcat() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", "1500"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(3, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            sb.append("(logcat unavailable: ").append(e).append(")\n");
        }
        return sb.toString();
    }

    /* ---------------------------------------- crashes Android reports later */

    /**
     * Native crashes, ANRs and kills can't be caught in Java. Android 11+ remembers them,
     * so on the next start we save any we haven't saved yet. Returns the newest file written.
     */
    public static File recordPreviousExits(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        File newest = null;
        try {
            ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long lastSeen = prefs.getLong("last_exit_ts", 0);
            long maxSeen = lastSeen;
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 10);
            for (ApplicationExitInfo info : exits) {
                if (info.getTimestamp() <= lastSeen) continue;
                maxSeen = Math.max(maxSeen, info.getTimestamp());
                if (!isProblem(info)) continue;

                StringBuilder sb = new StringBuilder();
                sb.append("=== SA-MP Mobile: the app stopped unexpectedly ===\n");
                sb.append("When: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(info.getTimestamp()))).append('\n');
                sb.append("Reason: ").append(reasonName(info.getReason())).append('\n');
                sb.append("Status/signal: ").append(info.getStatus()).append('\n');
                sb.append("Process: ").append(info.getProcessName()).append('\n');
                sb.append("Description: ").append(info.getDescription()).append('\n');
                sb.append("Memory at exit: pss=").append(info.getPss() / 1024).append(" MB rss=").append(info.getRss() / 1024).append(" MB\n");
                sb.append(deviceInfo(context)).append('\n');
                try (InputStream in = info.getTraceInputStream()) {
                    if (in != null) {
                        sb.append("--- Trace ---\n");
                        BufferedReader r = new BufferedReader(new InputStreamReader(in));
                        String line;
                        int n = 0;
                        while ((line = r.readLine()) != null && n++ < 4000) sb.append(line).append('\n');
                    }
                }
                catch (Exception e) {}

                File f = new File(getLogsDir(context), "app-exit-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(info.getTimestamp())) + ".txt");
                writeFile(f, sb.toString());
                if (newest == null) newest = f;
            }
            prefs.edit().putLong("last_exit_ts", maxSeen).apply();
            prune(context);
        }
        catch (Exception e) {}
        return newest;
    }

    private static boolean isProblem(ApplicationExitInfo info) {
        switch (info.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return true;
            case ApplicationExitInfo.REASON_SIGNALED:
                return info.getStatus() != 9; /* 9 = SIGKILL, normal when Android or the user closes the app */
            default:
                return false;
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Java)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE (native code)";
            case ApplicationExitInfo.REASON_ANR: return "ANR (app not responding)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (killed by Android for memory)";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            default: return String.valueOf(reason);
        }
    }

    /* ------------------------------------------------------- game session */

    /**
     * Started by the launcher before each Connect: writes the setup snapshot.
     * Returns the file; the game screen keeps appending to it.
     */
    public static File startSession(Context context, Container container, File gta, SampConfig.Server server, String nick) {
        prune(context);
        File f = new File(getLogsDir(context), "game-" + stamp() + ".txt");
        StringBuilder sb = new StringBuilder();
        sb.append("=== SA-MP Mobile game log ===\n");
        sb.append("Started: ").append(now()).append('\n');
        sb.append(deviceInfo(context)).append('\n');

        sb.append("--- Container ---\n");
        sb.append("Container default graphics driver: ").append(container.getGraphicsDriver()).append('\n');
        sb.append("Container default DX wrapper: ").append(container.getDXWrapper()).append('\n');
        sb.append("Screen: ").append(container.getScreenSize()).append('\n');
        sb.append("Wine: ").append(container.getWineVersion()).append('\n');
        sb.append("Emulator: ").append(container.getEmulator()).append(" (FEXCore ").append(container.getFEXCoreVersion()).append(" ").append(container.getFEXCorePreset())
          .append(", Box64 ").append(container.getBox64Version()).append(" ").append(container.getBox64Preset()).append(")\n");
        sb.append("Graphics driver config: ").append(container.getGraphicsDriverConfig()).append('\n');
        sb.append("DX wrapper config: ").append(container.getDXWrapperConfig()).append('\n');
        sb.append("Env: ").append(container.getEnvVars()).append('\n');
        sb.append("Win components: ").append(container.getWinComponents()).append('\n');
        sb.append('\n');

        sb.append("(the graphics actually used are on the \"Game screen started\" line below)\n\n");
        sb.append("--- SA-MP ---\n");
        sb.append("Server: ").append(server.name).append(" ").append(server.address()).append('\n');
        sb.append("Nickname length: ").append(nick.length()).append('\n');
        sb.append("SA-MP client: ").append(SampClients.detectVersion(gta)).append('\n');
        File exe = SampClients.findCaseInsensitive(gta, "gta_sa.exe");
        if (exe != null) {
            long size = exe.length();
            sb.append("gta_sa.exe: ").append(size).append(" bytes, ").append(describeExe(size)).append('\n');
        }
        sb.append('\n');

        sb.append("--- GTA folder: ").append(gta.getPath()).append(" ---\n");
        sb.append(listFolder(gta, 0));
        File samp = SampClients.findCaseInsensitive(gta, "SAMP");
        if (samp != null && samp.isDirectory()) sb.append("[SAMP folder] ").append(countFiles(samp)).append(" files\n");
        File userFiles = SampClients.findCaseInsensitive(gta, "GTA San Andreas User Files");
        if (userFiles != null) sb.append("[User Files folder present]\n");
        sb.append('\n');
        sb.append("--- Wine / Box86 / Box64 / DXVK output ---\n");

        writeFile(f, sb.toString());
        return f;
    }

    private static String describeExe(long size) {
        if (size == 14383616L) return "looks like v1.0 US (OK for SA-MP)";
        if (size == 14405632L) return "looks like v1.0 US no-CD (OK for SA-MP)";
        if (size > 13_000_000L && size < 16_000_000L) return "a v1.0-sized exe, but not the standard one; may or may not work";
        return "NOT a v1.0 exe (Steam/RGL/v1.01/v3.0 are not supported by SA-MP; use a v1.0 US gta_sa.exe)";
    }

    private static int countFiles(File dir) {
        String[] names = dir.list();
        return names != null ? names.length : 0;
    }

    /** Top-level files with sizes, plus one level into folders that usually hold mods. */
    private static String listFolder(File dir, int depth) {
        StringBuilder sb = new StringBuilder();
        File[] files = dir.listFiles();
        if (files == null) return "(cannot read folder - storage permission?)\n";
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int shown = 0;
        for (File f : files) {
            if (shown++ >= 150) {
                sb.append("... (").append(files.length - 150).append(" more)\n");
                break;
            }
            String indent = depth > 0 ? "    " : "";
            if (f.isDirectory()) {
                sb.append(indent).append("[dir] ").append(f.getName()).append(" (").append(countFiles(f)).append(" entries)\n");
                String n = f.getName().toLowerCase(Locale.ENGLISH);
                if (depth == 0 && (n.equals("cleo") || n.equals("modloader") || n.equals("scripts") || n.equals("plugins"))) {
                    sb.append(listFolder(f, 1));
                }
            }
            else sb.append(indent).append(f.getName()).append("  ").append(f.length()).append('\n');
        }
        return sb.toString();
    }

    /** Collects all output of the Wine processes into the session log. */
    public static class Session implements Callback<String> {
        private final Context context;
        private final File file;
        private BufferedWriter writer;
        private long written = 0;
        private long lastFlush = 0;
        private boolean truncated = false;

        public Session(Context context, File file) {
            this.context = context.getApplicationContext();
            this.file = file;
            try {
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
                written = file.length();
            }
            catch (IOException e) {
                writer = null;
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("running_session", file.getPath()).commit();
        }

        public File getFile() {
            return file;
        }

        @Override
        public synchronized void call(String line) {
            if (writer == null) return;
            try {
                if (written > MAX_SESSION_BYTES) {
                    if (!truncated) {
                        writer.write("\n[log too long, further output dropped]\n");
                        writer.flush();
                        truncated = true;
                    }
                    return;
                }
                writer.write(line);
                writer.write('\n');
                written += line.length() + 1;
                long t = System.currentTimeMillis();
                if (t - lastFlush > 500) {
                    writer.flush();
                    lastFlush = t;
                }
            }
            catch (IOException e) {}
        }

        public synchronized void note(String text) {
            call("[SA-MP Mobile " + now() + "] " + text);
            flush();
        }

        public synchronized void flush() {
            try { if (writer != null) writer.flush(); }
            catch (IOException e) {}
        }

        /** Called when Wine exits; the launcher shows this log next time it opens. */
        private File gtaDir, containerRoot;

        /** Where the game's own logs are, so they can be added when the game ends. */
        public void setGameDirs(File gtaDir, File containerRoot) {
            this.gtaDir = gtaDir;
            this.containerRoot = containerRoot;
        }

        /**
         * SA-MP and mods write why they stopped into their own files (SA-MP's chat log
         * and crash info, SAMPFUNCS, modloader, CLEO). Append the end of each one.
         */
        private void appendGameLogs() {
            ArrayList<File> files = new ArrayList<>();
            if (containerRoot != null) {
                File userFiles = new File(containerRoot, ".wine/drive_c/users/" + com.winlator.cmod.xenvironment.ImageFs.USER + "/Documents/GTA San Andreas User Files");
                File sampDir = SampClients.findCaseInsensitive(userFiles, "SAMP");
                if (sampDir != null) {
                    for (String n : new String[]{"crashinfo.txt", "chatlog.txt", "samp.log"}) {
                        File f = SampClients.findCaseInsensitive(sampDir, n);
                        if (f != null) files.add(f);
                    }
                }
            }
            if (gtaDir != null) {
                String[][] paths = {{"SAMPFUNCS", "SAMPFUNCS.log"}, {"modloader", "modloader.log"}, {null, "cleo.log"}, {null, "omp-client.log"}};
                for (String[] p : paths) {
                    File dir = p[0] != null ? SampClients.findCaseInsensitive(gtaDir, p[0]) : gtaDir;
                    if (dir == null) continue;
                    File f = SampClients.findCaseInsensitive(dir, p[1]);
                    if (f != null) files.add(f);
                }
            }
            for (File f : files) {
                if (!f.isFile()) continue;
                call("");
                call("--- " + f.getPath() + " (" + f.length() + " bytes, changed " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(f.lastModified())) + ") ---");
                String text = readHeadTail(f, 0, 6 * 1024);
                for (String line : text.split("\n")) call(line);
            }
        }

        public synchronized void end(int status) {
            if (writer == null) return;
            note("Wine exited, exit status " + status);
            note("Game and mod logs:");
            appendGameLogs();
            flush();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("running_session")
                .putString("pending_notice", file.getPath())
                .putInt("pending_status", status)
                .commit();
            close();
        }

        /** The user left the game from the menu: not a crash, so no notice. */
        public synchronized void closedByUser() {
            if (writer == null) return;
            note("Game closed from the menu");
            note("Game and mod logs:");
            appendGameLogs();
            flush();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("running_session").commit();
            close();
        }

        public synchronized void close() {
            try { if (writer != null) writer.close(); }
            catch (IOException e) {}
            writer = null;
        }
    }

    /* --------------------------------------------- notices for the launcher */

    /**
     * A log the user should look at now: after a crash, or after the game closed.
     * Also turns a session that never ended (the app died mid-game) into a notice.
     */
    public static File takePendingNotice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String running = prefs.getString("running_session", null);
        if (running != null) {
            appendLine(new File(running), "[SA-MP Mobile " + now() + "] The app stopped while the game was running (no clean exit).");
            prefs.edit().remove("running_session").putString("pending_notice", running).commit();
        }
        String path = prefs.getString("pending_notice", null);
        if (path == null) return null;
        prefs.edit().remove("pending_notice").apply();
        File f = new File(path);
        return f.isFile() ? f : null;
    }

    /* ------------------------------------------------------ reading/sharing */

    /** Key lines first, then the log (start + end if it is big): small enough to share as text. */
    public static String forSharing(File file) {
        String all = readHeadTail(file, 48 * 1024, 140 * 1024);
        StringBuilder key = new StringBuilder();
        int count = 0;
        boolean inOutput = false;
        for (String line : all.split("\n")) {
            if (line.startsWith("--- Wine / Box86")) { inOutput = true; continue; }
            if (!inOutput) continue;
            String l = line.toLowerCase(Locale.ENGLISH);
            for (String p : KEY_PATTERNS) {
                if (l.contains(p)) {
                    key.append(line.length() > 300 ? line.substring(0, 300) : line).append('\n');
                    count++;
                    break;
                }
            }
            if (count >= 80) break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(file.getName()).append(" (").append(file.length()).append(" bytes)\n");
        if (count > 0) sb.append("\n=== Key lines ===\n").append(key).append('\n');
        sb.append("=== Log ===\n").append(all);
        return sb.toString();
    }

    public static String readHeadTail(File file, int head, int tail) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            if (len <= head + tail) {
                byte[] b = new byte[(int)len];
                f.readFully(b);
                return new String(b, StandardCharsets.UTF_8);
            }
            byte[] h = new byte[head];
            f.readFully(h);
            byte[] t = new byte[tail];
            f.seek(len - tail);
            f.readFully(t);
            return new String(h, StandardCharsets.UTF_8) + "\n\n[... " + (len - head - tail) + " bytes skipped ...]\n\n" + new String(t, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "(cannot read " + file + ": " + e + ")";
        }
    }

    private static void writeFile(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {}
    }

    private static void appendLine(File f, String line) {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {}
    }
}
