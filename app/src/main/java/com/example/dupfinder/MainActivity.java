package com.example.dupfinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Duplicate Cleaner: finds files with identical content anywhere in phone storage
 * and lets you delete the extra copies. One copy of each file is always kept.
 */
public class MainActivity extends Activity {

    static class Group {
        final List<Item> items = new ArrayList<>();
        long size;
    }

    static class Item {
        Group group;
        File file;
        boolean checked;
    }

    static class RowHolder {
        CheckBox cb;
        TextView name, folder, meta;
    }

    private static final int PRIMARY = 0xFF00796B;
    private static final int PRIMARY_DARK = 0xFF004D40;
    private static final int PRIMARY_LIGHT = 0xFFE0F2F1;
    private static final int DANGER = 0xFFD32F2F;
    private static final int BG = 0xFFF5F5F5;
    private static final int PARTIAL_BYTES = 64 * 1024;

    private final List<Group> groups = new ArrayList<>();
    private final List<Object> flat = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private ListView listView;
    private Button scanBtn, deleteBtn;
    private TextView status, subtitle, emptyView;
    private LinearLayout selectRow;
    private ProgressBar progress;
    private BaseAdapter adapter;
    private String rootPath;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        getWindow().setStatusBarColor(PRIMARY_DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(PRIMARY);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        header.setElevation(dp(4));
        TextView title = new TextView(this);
        title.setText("Duplicate Cleaner");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        subtitle = new TextView(this);
        subtitle.setText("Find and remove identical files");
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setTextSize(13);
        header.addView(title);
        header.addView(subtitle);
        root.addView(header);

        // Controls
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(12), dp(12), dp(4));

        scanBtn = makeButton("Scan for duplicates", PRIMARY);
        scanBtn.setOnClickListener(v -> onScanClicked());
        controls.addView(scanBtn);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        plp.topMargin = dp(8);
        controls.addView(progress, plp);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFF212121);
        status.setTypeface(null, Typeface.BOLD);
        status.setPadding(dp(2), dp(10), dp(2), dp(4));
        status.setVisibility(View.GONE);
        controls.addView(status);

        selectRow = new LinearLayout(this);
        selectRow.setOrientation(LinearLayout.HORIZONTAL);
        selectRow.setVisibility(View.GONE);
        selectRow.addView(smallButton("Keep oldest", v -> selectKeep(false)));
        selectRow.addView(smallButton("Keep newest", v -> selectKeep(true)));
        selectRow.addView(smallButton("Clear", v -> clearSelection()));
        controls.addView(selectRow);
        root.addView(controls);

        // List + empty state
        FrameLayout listFrame = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(dp(12), dp(4), dp(12), dp(8));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            Object o = flat.get(pos);
            if (o instanceof Item && !busy) toggle((Item) o);
        });
        emptyView = new TextView(this);
        emptyView.setText("Tap \"Scan for duplicates\" to search your whole phone:\nmusic, photos, PDFs, ringtones, videos and more.");
        emptyView.setTextColor(0xFF757575);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
        listFrame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        root.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom delete bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setBackgroundColor(Color.WHITE);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottom.setElevation(dp(8));
        deleteBtn = makeButton("Delete selected", DANGER);
        deleteBtn.setOnClickListener(v -> confirmDelete());
        setEnabledLook(deleteBtn, false);
        bottom.addView(deleteBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        setContentView(root);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return flat.size(); }
            @Override public Object getItem(int i) { return flat.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) {
                return flat.get(i) instanceof Group ? 0 : 1;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                Object o = flat.get(i);
                if (o instanceof Group) {
                    Group g = (Group) o;
                    TextView tv = cv != null ? (TextView) cv : new TextView(MainActivity.this);
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setTextColor(PRIMARY_DARK);
                    tv.setTextSize(14);
                    tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                    float r = dp(10);
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(PRIMARY_LIGHT);
                    gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                    tv.setBackground(gd);
                    tv.setText(g.items.size() + " identical copies  \u2022  "
                            + Formatter.formatFileSize(MainActivity.this, g.size) + " each");
                    return tv;
                }
                final Item it = (Item) o;
                RowHolder h;
                View row = cv;
                if (row == null) {
                    LinearLayout l = new LinearLayout(MainActivity.this);
                    l.setOrientation(LinearLayout.HORIZONTAL);
                    l.setGravity(Gravity.CENTER_VERTICAL);
                    l.setBackgroundColor(Color.WHITE);
                    l.setPadding(dp(8), dp(8), dp(12), dp(8));
                    h = new RowHolder();
                    h.cb = new CheckBox(MainActivity.this);
                    h.cb.setClickable(false);
                    h.cb.setFocusable(false);
                    LinearLayout col = new LinearLayout(MainActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    h.name = new TextView(MainActivity.this);
                    h.name.setTextSize(15);
                    h.name.setTextColor(0xFF212121);
                    h.name.setSingleLine(true);
                    h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    h.folder = new TextView(MainActivity.this);
                    h.folder.setTextSize(12);
                    h.folder.setTextColor(0xFF757575);
                    h.folder.setSingleLine(true);
                    h.folder.setEllipsize(TextUtils.TruncateAt.START);
                    h.meta = new TextView(MainActivity.this);
                    h.meta.setTextSize(12);
                    col.addView(h.name);
                    col.addView(h.folder);
                    col.addView(h.meta);
                    l.addView(h.cb);
                    l.addView(col, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    l.setTag(h);
                    row = l;
                } else {
                    h = (RowHolder) row.getTag();
                }
                File f = it.file;
                h.cb.setChecked(it.checked);
                h.name.setText(emojiFor(f.getName()) + "  " + f.getName());
                String dir = f.getParent() == null ? "" : f.getParent();
                if (dir.startsWith(rootPath)) dir = dir.substring(rootPath.length());
                h.folder.setText(dir.isEmpty() ? "/" : dir);
                h.meta.setText(Formatter.formatFileSize(MainActivity.this, f.length())
                        + "  \u2022  " + DateFormat.getDateInstance().format(new Date(f.lastModified()))
                        + (it.checked ? "  \u2022  will be deleted" : "  \u2022  keep"));
                h.meta.setTextColor(it.checked ? DANGER : 0xFF2E7D32);
                return row;
            }
        };
        listView.setAdapter(adapter);
    }

    private static String emojiFor(String name) {
        int dot = name.lastIndexOf('.');
        String e = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (e) {
            case "mp3": case "m4a": case "wav": case "flac": case "ogg": case "aac":
            case "opus": case "amr": case "wma": case "mid":
                return "\uD83C\uDFB5";
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "heic": case "bmp":
                return "\uD83D\uDDBC";
            case "mp4": case "mkv": case "3gp": case "avi": case "mov": case "webm":
                return "\uD83C\uDFAC";
            case "pdf":
                return "\uD83D\uDCD5";
            case "doc": case "docx": case "txt": case "xls": case "xlsx": case "ppt":
            case "pptx": case "odt": case "csv": case "rtf":
                return "\uD83D\uDCC4";
            case "zip": case "rar": case "7z": case "apk": case "tar": case "gz":
                return "\uD83D\uDCE6";
            default:
                return "\uD83D\uDCCE";
        }
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(12));
        b.setBackground(gd);
        b.setStateListAnimator(null);
        return b;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(PRIMARY_DARK);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(PRIMARY_LIGHT);
        gd.setCornerRadius(dp(18));
        b.setBackground(gd);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void setEnabledLook(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(final String s) {
        ui.post(() -> {
            status.setText(s);
            status.setVisibility(View.VISIBLE);
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        setEnabledLook(scanBtn, !b);
        updateDeleteButton();
    }

    // --------------------------------------------------------- Selection

    private void toggle(Item it) {
        if (!it.checked) {
            int others = 0;
            for (Item o : it.group.items) if (o != it && o.checked) others++;
            if (others >= it.group.items.size() - 1) {
                Toast.makeText(this, "Keep at least one copy of each file.", Toast.LENGTH_SHORT).show();
                return;
            }
            it.checked = true;
        } else {
            it.checked = false;
        }
        adapter.notifyDataSetChanged();
        updateDeleteButton();
    }

    private void selectKeep(boolean newest) {
        for (Group g : groups) {
            int keep = newest ? g.items.size() - 1 : 0; // items are sorted oldest -> newest
            for (int i = 0; i < g.items.size(); i++) g.items.get(i).checked = i != keep;
        }
        adapter.notifyDataSetChanged();
        updateDeleteButton();
    }

    private void clearSelection() {
        for (Group g : groups) for (Item it : g.items) it.checked = false;
        adapter.notifyDataSetChanged();
        updateDeleteButton();
    }

    private void updateDeleteButton() {
        int n = 0;
        long bytes = 0;
        for (Group g : groups) for (Item it : g.items) if (it.checked) { n++; bytes += g.size; }
        setEnabledLook(deleteBtn, n > 0 && !busy);
        deleteBtn.setText(n == 0 ? "Delete selected"
                : "Delete " + n + " files  (" + Formatter.formatFileSize(this, bytes) + ")");
    }

    private void flatten() {
        flat.clear();
        for (Group g : groups) {
            flat.add(g);
            flat.addAll(g.items);
        }
        adapter.notifyDataSetChanged();
        selectRow.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        updateDeleteButton();
    }

    // ------------------------------------------------------- Permissions

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onScanClicked() {
        if (busy) return;
        if (!hasAccess()) {
            requestAccess();
            return;
        }
        startScan();
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Allow \"All files access\", then come back and tap Scan again.",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startScan();
    }

    // -------------------------------------------------------------- Scan

    private void startScan() {
        setBusy(true);
        groups.clear();
        flatten();
        emptyView.setText("Scanning your files...");
        setStatus("Scanning...");
        new Thread(() -> {
            try {
                runScan();
            } catch (Throwable t) {
                setStatus("Scan failed: " + t.getMessage());
            } finally {
                ui.post(() -> setBusy(false));
            }
        }).start();
    }

    private void runScan() throws Exception {
        File root = Environment.getExternalStorageDirectory();

        // 1) Walk every folder, group files by exact size.
        Map<Long, List<File>> bySize = new HashMap<>();
        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        int count = 0;
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                if (f.isDirectory()) {
                    if (dir.equals(root) && f.getName().equals("Android")) continue; // app-private
                    try {
                        if (!f.getCanonicalPath().equals(f.getAbsolutePath())) continue; // symlink
                    } catch (IOException e) {
                        continue;
                    }
                    stack.push(f);
                } else if (f.isFile()) {
                    long len = f.length();
                    if (len > 0) {
                        bySize.computeIfAbsent(len, k -> new ArrayList<>()).add(f);
                    }
                    if (++count % 300 == 0) setStatus("Scanning... " + count + " files found");
                }
            }
        }

        // 2) Only files that share a size can be duplicates.
        List<List<File>> candidates = new ArrayList<>();
        int total = 0;
        for (List<File> l : bySize.values()) {
            if (l.size() > 1) { candidates.add(l); total += l.size(); }
        }

        // 3) Compare content: quick hash of the start, then full hash if needed.
        List<Group> found = new ArrayList<>();
        int done = 0;
        for (List<File> sameSize : candidates) {
            long len = sameSize.get(0).length();
            Map<String, List<File>> byPartial = new HashMap<>();
            for (File f : sameSize) {
                try {
                    byPartial.computeIfAbsent(hash(f, PARTIAL_BYTES), k -> new ArrayList<>()).add(f);
                } catch (Exception ignored) { }
                if (++done % 20 == 0) setStatus("Comparing files... " + done + " / " + total);
            }
            for (List<File> p : byPartial.values()) {
                if (p.size() < 2) continue;
                List<List<File>> identical = new ArrayList<>();
                if (len <= PARTIAL_BYTES) {
                    identical.add(p);
                } else {
                    Map<String, List<File>> byFull = new HashMap<>();
                    for (File f : p) {
                        try {
                            byFull.computeIfAbsent(hash(f, -1), k -> new ArrayList<>()).add(f);
                        } catch (Exception ignored) { }
                    }
                    identical.addAll(byFull.values());
                }
                for (List<File> same : identical) {
                    if (same.size() < 2) continue;
                    Collections.sort(same, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                    Group g = new Group();
                    g.size = len;
                    for (int i = 0; i < same.size(); i++) {
                        Item it = new Item();
                        it.group = g;
                        it.file = same.get(i);
                        it.checked = i > 0; // keep the oldest copy by default
                        g.items.add(it);
                    }
                    found.add(g);
                }
            }
        }

        Collections.sort(found, (a, b) ->
                Long.compare(b.size * (b.items.size() - 1), a.size * (a.items.size() - 1)));
        long wasted = 0;
        for (Group g : found) wasted += g.size * (g.items.size() - 1);
        final long wastedF = wasted;

        ui.post(() -> {
            groups.clear();
            groups.addAll(found);
            flatten();
            if (found.isEmpty()) {
                emptyView.setText("\u2705  No duplicates found.\nYour phone is clean!");
                status.setVisibility(View.GONE);
            } else {
                status.setText(found.size() + " duplicate sets \u2022 "
                        + Formatter.formatFileSize(this, wastedF) + " can be freed");
                status.setVisibility(View.VISIBLE);
                subtitle.setText("Tap files to select or unselect them. Oldest copies are kept by default.");
            }
        });
    }

    /** SHA-256 of the file; limit > 0 hashes only the first `limit` bytes. */
    private static String hash(File f, int limit) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[limit > 0 ? limit : 65536];
            if (limit > 0) {
                int off = 0, n;
                while (off < limit && (n = in.read(buf, off, limit - off)) > 0) off += n;
                md.update(buf, 0, off);
            } else {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------ Delete

    private void confirmDelete() {
        int n = 0;
        long bytes = 0;
        for (Group g : groups) for (Item it : g.items) if (it.checked) { n++; bytes += g.size; }
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete " + n + " files?")
                .setMessage("This permanently deletes " + n + " files ("
                        + Formatter.formatFileSize(this, bytes)
                        + "). At least one copy of each file is kept. This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> doDelete())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDelete() {
        setBusy(true);
        setStatus("Deleting...");
        new Thread(() -> {
            List<String> removed = new ArrayList<>();
            int failed = 0;
            long freed = 0;
            for (Group g : groups) {
                int checked = 0;
                for (Item it : g.items) if (it.checked) checked++;
                if (checked >= g.items.size()) continue; // safety: never delete every copy
                for (Item it : g.items) {
                    if (!it.checked) continue;
                    if (it.file.delete()) {
                        removed.add(it.file.getAbsolutePath());
                        freed += g.size;
                    } else {
                        failed++;
                    }
                }
            }
            if (!removed.isEmpty()) {
                MediaScannerConnection.scanFile(this, removed.toArray(new String[0]), null, null);
            }
            final int failedF = failed;
            final int removedN = removed.size();
            final long freedF = freed;
            ui.post(() -> {
                List<Group> keep = new ArrayList<>();
                for (Group g : groups) {
                    g.items.removeIf(it -> !it.file.exists());
                    if (g.items.size() > 1) keep.add(g);
                }
                groups.clear();
                groups.addAll(keep);
                setBusy(false);
                flatten();
                emptyView.setText("\u2705  All done!\nNo more duplicates left.");
                status.setText("Deleted " + removedN + " files \u2022 freed "
                        + Formatter.formatFileSize(this, freedF)
                        + (failedF > 0 ? " \u2022 " + failedF + " failed" : ""));
                status.setVisibility(View.VISIBLE);
            });
        }).start();
    }
}
