package com.example.dupfinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.Map;

/**
 * Duplicate Cleaner: finds files with identical content anywhere in phone storage
 * (music, PDFs, photos, ringtones, videos, documents - every file type) and lets
 * you delete the extra copies. One copy of each file is always kept.
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

    private static final int PARTIAL_BYTES = 64 * 1024;

    private final List<Group> groups = new ArrayList<>();
    private final List<Object> flat = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private ListView listView;
    private Button scanBtn;
    private Button deleteBtn;
    private TextView status;
    private BaseAdapter adapter;
    private String rootPath;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, p, p, p);

        scanBtn = new Button(this);
        scanBtn.setText("Scan for duplicates");
        scanBtn.setOnClickListener(v -> onScanClicked());

        status = new TextView(this);
        status.setText("Tap \"Scan\" to find duplicate files on your phone.");
        status.setPadding(0, dp(8), 0, dp(8));

        listView = new ListView(this);

        deleteBtn = new Button(this);
        deleteBtn.setText("Delete selected");
        deleteBtn.setEnabled(false);
        deleteBtn.setOnClickListener(v -> confirmDelete());

        root.addView(scanBtn);
        root.addView(status);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(deleteBtn);
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
                    tv.setBackgroundColor(0xFFE0E0E0);
                    tv.setPadding(dp(8), dp(8), dp(8), dp(8));
                    tv.setText(g.items.size() + " identical copies  •  "
                            + Formatter.formatFileSize(MainActivity.this, g.size) + " each");
                    return tv;
                }
                final Item it = (Item) o;
                CheckBox cb = cv != null ? (CheckBox) cv : new CheckBox(MainActivity.this);
                cb.setOnCheckedChangeListener(null);
                String rel = it.file.getAbsolutePath();
                if (rel.startsWith(rootPath + "/")) rel = rel.substring(rootPath.length() + 1);
                cb.setText(rel + "\n" + DateFormat.getDateInstance()
                        .format(new Date(it.file.lastModified())));
                cb.setTextSize(13);
                cb.setChecked(it.checked);
                cb.setOnCheckedChangeListener((b, c) -> onToggle(it, b, c));
                return cb;
            }
        };
        listView.setAdapter(adapter);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(final String s) {
        ui.post(() -> status.setText(s));
    }

    private void onToggle(Item it, CompoundButton b, boolean checked) {
        if (checked) {
            int others = 0;
            for (Item o : it.group.items) if (o != it && o.checked) others++;
            if (others >= it.group.items.size() - 1) {
                // Would delete every copy: refuse.
                Toast.makeText(this, "Keep at least one copy of each file.", Toast.LENGTH_SHORT).show();
                it.checked = false;
                ui.post(() -> adapter.notifyDataSetChanged());
                return;
            }
        }
        it.checked = checked;
        updateDeleteButton();
    }

    private void updateDeleteButton() {
        int n = 0;
        long bytes = 0;
        for (Group g : groups) for (Item it : g.items) if (it.checked) { n++; bytes += g.size; }
        deleteBtn.setEnabled(n > 0 && !busy);
        deleteBtn.setText(n == 0 ? "Delete selected"
                : "Delete selected (" + n + " files, " + Formatter.formatFileSize(this, bytes) + ")");
    }

    private void flatten() {
        flat.clear();
        for (Group g : groups) {
            flat.add(g);
            flat.addAll(g.items);
        }
        adapter.notifyDataSetChanged();
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
        busy = true;
        scanBtn.setEnabled(false);
        deleteBtn.setEnabled(false);
        groups.clear();
        flatten();
        new Thread(() -> {
            try {
                runScan();
            } catch (Throwable t) {
                setStatus("Scan failed: " + t.getMessage());
            } finally {
                ui.post(() -> {
                    busy = false;
                    scanBtn.setEnabled(true);
                    updateDeleteButton();
                });
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
            status.setText(found.isEmpty()
                    ? "No duplicates found."
                    : found.size() + " sets of duplicates. You can free "
                    + Formatter.formatFileSize(this, wastedF)
                    + ". Extra copies are pre-selected (oldest is kept). Review, then tap Delete.");
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
        busy = true;
        scanBtn.setEnabled(false);
        deleteBtn.setEnabled(false);
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
                busy = false;
                scanBtn.setEnabled(true);
                flatten();
                status.setText("Deleted " + removedN + " files, freed "
                        + Formatter.formatFileSize(this, freedF)
                        + (failedF > 0 ? ". " + failedF + " could not be deleted." : "."));
            });
        }).start();
    }
}
