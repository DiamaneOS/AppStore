// SPDX-License-Identifier: Apache-2.0
package de.diamaneos.downloads;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.provider.Settings;

/** One atomic value in the platform-owned global settings authority. */
public final class DownloadPolicyClient {
    public static final String KEY = "diamaneos_download_server_policy";
    private DownloadPolicyClient() {}

    public static DownloadPolicy read(Context context) {
        try {
            return DownloadPolicy.decode(Settings.Global.getString(context.getContentResolver(), KEY));
        } catch (RuntimeException e) {
            return DownloadPolicy.DEFAULT;
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
