package com.erbaskaya.selam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class UpdateManager {
    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/erbaskaya/selam/releases/latest";

    private final Activity activity;
    private long downloadId = -1L;
    private Uri pendingInstallUri;
    private BroadcastReceiver receiver;
    private boolean checking;

    UpdateManager(Activity activity) {
        this.activity = activity;
    }

    void checkForUpdates(boolean showCurrentMessage) {
        if (checking) return;
        checking = true;
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE).openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Selam-Android/" + BuildConfig.VERSION_NAME);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new Exception("Güncelleme sunucusuna ulaşılamadı.");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                } finally {
                    connection.disconnect();
                }
                JSONObject release = new JSONObject(body.toString());
                String version = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                String downloadUrl = findApk(release.optJSONArray("assets"));
                activity.runOnUiThread(() -> {
                    checking = false;
                    if (isNewer(version, BuildConfig.VERSION_NAME) && !downloadUrl.isEmpty()) {
                        showUpdate(version, downloadUrl);
                    } else if (showCurrentMessage) {
                        Toast.makeText(activity, "Selam güncel: v" + BuildConfig.VERSION_NAME,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception exception) {
                activity.runOnUiThread(() -> {
                    checking = false;
                    if (showCurrentMessage) Toast.makeText(activity,
                            "Güncelleme kontrol edilemedi. İnternet bağlantınızı kontrol edin.",
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String findApk(JSONArray assets) {
        if (assets == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && "Selam.apk".equalsIgnoreCase(asset.optString("name"))) {
                return asset.optString("browser_download_url", "");
            }
        }
        return "";
    }

    private boolean isNewer(String available, String installed) {
        String[] left = available.split("\\.");
        String[] right = installed.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = numberAt(left, i);
            int b = numberAt(right, i);
            if (a != b) return a > b;
        }
        return false;
    }

    private int numberAt(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void showUpdate(String version, String downloadUrl) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Selam v" + version + " hazır")
                .setMessage("Yeni sürümü şimdi indirip kurabilirsiniz. Hesabınız ve sohbetleriniz korunur.")
                .setNegativeButton("Daha sonra", null)
                .setPositiveButton("Güncelle", (dialog, which) -> download(version, downloadUrl))
                .show();
    }

    private void download(String version, String downloadUrl) {
        try {
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl))
                    .setTitle("Selam v" + version)
                    .setDescription("Güncelleme indiriliyor…")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                            "Selam-v" + version + ".apk");
            registerReceiver();
            downloadId = manager.enqueue(request);
            Toast.makeText(activity, "Güncelleme indiriliyor…", Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(activity, "Güncelleme indirilemedi.", Toast.LENGTH_LONG).show();
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (completed != downloadId) return;
                DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                pendingInstallUri = manager.getUriForDownloadedFile(downloadId);
                if (pendingInstallUri == null) {
                    Toast.makeText(activity, "Güncelleme indirilemedi.", Toast.LENGTH_LONG).show();
                    return;
                }
                openInstallerOrPermission();
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
    }

    void resumeInstallIfReady() {
        if (pendingInstallUri != null && activity.getPackageManager().canRequestPackageInstalls()) {
            openInstaller();
        }
    }

    private void openInstallerOrPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity,
                    "Selam güncellemesini kurabilmek için bu kaynağa izin verin.",
                    Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        openInstaller();
    }

    private void openInstaller() {
        if (pendingInstallUri == null) return;
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(pendingInstallUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
        pendingInstallUri = null;
    }

    void close() {
        if (receiver == null) return;
        try {
            activity.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Etkinlik kapanırken alıcı zaten kaldırılmış olabilir.
        }
        receiver = null;
    }
}
