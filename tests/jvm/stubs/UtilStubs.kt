package app.grapheneos.apps.util

import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

fun PackageManager.getPackageInfoOrNull(packageName: String, flags: Long = 0L): PackageInfo? = installed[packageName]

// Signer checks of archives are out of scope: no archive has a signer.
fun PackageManager.getPackageArchiveInfo(archiveFilePath: String, flags: Long = 0L): PackageInfo? = null

val systemFeatures = HashMap<String, FeatureInfo>()
fun maybeGetSystemFeatureInfo(name: String): FeatureInfo? = systemFeatures[name]

fun throwIfAppInstallationNotAllowed() {}

fun StringBuilder.appendRes(ctx: Context, ref: Int) { append(ref) }
