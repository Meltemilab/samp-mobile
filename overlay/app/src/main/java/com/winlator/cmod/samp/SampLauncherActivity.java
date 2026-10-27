package com.winlator.cmod.samp;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.OnboardingActivity;
import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.OpenGLDriverDefaults;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCorePreset;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

/**
 * SA-MP Mobile home screen: pick the GTA folder, nickname, client version and a
 * server, then connect. The desktop gta_sa.exe runs through Winlator's
 * Wine + Box86/Box64 environment in a container made for SA-MP.
 */
public class SampLauncherActivity extends AppCompatActivity {
    private static final int REQUEST_GTA_FOLDER = 101;
    private static final int REQUEST_CLIENT_FOLDER = 102;
    private static final int REQUEST_PERMISSIONS = 103;
    public static final int SAMP_CONTROLS_PROFILE_ID = 50;
    private static final String SAMP_CONTROLS_ASSET = "inputcontrols/profiles/controls-50.icp";
    /** Built-in starter (tools/sampinject): boots gta_sa.exe and loads samp.dll, no launcher involved. */
    private static final String SAMP_INJECT_ASSET = "samp/sampinject.exe";

    private static final int BG = Color.rgb(18, 20, 24);
    private static final int CARD = Color.rgb(30, 34, 40);
    private static final int ACCENT = Color.rgb(232, 163, 61);
    private static final int TEXT = Color.rgb(235, 235, 235);
    private static final int DIM = Color.rgb(150, 155, 162);

    private SampConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText etGtaPath;
    private EditText etNickname;
    private TextView tvSystem;
    private TextView tvClient;
    private LinearLayout serverList;
    private ProgressDialog progress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        config = new SampConfig(this);
        buildUI();
        requestStoragePermissions();
        handler.postDelayed(this::checkCrashNotices, 600);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshServers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFields();
    }

    /* ------------------------------------------------------------------ UI */

    private int dp(float v) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.rgb(25, 18, 5) : TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 18 : 14);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(primary ? ACCENT : Color.rgb(48, 54, 62), 8));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setOnClickListener(l);
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TEXT);
        e.setSingleLine(true);
        e.setBackground(rounded(Color.rgb(22, 25, 30), 6));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        return e;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 12));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        TextView t = text(title, 13, ACCENT, true);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        return c;
    }

    private void addSpaced(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addWeighted(LinearLayout row, View v, float weight, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = dp(leftMarginDp);
        row.addView(v, lp);
    }

    private void buildUI() {
        getWindow().getDecorView().setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        root.addView(text("SA-MP Mobile", 26, TEXT, true));
        TextView sub = text("Desktop GTA San Andreas + SA-MP, running through Wine", 13, DIM, false);
        root.addView(sub);

        /* Game */
        LinearLayout game = card(root, "GAME");
        game.addView(text("GTA San Andreas folder (must contain gta_sa.exe and samp.dll)", 12, DIM, false));
        LinearLayout pathRow = row();
        etGtaPath = input("/storage/emulated/0/GTA San Andreas");
        etGtaPath.setText(config.getGtaPath());
        addWeighted(pathRow, etGtaPath, 1, 0);
        addWeighted(pathRow, button("Browse", false, v -> pickFolder(REQUEST_GTA_FOLDER)), 0.35f, 8);
        addSpaced(game, pathRow, 6);
        tvClient = text("", 14, TEXT, false);
        addSpaced(game, tvClient, 10);
        LinearLayout clientRow = row();
        addWeighted(clientRow, button("Change client version", false, v -> showVersionDialog()), 1, 0);
        addWeighted(clientRow, button("Import client files", false, v -> pickFolder(REQUEST_CLIENT_FOLDER)), 1, 8);
        addSpaced(game, clientRow, 8);
        TextView ompNote = text("open.mp servers accept the 0.3.7 and 0.3.DL clients, so connect to them like any other server.", 12, DIM, false);
        addSpaced(game, ompNote, 8);

        /* Player */
        LinearLayout player = card(root, "PLAYER");
        etNickname = input("Nickname (e.g. Carl_Johnson)");
        etNickname.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        etNickname.setText(config.getNickname());
        player.addView(etNickname);

        /* Servers */
        LinearLayout servers = card(root, "SERVERS");
        serverList = new LinearLayout(this);
        serverList.setOrientation(LinearLayout.VERTICAL);
        servers.addView(serverList);
        addSpaced(servers, button("+ Add server", false, v -> showServerDialog(-1)), 8);

        /* System */
        LinearLayout system = card(root, "SYSTEM");
        tvSystem = text("", 13, TEXT, false);
        system.addView(tvSystem);
        LinearLayout systemRow = row();
        addWeighted(systemRow, button("Crash logs", false, v -> showLogsDialog()), 1, 0);
        addWeighted(systemRow, button("Advanced settings (Winlator)", false, v -> startActivity(new Intent(this, MainActivity.class))), 1.4f, 8);
        addSpaced(system, systemRow, 8);

        Button connect = button("CONNECT", true, v -> connect());
        addSpaced(root, connect, 16);

        setContentView(scroll);
    }

    private void refreshStatus() {
        boolean installed = systemReady();
        tvSystem.setText(installed ? "Windows system: installed. Graphics: " + CrashLog.gpuInfo()
                                   : "Windows system: not installed yet. It installs once, the first time you connect (a few minutes).");
        tvSystem.setTextColor(installed ? TEXT : ACCENT);

        File gta = new File(etGtaPath.getText().toString().trim());
        String clientText;
        if (etGtaPath.getText().toString().trim().isEmpty()) clientText = "No GTA folder selected";
        else if (!SampClients.isGtaFolder(gta)) clientText = "gta_sa.exe not found in this folder";
        else {
            String v = SampClients.detectVersion(gta);
            clientText = v == null ? "SA-MP client: not installed in this folder" : "SA-MP client: " + v;
        }
        tvClient.setText(clientText);
    }

    private void refreshServers() {
        serverList.removeAllViews();
        ArrayList<SampConfig.Server> servers = config.getServers();
        int selected = config.getSelectedServer();
        if (servers.isEmpty()) {
            serverList.addView(text("No servers yet. Add one with its IP and port.", 13, DIM, false));
            return;
        }
        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            SampConfig.Server s = servers.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            boolean isSel = i == selected;
            GradientDrawable bg = rounded(isSel ? Color.rgb(58, 46, 26) : Color.rgb(38, 43, 50), 8);
            if (isSel) bg.setStroke(dp(2), ACCENT);
            item.setBackground(bg);
            item.addView(text(s.name.isEmpty() ? s.address() : s.name, 15, TEXT, true));
            item.addView(text(s.address(), 12, DIM, false));
            item.setOnClickListener(v -> {
                config.setSelectedServer(index);
                refreshServers();
            });
            item.setOnLongClickListener(v -> {
                showServerDialog(index);
                return true;
            });
            addSpaced(serverList, item, i == 0 ? 0 : 6);
        }
        TextView hint = text("Tap to select, hold to edit or delete.", 11, DIM, false);
        addSpaced(serverList, hint, 6);
    }

    private void saveFields() {
        if (etGtaPath != null) config.setGtaPath(etGtaPath.getText().toString().trim());
        if (etNickname != null) config.setNickname(etNickname.getText().toString().trim());
    }

    /* ------------------------------------------------------------ dialogs */

    private void showServerDialog(final int index) {
        final ArrayList<SampConfig.Server> servers = config.getServers();
        final SampConfig.Server existing = index >= 0 && index < servers.size() ? servers.get(index) : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(4));
        final EditText etName = new EditText(this);
        etName.setHint("Name (optional)");
        final EditText etHost = new EditText(this);
        etHost.setHint("IP or hostname (you can paste ip:port)");
        etHost.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText etPort = new EditText(this);
        etPort.setHint("Port");
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setText("7777");
        if (existing != null) {
            etName.setText(existing.name);
            etHost.setText(existing.host);
            etPort.setText(String.valueOf(existing.port));
        }
        box.addView(etName);
        box.addView(etHost);
        box.addView(etPort);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(existing == null ? "Add server" : "Edit server")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String host = etHost.getText().toString().trim();
                int port = 7777;
                try { port = Integer.parseInt(etPort.getText().toString().trim()); }
                catch (NumberFormatException e) {}
                host = host.replaceFirst("^samp://", "");
                int colon = host.lastIndexOf(':');
                if (colon > 0) {
                    try { port = Integer.parseInt(host.substring(colon + 1)); host = host.substring(0, colon); }
                    catch (NumberFormatException e) {}
                }
                if (host.isEmpty() || port <= 0 || port > 65535) {
                    toast("Enter a valid IP and port");
                    return;
                }
                SampConfig.Server s = new SampConfig.Server(etName.getText().toString().trim(), host, port);
                if (existing != null) servers.set(index, s);
                else {
                    servers.add(s);
                    config.setSelectedServer(servers.size() - 1);
                }
                config.setServers(servers);
                refreshServers();
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) {
            b.setNeutralButton("Delete", (d, w) -> {
                servers.remove(index);
                config.setServers(servers);
                int sel = config.getSelectedServer();
                if (sel == index) config.setSelectedServer(-1);
                else if (sel > index) config.setSelectedServer(sel - 1);
                refreshServers();
            });
        }
        b.show();
    }

    private void showVersionDialog() {
        saveFields();
        final File gta = new File(config.getGtaPath());
        if (!SampClients.isGtaFolder(gta)) {
            toast("Choose your GTA San Andreas folder first");
            return;
        }
        final String current = SampClients.detectVersion(gta);
        final ArrayList<String> packs = SampClients.listPacks(this);
        if (current != null && !packs.contains(current)) packs.add(0, current);
        if (packs.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("No client versions saved")
                .setMessage("Use \"Import client files\" and pick a folder that contains another SA-MP version (samp.dll, samp.exe, the SAMP folder...). Each version you import shows up here.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        String[] items = new String[packs.size()];
        for (int i = 0; i < packs.size(); i++) {
            String p = packs.get(i);
            items[i] = p + (p.equals(current) ? "   (installed now)" : "");
        }
        new AlertDialog.Builder(this)
            .setTitle("SA-MP client version")
            .setItems(items, (d, which) -> {
                final String v = packs.get(which);
                if (v.equals(current)) return;
                runInBackground("Switching to " + v + "...", () -> SampClients.installPack(this, v, gta),
                    ok -> toast(ok ? "Installed SA-MP " + v : "Could not install " + v + ". Check that the app can write to the GTA folder."));
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /* ---------------------------------------------------------------- setup */

    /** Winlator Ludashi's first-run setup (Windows system, Wine/Proton, drivers) is done. */
    private boolean systemReady() {
        boolean onboarded = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(OnboardingActivity.PREF_ONBOARDING_COMPLETE, false);
        return onboarded && ImageFs.find(this).isValid() && !new ContainerManager(this).getContainers().isEmpty();
    }

    /* ----------------------------------------------------------- crash logs */

    private void checkCrashNotices() {
        if (isFinishing()) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            File exitLog = CrashLog.recordPreviousExits(this);
            File notice = CrashLog.takePendingNotice(this);
            final File show = notice != null ? notice : exitLog;
            if (show == null) return;
            handler.post(() -> {
                if (isFinishing()) return;
                String name = show.getName();
                String message;
                if (name.startsWith("game-")) message = "The game closed. A log of this launch was saved, so if it crashed you can see why.";
                else message = "SA-MP Mobile crashed last time. A crash log was saved.";
                new AlertDialog.Builder(this)
                    .setTitle("Crash log saved")
                    .setMessage(message + "\n\n" + name)
                    .setPositiveButton("View", (d, w) -> showLog(show))
                    .setNeutralButton("Share", (d, w) -> shareLog(show))
                    .setNegativeButton("Close", null)
                    .show();
            });
        });
    }

    private void showLogsDialog() {
        final ArrayList<File> logs = CrashLog.listLogs(this);
        if (logs.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Crash logs")
                .setMessage("No logs yet. A log is saved every time you connect, and whenever the app crashes.\n\nFolder: " + CrashLog.getLogsDir(this).getPath())
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        String[] items = new String[logs.size()];
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.getDefault());
        for (int i = 0; i < logs.size(); i++) {
            File f = logs.get(i);
            String kind = f.getName().startsWith("game-") ? "Game" : f.getName().startsWith("app-exit-") ? "App stopped" : "App crash";
            items[i] = kind + "  \u2022  " + fmt.format(new java.util.Date(f.lastModified())) + "  (" + Math.max(1, f.length() / 1024) + " KB)";
        }
        new AlertDialog.Builder(this)
            .setTitle("Crash logs (newest first)")
            .setItems(items, (d, which) -> showLog(logs.get(which)))
            .setNegativeButton("Close", null)
            .show();
    }

    private void showLog(File file) {
        String text = CrashLog.forSharing(file);
        TextView tv = new TextView(this);
        tv.setText(text.length() > 60000 ? text.substring(0, 60000) + "\n\n[... open Share for the rest ...]" : text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setView(sv)
            .setPositiveButton("Share", (d, w) -> shareLog(file))
            .setNeutralButton("Copy", (d, w) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(file.getName(), CrashLog.forSharing(file)));
                toast("Log copied. Paste it in a message.");
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void shareLog(File file) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "SA-MP Mobile log " + file.getName());
        send.putExtra(Intent.EXTRA_TEXT, CrashLog.forSharing(file));
        try {
            startActivity(Intent.createChooser(send, "Share log"));
        }
        catch (Exception e) {
            toast("No app to share with. The file is in " + CrashLog.getLogsDir(this).getPath());
        }
    }

    /* ------------------------------------------------------------- folders */

    private void pickFolder(int requestCode) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, requestCode);
        }
        catch (Exception e) {
            toast("No folder picker available. Type the path instead.");
        }
    }

    /** Converts a document-tree URI from the system picker into a real file path. */
    private static String treeUriToPath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int colon = docId.indexOf(':');
            String volume = colon >= 0 ? docId.substring(0, colon) : docId;
            String rel = colon >= 0 ? docId.substring(colon + 1) : "";
            String base = volume.equalsIgnoreCase("primary")
                ? Environment.getExternalStorageDirectory().getPath()
                : "/storage/" + volume;
            return rel.isEmpty() ? base : base + "/" + rel;
        }
        catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String path = treeUriToPath(data.getData());
        if (path == null) {
            toast("Couldn't read that folder");
            return;
        }
        if (requestCode == REQUEST_GTA_FOLDER) {
            etGtaPath.setText(path);
            saveFields();
            refreshStatus();
            if (!SampClients.isGtaFolder(new File(path))) toast("gta_sa.exe isn't in that folder");
        }
        else if (requestCode == REQUEST_CLIENT_FOLDER) {
            final File src = new File(path);
            runInBackground("Importing client files...", () -> SampClients.importPack(this, src) != null, ok -> {
                if (ok) {
                    toast("Saved SA-MP " + SampClients.detectVersion(src) + ". Pick it in \"Change client version\".");
                }
                else toast("No samp.dll found in that folder");
            });
        }
    }

    /* -------------------------------------------------------------- launch */

    private void connect() {
        saveFields();
        String nick = config.getNickname();
        if (!nick.matches("[A-Za-z0-9_\\[\\]\\.\\$@=()]{3,24}")) {
            toast("Nickname: 3-24 characters, letters, numbers and _ [ ] . $ @ = ( )");
            return;
        }
        ArrayList<SampConfig.Server> servers = config.getServers();
        int sel = config.getSelectedServer();
        if (sel < 0 || sel >= servers.size()) {
            toast("Add and select a server first");
            return;
        }
        final File gta = new File(config.getGtaPath());
        if (!SampClients.isGtaFolder(gta)) {
            toast("Choose your GTA San Andreas folder (with gta_sa.exe)");
            return;
        }
        if (SampClients.findCaseInsensitive(gta, "samp.dll") == null) {
            toast("SA-MP isn't installed in that folder (samp.dll missing)");
            return;
        }
        if (!hasStoragePermission()) {
            requestStoragePermissions();
            toast("Allow storage access, then press Connect again");
            return;
        }
        if (!systemReady()) {
            new AlertDialog.Builder(this)
                .setTitle("One-time setup")
                .setMessage("The Windows system (Wine/Proton and the graphics drivers) needs to be set up first. This happens once and takes a few minutes.\n\nFollow the setup screens. When you reach the Winlator main screen, press Back to return here and connect.")
                .setPositiveButton("Install now", (d, w) -> startActivity(new Intent(this, MainActivity.class)))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }

        final SampConfig.Server server = servers.get(sel);
        final ContainerManager manager = new ContainerManager(this);
        Container container = manager.getContainerById(config.getContainerId());
        if (container != null) {
            launch(manager, container, gta, server, nick);
            return;
        }

        progress = ProgressDialog.show(this, null, "Creating the SA-MP Windows environment...", true, false);
        try {
            /* same settings Winlator Ludashi's setup uses for a new phone (newest Turnip through
               Adrenotools, DXVK, FEX/Box64), with the Wine/Proton of the first container */
            ContentsManager contentsManager = new ContentsManager(this);
            contentsManager.syncContents();
            String runtime = manager.getContainers().get(0).getWineVersion();
            WineInfo wineInfo = WineInfo.fromIdentifier(this, contentsManager, runtime);
            JSONObject data = new JSONObject();
            data.put("name", "SA-MP");
            data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
            data.put("envVars", Container.DEFAULT_ENV_VARS.replaceAll("DXVK_HUD=\\S+ ?", ""));
            data.put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
            data.put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG);
            data.put("rendererNative", false);
            data.put("rendererPresentMode", "fifo");
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("emulator", wineInfo.isArm64EC() ? "FEXCore" : "Box64");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", drivesFor(gta));
            data.put("box64Version", wineInfo.isArm64EC() ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
            data.put("box64Preset", Box64Preset.COMPATIBILITY);
            data.put("fexcoreVersion", DefaultVersion.FEXCORE);
            data.put("fexcorePreset", FEXCorePreset.INTERMEDIATE);
            data.put("wineVersion", runtime);
            data.put("showFPS", false);
            OpenGLDriverDefaults.initialize(this, data);
            manager.createContainerAsync(data, contentsManager, created -> {
                dismissProgress();
                if (created == null) {
                    toast("Couldn't create the Windows environment. Try Advanced settings > Containers.");
                    return;
                }
                config.setContainerId(created.id);
                launch(manager, created, gta, server, nick);
            });
        }
        catch (Exception e) {
            dismissProgress();
            toast("Couldn't prepare the Windows environment: " + e.getMessage());
        }
    }

    private static String drivesFor(File gta) {
        /* D: = the GTA folder, F: = phone storage (Ludashi's default) */
        return "D:" + gta.getPath() + "F:" + Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    private void launch(ContainerManager manager, Container container, File gta, SampConfig.Server server, String nick) {
        /* D: is always the GTA folder */
        container.setDrives(drivesFor(gta));
        container.saveData();

        /* SA-MP reads the nickname and the game path from the registry */
        File userReg = new File(container.getRootDir(), ".wine/user.reg");
        try (WineRegistryEditor editor = new WineRegistryEditor(userReg)) {
            editor.setStringValue("Software\\SAMP", "PlayerName", nick);
            editor.setStringValue("Software\\SAMP", "gta_sa_exe", "D:\\gta_sa.exe");
        }
        catch (Exception e) {}

        int profileId = ensureControlsProfile();

        /* Crash log for this launch: setup snapshot now, Wine output appended by the game screen */
        File sessionLog = CrashLog.startSession(this, container, gta, server, nick);

        /* Optional: use the PC's GTA settings and key bindings. If the GTA folder contains
           "GTA San Andreas User Files" (copied from Documents on the PC), copy gta_sa.set into
           the Windows user's Documents, where GTA reads it. Otherwise GTA uses its PC defaults. */
        File pcUserFiles = SampClients.findCaseInsensitive(gta, "GTA San Andreas User Files");
        if (pcUserFiles != null && pcUserFiles.isDirectory()) {
            File set = SampClients.findCaseInsensitive(pcUserFiles, "gta_sa.set");
            if (set != null && set.isFile()) {
                File dstDir = new File(container.getRootDir(), ".wine/drive_c/users/" + ImageFs.USER + "/Documents/GTA San Andreas User Files");
                dstDir.mkdirs();
                FileUtils.copy(set, new File(dstDir, "gta_sa.set"));
            }
        }

        /* Boot the game directly: the built-in starter runs gta_sa.exe with the server
           arguments and loads samp.dll into it (what samp.exe does), so no launcher -
           not even a modpack's replaced samp.exe - gets in the way. */
        File injector = new File(container.getRootDir(), ".wine/drive_c/sampmobile/sampinject.exe");
        injector.getParentFile().mkdirs();
        FileUtils.copy(this, SAMP_INJECT_ASSET, injector);
        if (!injector.isFile()) {
            toast("The built-in SA-MP starter is missing from this build");
            return;
        }
        File gtaExe = SampClients.findCaseInsensitive(gta, "gta_sa.exe");
        File sampDll = SampClients.findCaseInsensitive(gta, "samp.dll");
        String exe = "C:\\\\\\\\sampmobile\\\\\\\\sampinject.exe";
        String gameArgs = "D:\\" + gtaExe.getName() + " D:\\" + sampDll.getName() +
            " -c -n " + nick + " -h " + server.host + " -p " + server.port;
        File desktop = container.getDesktopDir();
        if (!desktop.isDirectory()) desktop.mkdirs();
        File shortcut = new File(desktop, "SA-MP.desktop");
        StringBuilder sb = new StringBuilder();
        sb.append("[Desktop Entry]\n");
        sb.append("Name=SA-MP\n");
        sb.append("Exec=env WINEPREFIX=\"").append(ImageFs.WINEPREFIX).append("\" wine ").append(exe).append("\n");
        sb.append("Type=Application\n");
        sb.append("StartupNotify=true\n");
        sb.append("StartupWMClass=gta_sa.exe\n");
        sb.append("\n[Extra Data]\n");
        sb.append("execArgs=").append(gameArgs).append("\n");
        sb.append("sampMode=1\n");
        sb.append("sampLog=").append(sessionLog.getPath()).append("\n");
        sb.append("sampGta=").append(gta.getPath()).append("\n");
        /* errors, exceptions and DLL loads from Wine; segfault details from Box86/Box64 */
        sb.append("envVars=WINEDEBUG=-all,err+all,warn+seh,warn+module,+loaddll BOX86_LOG=1 BOX86_SHOWSEGV=1 BOX86_NOBANNER=0 BOX64_LOG=1 BOX64_SHOWSEGV=1 BOX64_NOBANNER=0\n");
        if (profileId > 0) sb.append("controlsProfile=").append(profileId).append("\n");
        if (!FileUtils.writeString(shortcut, sb.toString())) {
            toast("Couldn't write the launch shortcut");
            return;
        }

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcut.getPath());
        startActivity(intent);
    }

    /** Makes sure the SA-MP touch controls profile exists and returns its id. */
    private int ensureControlsProfile() {
        InputControlsManager manager = new InputControlsManager(this);
        for (ControlsProfile p : manager.getProfiles()) {
            if (p.id == SAMP_CONTROLS_PROFILE_ID) return p.id;
        }
        File dst = new File(InputControlsManager.getProfilesDir(this), "controls-" + SAMP_CONTROLS_PROFILE_ID + ".icp");
        FileUtils.copy(this, SAMP_CONTROLS_ASSET, dst);
        return dst.isFile() ? SAMP_CONTROLS_PROFILE_ID : 0;
    }

    /* -------------------------------------------------------- permissions */

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermissions() {
        if (hasStoragePermission()) return;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSIONS);
    }

    /* ------------------------------------------------------------- helpers */

    private interface Task { boolean run(); }
    private interface Done { void done(boolean ok); }

    private void runInBackground(String message, Task task, Done done) {
        progress = ProgressDialog.show(this, null, message, true, false);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok;
            try { ok = task.run(); }
            catch (Exception e) { ok = false; }
            final boolean result = ok;
            handler.post(() -> {
                dismissProgress();
                refreshStatus();
                done.done(result);
            });
        });
    }

    private void dismissProgress() {
        if (progress != null && progress.isShowing()) {
            try { progress.dismiss(); } catch (Exception e) {}
        }
        progress = null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
