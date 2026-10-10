// SPDX-License-Identifier: Apache-2.0
package de.diamaneos.downloads;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import java.io.IOException;
import android.os.UserManager;
import android.provider.Settings;

/** One atomic value in the platform-owned global settings authority. */
public final class DownloadPolicyClient {
    public static final String KEY = "diamaneos_download_server_policy";
    private DownloadPolicyClient() {}

    public static DownloadPolicy read(Context context) throws IOException {
        // getString() conflates an unset key with provider failure. Query distinguishes them.
        try (Cursor cursor = context.getContentResolver().query(Settings.Global.getUriFor(KEY),
                new String[]{Settings.Global.VALUE}, null, null, null)) {
            if (cursor == null) throw new IOException("Download server policy is unavailable");
            int rows = cursor.getCount();
            if (rows < 0 || rows > 1 || cursor.getColumnCount() != 1) {
                throw new IOException("Unexpected download policy response");
            }
            if (rows == 0) return DownloadPolicy.DEFAULT;
            if (!cursor.moveToFirst()) throw new IOException("Unreadable download policy response");
            return DownloadPolicy.decode(cursor.getString(0));
        } catch (RuntimeException e) {
            throw new IOException("Download server policy is unavailable", e);
        }
    }

    /** Guard this client's UI/setup writes; existing privileged platform writers retain control. */
    public static boolean canWrite(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && users.isSystemUser()
                && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean write(Context context, DownloadPolicy policy) {
        if (!canWrite(context)) return false;
        return Settings.Global.putString(context.getContentResolver(), KEY, policy.encode());
    }

    public static Intent settingsIntent(Context context) {
        if (context.getPackageManager().checkSignatures("android", "com.android.settings")
                != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Untrusted Settings application");
        }
        return new Intent("de.diamaneos.settings.DOWNLOAD_SERVERS").setComponent(
                new ComponentName("com.android.settings",
                        "com.android.settings.Settings$DownloadServersActivity"));
    }
}
