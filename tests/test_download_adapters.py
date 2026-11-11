"""Compile isolated JVM adapters with API and verifier stand-ins. Does not test Android or the crypto."""
import base64
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = {
    'android/Manifest.java': 'package android; public class Manifest { public static class permission { public static final String WRITE_SECURE_SETTINGS="android.permission.WRITE_SECURE_SETTINGS"; } }',
    'android/os/UserManager.java': 'package android.os; public class UserManager { public boolean isSystemUser(){return false;} }',
    'android/net/Uri.java': 'package android.net; public class Uri { public final String value; public Uri(String s){value=s;} }',
    'android/database/Cursor.java': 'package android.database; public class Cursor implements AutoCloseable {\n        public int getCount(){return android.provider.Settings.Global.value==null?0:1;} public int getColumnCount(){return 1;}\n        public boolean moveToFirst(){return true;} public String getString(int i){return android.provider.Settings.Global.value;} public void close(){} }',
    'android/provider/Settings.java': 'package android.provider; public class Settings { public static class Global {\n        public static String value; public static boolean unavailable,deny; public static final String VALUE="value";\n        public static android.net.Uri getUriFor(String k){if(!k.equals("diamaneos_download_server_policy"))throw new AssertionError();return new android.net.Uri("content://settings/global/"+k);}\n        public static boolean putString(android.content.ContentResolver r,String k,String v){throw new SecurityException("Unprivileged fixture");} } }',
    'android/net/Network.java': '''package android.net; public class Network {
        public java.net.URLConnection openConnection(java.net.URL u) throws java.io.IOException {return u.openConnection();} }''',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int SIGNATURE_MATCH=0,PERMISSION_GRANTED=0; public int checkSignatures(String a,String b){return 0;} }',
    'android/content/Context.java': 'package android.content; public class Context {\n        public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}\n        public ContentResolver getContentResolver(){return new ContentResolver();}\n        public <T> T getSystemService(Class<T> t){return t.cast(new android.os.UserManager());}\n        public int checkSelfPermission(String p){return -1;} }',
    'android/content/ContentResolver.java': 'package android.content; public class ContentResolver {\n        public android.database.Cursor query(android.net.Uri uri,String[] p,String w,String[] a,String o){\n            if(!uri.value.equals("content://settings/global/diamaneos_download_server_policy"))throw new AssertionError();\n            if(android.provider.Settings.Global.deny)throw new SecurityException("Authority fixture refusal");\n            if(android.provider.Settings.Global.unavailable)return null; return new android.database.Cursor();} }',
    'android/content/pm/Signature.java': 'package android.content.pm; public class Signature { final byte[] b; public Signature(byte[] b){this.b=b;} public byte[] toByteArray(){return b.clone();} }',
    'android/content/pm/SigningInfo.java': '''package android.content.pm; public class SigningInfo {
        final Signature[] s; final boolean multiple, past; public SigningInfo(Signature[] s,boolean multiple,boolean past){this.s=s;this.multiple=multiple;this.past=past;}
        public boolean hasMultipleSigners(){return multiple;} public boolean hasPastSigningCertificates(){return past;} public Signature[] getApkContentsSigners(){return s;} }''',
    'android/content/ComponentName.java': 'package android.content; public class ComponentName { public ComponentName(String p,String c){} }',
    'android/content/Intent.java': 'package android.content; public class Intent { public Intent(String a){} public Intent setComponent(ComponentName c){return this;} }',
    'org/grapheneos/tls/ModernTLSSocketFactory.java': '''package org.grapheneos.tls; public class ModernTLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
        public String[] getDefaultCipherSuites(){return new String[0];} public String[] getSupportedCipherSuites(){return new String[0];}
        public java.net.Socket createSocket(java.net.Socket s,String h,int p,boolean c){throw new UnsupportedOperationException();}
        public java.net.Socket createSocket(String h,int p){throw new UnsupportedOperationException();}
        public java.net.Socket createSocket(String h,int p,java.net.InetAddress a,int l){throw new UnsupportedOperationException();}
        public java.net.Socket createSocket(java.net.InetAddress h,int p){throw new UnsupportedOperationException();}
        public java.net.Socket createSocket(java.net.InetAddress h,int p,java.net.InetAddress a,int l){throw new UnsupportedOperationException();} }''',
    'org/json/JSONObject.java': '''package org.json; public class JSONObject {
        public static long nextTime=1800000000L; public static int parsed=0; public long time=nextTime; public boolean bad;
        public JSONObject(){} public JSONObject(String s){parsed++;bad=s.contains("unparsable");}
        public long getLong(String k){if(!k.equals("time"))throw new AssertionError();return time;}
        public Object opt(String k){return "png";} public String getString(String k){return k.equals("iconSha256")?"811d6293869324c4cc8274d854f28fcbc6efcab2e4d6cddb76d54fae9f2b7fa8":"png";} public int getInt(String k){return 12;}
        public JSONObject put(String k,Object v){if(k.equals("time"))time=((Number)v).longValue();return this;} }''',
}
CORE = '''package app.grapheneos.apps.core
import android.content.Context
import org.json.JSONObject
import java.security.GeneralSecurityException
val appContext = Context()
fun sha256FromString(str: String): ByteArray = java.util.HexFormat.of().parseHex(str).also { require(it.size == 32) }
const val REPO_BASE_URL = "https://apps.diamaneos.de"
// Stands in for the full parse: a part marked unparsable makes it throw, as an unknown source would.
class Repo(val parts: List<CatalogPart>) {
    val isDummy = parts.all { it.isDummy }
    init { if (parts.any { it.json.bad }) throw IllegalArgumentException("fixture catalog this App Store cannot parse") }
}
// Explicit verifier double: verifies call ordering, the key used and failure propagation, never cryptography.
class FileVerifier(val key: String) {
    init { if (key.isEmpty()) throw GeneralSecurityException("fixture key absent") }
    fun verifySignature(message: ByteArray, signature: String) {
        calls++
        keys += key
        if (!allowed || key in refused) throw GeneralSecurityException("fixture signature refused")
    }
    companion object { var allowed = true; var calls = 0; val keys = mutableListOf<String>(); var refused = setOf<String>() }
}
'''
STORAGE = '''package app.grapheneos.apps.util
class AtomicFile2(val name: String) {
    fun write(v: ByteArray) { files[name]=v }
    fun read(): ByteArray? { reads++;return files[name] }
    companion object { val files=mutableMapOf<String, ByteArray>();var reads=0 }
}
'''
HARNESS = '''package app.grapheneos.apps.core
import app.grapheneos.apps.util.*
import de.diamaneos.downloads.DownloadPolicy
import de.diamaneos.downloads.DownloadPolicyClient
import java.io.*
import java.net.*
import javax.net.ssl.*
import org.json.JSONObject
import java.security.GeneralSecurityException

const val ICON_SHA256 = "811d6293869324c4cc8274d854f28fcbc6efcab2e4d6cddb76d54fae9f2b7fa8"

object NetworkFixture {
    val opened: MutableList<HttpsURLConnection> = java.util.Collections.synchronizedList(mutableListOf())
    // GET requests only. Automatic adds a HEAD probe per server.
    val requests: List<URL> get() = synchronized(opened) { opened.filter { it.requestMethod != "HEAD" }.map { it.url } }
    val probes: Int get() = synchronized(opened) { opened.count { it.requestMethod == "HEAD" } }
    val paths: List<String> get() = requests.map { it.path }
    val ranges: List<String?> get() = synchronized(opened) { opened.filter { it.requestMethod != "HEAD" }.map { it.getRequestProperty("Range") } }
    // The first GET's answer. Canada's probe can be made to fail, so Europe goes first.
    var code = 200
    // Every GET's answer when set, or a path's.
    var everyCode: Int? = null
    val codes = mutableMapOf<String, Int>()
    var error: IOException? = null
    var failAt = -1L
    var canadaProbeFails = false
    var body = "fixture catalog JSON\\n".toByteArray() + "X".repeat(100).toByteArray() + "\\n".toByteArray()
    // Bodies by path, instead of body.
    val bodies = mutableMapOf<String, ByteArray>()
    fun install() {
        // Keep TLS entirely synthetic, including HttpsURLConnection's constructor default.
        HttpsURLConnection.setDefaultSSLSocketFactory(org.grapheneos.tls.ModernTLSSocketFactory())
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") throw AssertionError("No other protocol in fixture")
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host in setOf("apps.diamaneos.de", "apps-na.diamaneos.de"))
                    val connection = object : HttpsURLConnection(url) {
                        fun first() = synchronized(opened) { opened.first { it.requestMethod != "HEAD" } } === this
                        fun start() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int {
                            check(!instanceFollowRedirects)
                            if (requestMethod == "HEAD") return if (canadaProbeFails && url.host == "apps-na.diamaneos.de") 503 else 200
                            if (first()) error?.let { throw it }
                            val answer = codes[url.path] ?: everyCode ?: if (first()) code else 200
                            return if (answer == 200 && start() > 0) 206 else answer
                        }
                        val body = bodies[url.path] ?: NetworkFixture.body
                        override fun getContentLengthLong() = body.size - start()
                        override fun getHeaderField(name: String): String? =
                            if (name == "Content-Range" && start() > 0) "bytes ${start()}-${body.size - 1}/${body.size}" else null
                        override fun getInputStream(): InputStream {
                            val stop = if (first()) failAt else -1L
                            return object : InputStream() {
                                var at = start()
                                override fun read(): Int = throw UnsupportedOperationException()
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    if (at == stop) throw SocketException("fixture reset")
                                    val end = if (stop > at) stop else body.size.toLong()
                                    if (at >= end) return -1
                                    val count = minOf(len.toLong(), end - at).toInt()
                                    System.arraycopy(body, at.toInt(), b, off, count)
                                    at += count
                                    return count
                                }
                            }
                        }
                        override fun getCipherSuite(): String = throw UnsupportedOperationException()
                        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
                        override fun getServerCertificates(): Array<java.security.cert.Certificate> = throw UnsupportedOperationException()
                    }
                    opened += connection
                    return connection
                }
            }
        }
    }
    fun policy(mode: String) {
        android.provider.Settings.Global.value = DownloadPolicy.require(mode).encode()
    }
}
fun refuse(action: () -> Unit) {
    try { action() } catch (e: java.security.GeneralSecurityException) { return }
    throw AssertionError("Expected verification refusal")
}
fun main(args: Array<String>) {
    NetworkFixture.install()
    NetworkFixture.policy("primary_eu")
    when(args[0]) {
        "missing_key" -> {
            check(getCachedRepo().isDummy)
            refuse { fetchRepo(getCachedRepo()) }
            check(NetworkFixture.requests.isEmpty() && AtomicFile2.reads == 0)
        }
        "policy_unavailable" -> {
            for (mode in listOf("canada", "automatic")) {
                NetworkFixture.policy(mode)
                android.provider.Settings.Global.unavailable = true
                try { fetchRepo(getCachedRepo());error("Expected refusal") }
                catch (e: IOException) { check(!de.diamaneos.downloads.DownloadTransport.retryable(e)) }
                check(NetworkFixture.requests.isEmpty())
                // Also fail before the HTTP adapter's alternate-origin loop and omit optional icons.
                try { openConnection(null,"https://apps-na.diamaneos.de/metadata.1.0.sjson",{});error("Expected refusal") }
                catch (e: IOException) { }
                // The icon fields never read the policy, and an icon load fails without a request.
                check(IconFixture(JSONObject(), Catalog.DiamaneOS).iconPath == "packages/app.fixture/icon.png")
                try { DownloadIconLoader.load("packages/app.fixture/icon.png", 12, ICON_SHA256);error("Expected refusal") }
                catch (e: IOException) { }
                android.provider.Settings.Global.unavailable = false
                android.provider.Settings.Global.deny = true
                try { fetchRepo(getCachedRepo());error("Expected refusal") }
                catch (e: IOException) { }
                android.provider.Settings.Global.deny = false
                check(NetworkFixture.requests.isEmpty() && FileVerifier.calls == 0)
            }
        }
        "selected_region" -> {
            NetworkFixture.policy("canada")
            val result = fetchRepo(getCachedRepo())
            check(result.parts.map { it.timestamp } == listOf(1800000000L, 1800000000L) && !result.isDummy)
            check(NetworkFixture.requests.all { it.host == "apps-na.diamaneos.de" })
            check(FileVerifier.calls == 2 && JSONObject.parsed == 2)
        }
        "catalog_keys" -> {
            // Each catalog is fetched from its own path and verified with its own key only.
            val result = fetchRepo(getCachedRepo())
            check(NetworkFixture.paths == listOf("/metadata.1.0.sjson", "/grapheneos/metadata.1.0.sjson"))
            check(FileVerifier.keys == listOf("fixture public verifier", GRAPHENEOS_CATALOG_KEY))
            check(result.parts.map { it.catalog } == listOf(Catalog.DiamaneOS, Catalog.GrapheneOS))
            check(AtomicFile2.files.keys == setOf("repo", "repo-grapheneos"))
            // The caches stay bound to their catalog's key.
            val cached = getCachedRepo()
            check(!cached.isDummy && cached.parts.none { it.isDummy })
        }
        "partial_update" -> {
            // GrapheneOS's catalog fails its signature check. Our catalog's update is kept, the failure reported.
            FileVerifier.refused = setOf(GRAPHENEOS_CATALOG_KEY)
            try { fetchRepo(getCachedRepo());error("Expected a partial update") }
            catch (e: PartialRepoUpdate) {
                check(e.cause is java.security.GeneralSecurityException)
                check(!e.repo.parts[0].isDummy && e.repo.parts[1].isDummy)
            }
            check(AtomicFile2.files.keys == setOf("repo"))
            // And the reverse.
            AtomicFile2.files.clear()
            FileVerifier.refused = setOf("fixture public verifier")
            try { fetchRepo(getCachedRepo());error("Expected a partial update") }
            catch (e: PartialRepoUpdate) { check(e.repo.parts[0].isDummy && !e.repo.parts[1].isDummy) }
            check(AtomicFile2.files.keys == setOf("repo-grapheneos"))
        }
        "not_modified" -> {
            val first = fetchRepo(getCachedRepo())
            NetworkFixture.everyCode = 304
            check(fetchRepo(first) === first)
            check(NetworkFixture.requests.size == 4 && FileVerifier.calls == 2)
        }
        "scope" -> {
            val json = JSONObject()
            val ours = DIAMANEOS_SIGNERS.first()
            val camera = "6436b155b917c2f9a9ed1d15c4993a5968ffabc94947c13f2aeee14b7b27ed59"
            fun our(name: String, signers: List<String> = listOf(ours), original: String? = null, source: String? = "DiamaneOS") =
                CatalogEntry(Catalog.DiamaneOS, name, json, original, source, signers)
            fun their(name: String, original: String? = null) = CatalogEntry(Catalog.GrapheneOS, name, json, original, "GrapheneOS", listOf(camera))
            fun listed(vararg entries: CatalogEntry, missing: Boolean = false) = listedEntries(entries.toList(), missing).map { it.catalog to it.name }
            // Our catalog lists any package signed only by our certificates, CameraProcessor included.
            for (name in DIAMANEOS_PACKAGES + "de.diamaneos.camera.processor" + "de.diamaneos.future") {
                for (signer in DIAMANEOS_SIGNERS) check(listed(our(name, listOf(signer))) == listOf(Catalog.DiamaneOS to name))
                check(listed(our(name, source = "GrapheneOS"), our(name, source = null), our(name, original = "org.example.renamed")).isEmpty())
                // Another signer, alone or beside ours, two of ours, none, or ours in another spelling.
                check(listed(our(name, listOf(camera)), our(name, listOf(ours, camera)), our(name, DIAMANEOS_SIGNERS.toList()), our(name, emptyList()),
                    our(name, listOf(ours.uppercase()))).isEmpty())
                // While GrapheneOS's catalog is missing, only our own builds and names in our namespace.
                check(listed(our(name), missing = true) == listOf(Catalog.DiamaneOS to name))
            }
            // Other names stay hidden then, with other spellings of our namespace.
            for (name in listOf("org.example.other", "de.diamaneosx.app", "de.diamaneos-app", "De.Diamaneos.app", "app.grapheneos.camera.processor")) {
                check(listed(our(name), missing = true).isEmpty())
            }
            check(listed(our("de.diamaneos.camera.processor"), our("org.example.other"), missing = true) == listOf(Catalog.DiamaneOS to "de.diamaneos.camera.processor"))
            check(DIAMANEOS_SIGNERS.size == 3)
            // A pinned maker's app: listed only with its own signer, from our catalog, also while GrapheneOS's
            // catalog is missing. Its signer counts for no other name, and ours do not count for it.
            val tcl = PINNED_PACKAGES.getValue("com.fps.camera")
            check(PINNED_PACKAGES == mapOf("com.fps.camera" to "0f00b338d27aa1cd1c4a00e8584501ffb36775a07af42e1cc394ca1ed0908470"))
            check(listed(our("com.fps.camera", listOf(tcl))) == listOf(Catalog.DiamaneOS to "com.fps.camera"))
            check(listed(our("com.fps.camera", listOf(tcl)), missing = true) == listOf(Catalog.DiamaneOS to "com.fps.camera"))
            check(listed(our("com.fps.camera"), our("com.fps.camera", listOf(tcl, ours)), our("com.fps.camera", listOf(tcl.uppercase())),
                our("com.fps.camera", listOf(tcl), source = "Fairphone"), our("Com.Fps.Camera", listOf(tcl)),
                our("com.fps.camera.other", listOf(tcl)), our("de.diamaneos.future", listOf(tcl))).isEmpty())
            check(allowedSigner("com.fps.camera", tcl) && !allowedSigner("com.fps.camera", ours) && !allowedSigner("app.grapheneos.apps", tcl))
            // A name GrapheneOS's catalog lists, in any case or as the name it updates, stays GrapheneOS's.
            check(listed(their("app.vanadium.browser", "org.chromium.chrome"), our("app.vanadium.browser"), our("App.Vanadium.Browser"),
                our("org.chromium.chrome")) == listOf(Catalog.GrapheneOS to "app.vanadium.browser"))
            check(listed(their("app.grapheneos.camera.processor"), our("app.grapheneos.camera.processor")) == listOf(Catalog.GrapheneOS to "app.grapheneos.camera.processor"))
            // GrapheneOS's entries for our own builds do not count.
            for (name in DIAMANEOS_PACKAGES) check(listed(their(name), our(name)) == listOf(Catalog.DiamaneOS to name))
            for (name in DIAMANEOS_PACKAGES) {
                check(!Catalog.GrapheneOS.lists(name, null, "GrapheneOS"))
                // Nor through the original-package system.
                check(!Catalog.GrapheneOS.lists("app.example.other", name, "GrapheneOS"))
            }
            for (name in listOf("app.vanadium.browser", "app.grapheneos.pdfviewer", "com.google.android.gms")) {
                check(Catalog.GrapheneOS.lists(name, null, "GrapheneOS") && Catalog.GrapheneOS.lists(name, null, null))
            }
            check(Catalog.GrapheneOS.lists("app.vanadium.browser", "org.chromium.chrome", "GrapheneOS"))
            for (name in EXCLUDED_PACKAGES) {
                check(!Catalog.GrapheneOS.lists(name, null, "GrapheneOS") && listed(our(name), our(name.uppercase()), their(name)).isEmpty())
            }
            check(EXCLUDED_PACKAGES == setOf("app.grapheneos.info", "app.attestation.auditor"))
            check(DIAMANEOS_PACKAGES == setOf("app.grapheneos.apps", "app.grapheneos.camera", "de.diamaneos.auditor"))
            // GrapheneOS's catalog can neither claim our label nor our namespace.
            check(!Catalog.GrapheneOS.lists("app.example.other", null, "DiamaneOS"))
            check(!Catalog.GrapheneOS.lists("de.diamaneos.future", null, "GrapheneOS"))
            for (name in listOf("De.Diamaneos.x", "de.diamaneos", "App.GrapheneOS.Apps", "APP.ATTESTATION.AUDITOR")) {
                check(!Catalog.GrapheneOS.lists(name, null, "GrapheneOS"))
            }
            check(Catalog.GrapheneOS.lists("de.diamaneosx.other", null, "GrapheneOS"))
            check(Catalog.GrapheneOS.path == "grapheneos/metadata.1.0.sjson" && Catalog.DiamaneOS.path == CATALOG_PATH)
        }
        "signers" -> {
            // The APK check, with our three public certificates in the order of DIAMANEOS_SIGNERS.
            val certificates = args.drop(1).map { File(it).readBytes() }
            check(certificates.size == 3)
            val digests = certificates.map { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
            check(digests.map { d -> d.joinToString("") { "%02x".format(it) } } == DIAMANEOS_SIGNERS.toList())
            fun info(vararg c: ByteArray, multiple: Boolean = false, past: Boolean = false) =
                android.content.pm.SigningInfo(c.map { android.content.pm.Signature(it) }.toTypedArray(), multiple, past)
            fun refused(info: android.content.pm.SigningInfo?, listed: Array<ByteArray>) =
                try { checkDiamaneOSSigner("base.apk", "app.grapheneos.apps", info, listed);false } catch (e: java.security.GeneralSecurityException) { true }
            for (i in certificates.indices) {
                checkDiamaneOSSigner("base.apk", "app.grapheneos.apps", info(certificates[i]), arrayOf(digests[i]))
                checkDiamaneOSSigner("base.apk", "app.grapheneos.apps", info(certificates[i]), digests.toTypedArray())
                // One of ours, but not the one the entry lists.
                check(refused(info(certificates[i]), arrayOf(digests[(i + 1) % 3])))
            }
            val other = "not one of our certificates".toByteArray()
            val otherDigest = java.security.MessageDigest.getInstance("SHA-256").digest(other)
            check(refused(info(other), arrayOf(otherDigest)))
            check(refused(null, digests.toTypedArray()))
            check(refused(info(), digests.toTypedArray()))
            check(refused(info(certificates[0], certificates[1]), digests.toTypedArray()))
            check(refused(info(certificates[0], multiple = true), digests.toTypedArray()))
            check(refused(info(certificates[0], past = true), digests.toTypedArray()))
        }
        "parse_isolation" -> {
            // A signed GrapheneOS catalog this App Store cannot parse keeps our catalog's update and is not cached.
            NetworkFixture.bodies["/grapheneos/metadata.1.0.sjson"] = "unparsable\\n".toByteArray() + "X".repeat(100).toByteArray() + "\\n".toByteArray()
            try { fetchRepo(getCachedRepo());error("Expected a partial update") }
            catch (e: PartialRepoUpdate) {
                check(e.cause is IllegalArgumentException)
                check(!e.repo.parts[0].isDummy && e.repo.parts[1].isDummy)
            }
            check(AtomicFile2.files.keys == setOf("repo"))
            // A cache this App Store cannot parse starts that catalog afresh and keeps the other.
            val data = ByteArrayOutputStream()
            DataOutputStream(data).use {
                it.writeInt(3);it.writeString(GRAPHENEOS_CATALOG_KEY);it.writeString("0");it.writeString("")
                it.writeByteArray("unparsable".toByteArray())
            }
            AtomicFile2.files["repo-grapheneos"] = data.toByteArray()
            val cached = getCachedRepo()
            check(!cached.parts[0].isDummy && cached.parts[1].isDummy)
            // Its time stays the floor: an older GrapheneOS catalog is refused, ours still updates.
            check(cached.parts[1].timestamp == 1800000000L)
            JSONObject.nextTime = 1799999000L
            try { fetchRepo(cached);error("Expected a partial update") }
            catch (e: PartialRepoUpdate) {
                check(e.cause is java.security.GeneralSecurityException)
                check(e.repo.parts[1].isDummy && e.repo.parts[1].timestamp == 1800000000L)
            }
            JSONObject.nextTime = 1800000000L
            // So does a truncated one, with the catalog's own floor.
            AtomicFile2.files["repo-grapheneos"] = data.toByteArray().copyOf(6)
            check(getCachedRepo().parts[1].isDummy && getCachedRepo().parts[1].timestamp == Catalog.GrapheneOS.minTimestamp)
            // A cache of another format, 36.x's, cannot be used either, but its time stays the floor.
            val older = ByteArrayOutputStream()
            DataOutputStream(older).use {
                it.writeInt(2);it.writeString(GRAPHENEOS_CATALOG_KEY);it.writeString("0");it.writeString("")
                it.writeByteArray("{}".toByteArray())
            }
            AtomicFile2.files["repo-grapheneos"] = older.toByteArray()
            JSONObject.nextTime = 1800000500L
            check(getCachedRepo().parts[1].isDummy && getCachedRepo().parts[1].timestamp == 1800000500L)
            JSONObject.nextTime = 1800000000L
        }
        "older_copy" -> {
            // An older signed copy, for example on the other server, counts as not modified.
            val first = fetchRepo(getCachedRepo())
            val cached = AtomicFile2.files.toMap()
            JSONObject.nextTime = 1799999000L
            check(fetchRepo(first) === first)
            check(AtomicFile2.files == cached && FileVerifier.calls == 4)
            // On a phone without a catalog it is still refused below the catalog's floor.
            AtomicFile2.files.clear()
            JSONObject.nextTime = Catalog.GrapheneOS.minTimestamp - 1
            refuse { fetchRepo(getCachedRepo()) }
        }
        "partial_unchanged" -> {
            // Our catalog is current and GrapheneOS's fails: a partial update, so our packages still update.
            val first = fetchRepo(getCachedRepo())
            NetworkFixture.codes["/metadata.1.0.sjson"] = 304
            FileVerifier.refused = setOf(GRAPHENEOS_CATALOG_KEY)
            try { fetchRepo(first);error("Expected a partial update") }
            catch (e: PartialRepoUpdate) { check(e.repo === first && e.cause is java.security.GeneralSecurityException) }
        }
        "version_gates" -> {
            // GrapheneOS's gates name upstream versions: our 3603 is upstream 36.
            check(Catalog.GrapheneOS.dependencyVersion("app.grapheneos.apps", 3603) == 36L)
            check(Catalog.GrapheneOS.dependencyVersion("app.grapheneos.camera", 9304) == 93L)
            check(Catalog.GrapheneOS.dependencyVersion("app.vanadium.config", 217) == 217L)
            check(Catalog.DiamaneOS.dependencyVersion("app.grapheneos.apps", 3603) == 3603L)
            // Vanadium's gate "app.grapheneos.apps >= 22" passes, a gate for upstream 37 does not.
            check(Catalog.GrapheneOS.dependencyVersion("app.grapheneos.apps", 3603) >= 22)
            check(Catalog.GrapheneOS.dependencyVersion("app.grapheneos.apps", 3603) < 37)
        }
        "automatic_failover" -> {
            NetworkFixture.policy("automatic")
            NetworkFixture.canadaProbeFails = true
            NetworkFixture.code = 503
            fetchRepo(getCachedRepo())
            check(NetworkFixture.requests.map { it.host } == listOf("apps.diamaneos.de", "apps-na.diamaneos.de", "apps.diamaneos.de"))
            check(NetworkFixture.probes == 4 && FileVerifier.calls == 2)
        }
        "apk_resume" -> {
            NetworkFixture.policy("automatic")
            NetworkFixture.canadaProbeFails = true
            NetworkFixture.body = ByteArray(40_000) { (it * 31).toByte() }
            NetworkFixture.failAt = 10_000
            for (offset in listOf(0L, 5_000L)) {
                val out = ByteArrayOutputStream()
                val progress = java.util.concurrent.atomic.AtomicLong()
                openConnection(null, "https://apps.diamaneos.de/packages/app.fixture/1/base.apk.gz") {
                    if (offset > 0) addRequestProperty("Range", "bytes=$offset-")
                }.use { conn ->
                    check(conn.copyBody(out, offset, 40_000, progress) {} == 40_000L)
                }
                check(out.toByteArray().contentEquals(NetworkFixture.body.copyOfRange(offset.toInt(), 40_000)))
                check(progress.get() == 40_000 - offset)
                check(NetworkFixture.requests.takeLast(2).map { it.host } == listOf("apps.diamaneos.de", "apps-na.diamaneos.de"))
                check(NetworkFixture.ranges.last() == "bytes=10000-")
                NetworkFixture.opened.clear()
            }
        }
        "signature_failure" -> {
            FileVerifier.allowed = false
            refuse { fetchRepo(getCachedRepo()) }
            check(FileVerifier.calls == 2 && JSONObject.parsed == 0 && AtomicFile2.files.isEmpty())
            check(NetworkFixture.requests.size == 2)
        }
        "downgrade_failure" -> {
            JSONObject.nextTime = 1L
            refuse { fetchRepo(getCachedRepo()) }
            check(FileVerifier.calls == 2 && NetworkFixture.requests.size == 2 && AtomicFile2.files.isEmpty())
        }
        "tls_or_redirect" -> {
            NetworkFixture.policy("automatic")
            NetworkFixture.canadaProbeFails = true
            NetworkFixture.error = SSLHandshakeException("fixture")
            try { openConnection(null, "https://apps.diamaneos.de/metadata.1.0.sjson", {}) ; error("Expected refusal") }
            catch (e: SSLException) { }
            check(NetworkFixture.requests.size == 1)
        }
        "redirect" -> {
            NetworkFixture.policy("automatic")
            NetworkFixture.canadaProbeFails = true
            NetworkFixture.code = 302
            try { openConnection(null, "https://apps.diamaneos.de/metadata.1.0.sjson", {});error("Expected refusal") }
            catch (e: ProtocolException) { }
            check(NetworkFixture.requests.size == 1)
        }
        "short_metadata" -> {
            NetworkFixture.body = "fixture".toByteArray()
            refuse { fetchRepo(getCachedRepo()) }
            check(FileVerifier.calls == 0 && NetworkFixture.requests.size == 2)
        }
        "metadata_bound" -> {
            NetworkFixture.body = ByteArray(8*1024*1024 + 1)
            try { fetchRepo(getCachedRepo());error("Expected refusal") }
            catch (e: IOException) { }
            check(FileVerifier.calls == 0 && NetworkFixture.requests.size == 2)
        }
        "cache_binding" -> {
            fun entry(key: String) = ByteArrayOutputStream().also { data ->
                DataOutputStream(data).use { it.writeInt(3);it.writeString(key);it.writeString("0") }
            }.toByteArray()
            AtomicFile2.files["repo"] = entry("different fixture authority")
            // Our key never stands in for GrapheneOS's, nor the reverse.
            AtomicFile2.files["repo-grapheneos"] = entry("fixture public verifier")
            check(getCachedRepo().isDummy)
            AtomicFile2.files["repo"] = entry(GRAPHENEOS_CATALOG_KEY)
            check(getCachedRepo().isDummy && NetworkFixture.requests.isEmpty())
        }
        "icon_policy" -> {
            // The icon fields come from the catalog alone. The origin is chosen when the icon loads.
            NetworkFixture.policy("canada")
            check(IconFixture(JSONObject(), Catalog.DiamaneOS).iconPath == "packages/app.fixture/icon.png")
            check(IconFixture(JSONObject(), Catalog.GrapheneOS).iconPath == "grapheneos/packages/app.fixture/icon.png")
            check(NetworkFixture.requests.isEmpty())
            NetworkFixture.body = "fixture icon".toByteArray()
            for ((mode, host) in listOf("primary_eu" to "apps.diamaneos.de", "canada" to "apps-na.diamaneos.de")) {
                NetworkFixture.policy(mode)
                DownloadIconLoader.load("grapheneos/packages/app.fixture/icon.png", 12, ICON_SHA256)
                check(NetworkFixture.requests.last().host == host)
            }
        }
        "apk_paths" -> {
            check(ApkFixture(Catalog.DiamaneOS).downloadUrl("base.apk.br") == "https://apps.diamaneos.de/packages/app.fixture/7/base.apk.br")
            check(ApkFixture(Catalog.GrapheneOS).downloadUrl("base.apk.br") == "https://apps.diamaneos.de/grapheneos/packages/app.fixture/7/base.apk.br")
            check(NetworkFixture.requests.isEmpty())
        }
        "icons" -> {
            var failed = false
            for (path in listOf("https://evil.invalid/icon.png", "//evil.invalid/packages/app.fixture/icon.png")) {
                try { DownloadIconLoader.load(path, 12, ICON_SHA256) } catch (e: Exception) { failed = true }
                check(failed)
                failed = false
            }
            failed = true
            check(failed && NetworkFixture.requests.isEmpty())
        }
        "icon_paths" -> {
            fun load(path: String, size: Int = 12, sha256: String = ICON_SHA256): ByteArray? =
                try { DownloadIconLoader.load(path, size, sha256) } catch (e: Exception) { null }
            // Each catalog's icon, at the path Repo.kt builds, reaches the decoder.
            NetworkFixture.body = "fixture icon".toByteArray()
            for (catalog in Catalog.entries) {
                check(load(IconFixture(JSONObject(), catalog).iconPath!!)!!.contentEquals(NetworkFixture.body))
            }
            check(NetworkFixture.paths == listOf("/packages/app.fixture/icon.png", "/grapheneos/packages/app.fixture/icon.png"))
            // Only the catalogs' prefixes, and only icons.
            for (path in listOf("other/packages/app.fixture/icon.png", "grapheneos/grapheneos/packages/app.fixture/icon.png",
                    "grapheneospackages/app.fixture/icon.png", "grapheneos/packages/app.fixture/7/icon.png",
                    "grapheneos/packages/app.fixture/7/base.apk.br", "packages/app.fixture/7/base.apk.br",
                    "grapheneos/metadata.1.0.sjson", "grapheneos/packages/app.fixture/icon.png/x",
                    "grapheneos/packages/../icon.png", "GrapheneOS/packages/app.fixture/icon.png")) {
                check(!DownloadIconLoader.isIconPath(path))
                check(load(path) == null)
            }
            check(NetworkFixture.requests.size == 2)
            // The signed catalog's size and SHA-256 decide: a bigger or smaller file, another hash or a size
            // outside 1 byte to 1 MiB never reach the decoder, and no request is made for a bad size.
            val url = IconFixture(JSONObject(), Catalog.GrapheneOS).iconPath!!
            check(load(url, 11) == null && load(url, 13) == null)
            check(load(url, sha256 = "00".repeat(32)) == null)
            val requests = NetworkFixture.requests.size
            check(load(url, 0) == null && load(url, 1024 * 1024 + 1) == null && load(url, -1) == null)
            check(NetworkFixture.requests.size == requests)
            // The 1 MiB bound holds for GrapheneOS's icons too.
            val big = ByteArray(1024 * 1024)
            val bigSha = java.security.MessageDigest.getInstance("SHA-256").digest(big).joinToString("") { "%02x".format(it) }
            NetworkFixture.bodies["/grapheneos/packages/app.fixture/icon.png"] = big
            check(load(url, big.size, bigSha)!!.size == big.size)
            NetworkFixture.bodies["/grapheneos/packages/app.fixture/icon.png"] = ByteArray(1024 * 1024 + 1)
            check(load(url, big.size, bigSha) == null)
        }
        "check_report" -> {
            // A catalog that failed while the other is current is never "all up to date".
            check(checkReport(false, false) == CheckReport.ALL_UP_TO_DATE)
            check(checkReport(false, true) == CheckReport.UPDATES)
            check(checkReport(true, false) == CheckReport.FAILED_PARTLY)
            check(checkReport(true, true) == CheckReport.UPDATES_AND_FAILED_PARTLY)
        }
        "delta_fallback" -> {
            // One run: which attempts ran (delta or full) and how many fallbacks, or the failure that came out.
            fun run(useDelta: Boolean, vararg failures: Exception?): String {
                val attempts = mutableListOf<Boolean>()
                var fallbacks = 0
                return try {
                    withDeltaFallback(useDelta, { delta ->
                        attempts += delta
                        failures.getOrNull(attempts.size - 1)?.let { throw it }
                    }, { fallbacks++ })
                    "$attempts $fallbacks"
                } catch (e: Exception) {
                    "$attempts $fallbacks " + e.javaClass.simpleName
                }
            }
            val missing = try { throwResponseCodeException(FailingConnection(404)) } catch (e: ProtocolException) { e }
            check(run(true) == "[true] 0")
            check(run(false) == "[false] 0")
            // A missing delta and one with the wrong hash take the full APK in the same run.
            check(run(true, missing) == "[true, false] 1")
            check(run(true, DeltaHashException("delta")) == "[true, false] 1")
            // The full APK failing the same way is a real failure, and comes out once.
            check(run(true, missing, missing) == "[true, false] 1 ProtocolException")
            check(run(false, missing) == "[false] 0 ProtocolException")
            // A delta that does not patch to the right APK takes the full APK too.
            check(run(true, DeltaPatchException(GeneralSecurityException("patched"))) == "[true, false] 1")
            // A dropped network can fail the full APK just the same, and keeps the partial delta for the retry.
            check(run(true, SocketTimeoutException("slow")) == "[true] 0 SocketTimeoutException")
            check(run(true, EOFException("ended early")) == "[true] 0 EOFException")
            // The full APK failing its own hash is a real failure.
            check(run(true, GeneralSecurityException("full")) == "[true] 0 GeneralSecurityException")
        }
        else -> error("Unknown fixture")
    }
}
class FailingConnection(private val code: Int) : HttpURLConnection(URL("https://apps.diamaneos.de/grapheneos/x.ina")) {
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getResponseCode() = code
    override fun getResponseMessage() = "Not Found"
}
'''


class AdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='apps-download-host-')
        cls.jdk = Path(os.environ.get('DOWNLOAD_POLICY_JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        cls.kotlin = Path(os.environ.get('DOWNLOAD_POLICY_KOTLIN_HOME', '/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc'))
        if not (cls.jdk / 'bin/java').is_file() or not (cls.kotlin / 'lib/kotlin-compiler.jar').is_file():
            raise RuntimeError('Host JDK and Kotlin compiler required; no Gradle or Android build is used')
        cls.outputs = {}
        for variant, key in [('configured', 'fixture public verifier'), ('disabled', '')]:
            out = Path(cls.tmp.name) / variant
            out.mkdir()
            files = dict(JAVA)
            files['app/grapheneos/apps/BuildConfig.java'] = f'package app.grapheneos.apps; public class BuildConfig {{ public static final String REPO_PUBLIC_KEY="{key}", REPO_KEY_VERSION="0"; public static final boolean REPO_STAGING=false; }}'
            for name, body in files.items():
                p = out / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(body)
            src = ROOT / 'app/src/main/java'
            java = list(out.rglob('*.java')) + list((src / 'de/diamaneos/downloads').glob('*.java'))
            subprocess.run([str(cls.jdk / 'bin/javac'), '--release', '17', '-d', str(out)] + [str(p) for p in java], capture_output=True, text=True, check=True, timeout=30)
            repo_source = (src / 'app/grapheneos/apps/core/Repo.kt').read_text()
            begin_base = repo_source.index('val REPO_BASE_URL: String')
            end_base = repo_source.index('\nclass Repo(', begin_base)
            core = CORE.replace('const val REPO_BASE_URL = "https://apps.diamaneos.de"', repo_source[begin_base:end_base].strip())
            for name, body in [('Core.kt', core), ('Storage.kt', STORAGE), ('Harness.kt', HARNESS)]:
                (out / name).write_text(body)
            # Compile the actual changed icon field/getter against its constructor parameter scope.
            repo_source = (src / 'app/grapheneos/apps/core/Repo.kt').read_text()
            begin = repo_source.index('    private val iconType =')
            end = repo_source.index('\n    // Used for setting release channel', begin)
            icon_source = 'package app.grapheneos.apps.core\nclass IconFixture(json: org.json.JSONObject, val catalog: Catalog) {\n val manifestPackageName = "app.fixture"\n' + repo_source[begin:end] + '\n}\n'
            (out / 'IconFixture.kt').write_text(icon_source)
            # And the actual APK download path.
            download = re.search(r'^    fun downloadUrl\(fileName: String\) = .*$', repo_source, re.M)[0]
            (out / 'ApkFixture.kt').write_text('package app.grapheneos.apps.core\nclass ApkFixture(catalog: Catalog) {\n'
                ' class Common(val catalog: Catalog)\n class Pkg(c: Catalog) { val common = Common(c); val manifestPackageName = "app.fixture"; val versionCode = 7L }\n'
                ' val pkg = Pkg(catalog)\n val name = "base.apk"\n' + download + '\n}\n')
            kotlin = [out / p for p in ('Core.kt', 'Storage.kt', 'Harness.kt', 'IconFixture.kt', 'ApkFixture.kt')]
            kotlin += [src / 'app/grapheneos/apps' / p for p in ('util/HttpUtils.kt', 'util/DownloadIconLoader.kt', 'util/DataStreamUtils.kt', 'core/RepoRetriever.kt', 'core/Catalogs.kt', 'core/DeltaFallback.kt')]
            cmd = [str(cls.jdk / 'bin/java'), '-cp', str(cls.kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-kotlin-home', str(cls.kotlin), '-jvm-target', '17', '-classpath', str(out), '-d', str(out)] + [str(p) for p in kotlin]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=45)
            if result.returncode: raise RuntimeError(result.stderr)
            cls.outputs[variant] = out

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def scenario(self, name, variant='configured', *args):
        cp = str(self.outputs[variant]) + os.pathsep + str(self.kotlin / 'lib/kotlin-stdlib.jar')
        result = subprocess.run([str(self.jdk / 'bin/java'), '-cp', cp, 'app.grapheneos.apps.core.HarnessKt', name, *args], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_key_has_no_network_or_cache_effect(self): self.scenario('missing_key', 'disabled')
    def test_authority_failure_never_contacts_either_region(self): self.scenario('policy_unavailable')
    def test_shared_region_and_verifier_calls(self): self.scenario('selected_region')
    def test_each_catalog_has_its_own_path_key_and_cache(self): self.scenario('catalog_keys')
    def test_one_catalog_failing_keeps_the_others_update(self): self.scenario('partial_update')
    def test_unchanged_catalogs_keep_the_repo(self): self.scenario('not_modified')
    def test_catalog_package_scopes_are_disjoint(self): self.scenario('scope')
    def test_apk_paths_follow_the_catalog(self): self.scenario('apk_paths')

    def test_our_apks_need_our_listed_signer(self):
        # tests/signers.pem: the public App Store, Camera and Auditor certificates.
        pem = (ROOT / 'tests/signers.pem').read_text()
        paths = []
        for i, block in enumerate(re.findall(r'-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----', pem, re.S)):
            paths.append(Path(self.tmp.name) / f'signer{i}.der')
            paths[-1].write_bytes(base64.b64decode(''.join(block.split())))
        self.scenario('signers', 'configured', *map(str, paths))
    def test_unparsable_catalog_never_breaks_the_other_or_the_cache(self): self.scenario('parse_isolation')
    def test_older_copy_counts_as_not_modified(self): self.scenario('older_copy')
    def test_failure_with_a_current_catalog_is_partial(self): self.scenario('partial_unchanged')
    def test_grapheneos_version_gates_use_upstream_versions(self): self.scenario('version_gates')
    def test_automatic_moves_to_other_server(self): self.scenario('automatic_failover')
    def test_apk_download_resumes_on_other_server(self): self.scenario('apk_resume')
    def test_signature_failure_never_parses_caches_or_falls_back(self): self.scenario('signature_failure')
    def test_downgrade_never_caches_or_falls_back(self): self.scenario('downgrade_failure')
    def test_tls_failure_not_retried(self): self.scenario('tls_or_redirect')
    def test_redirect_never_retried(self): self.scenario('redirect')
    def test_short_catalog_never_parsed_or_retried(self): self.scenario('short_metadata')
    def test_metadata_bound_before_verifier(self): self.scenario('metadata_bound')
    def test_cached_catalog_tied_to_public_authority(self): self.scenario('cache_binding')
    def test_icon_origin_tracks_shared_policy(self): self.scenario('icon_policy')
    def test_icon_external_origin_refused(self): self.scenario('icons')
    def test_icons_of_both_catalogs_only(self): self.scenario('icon_paths')
    def test_a_partial_failure_is_reported_never_as_up_to_date(self): self.scenario('check_report')
    def test_a_missing_or_wrong_delta_takes_the_full_apk(self): self.scenario('delta_fallback')

    def test_install_task_uses_the_delta_fallback(self):
        install = (ROOT / 'app/src/main/java/app/grapheneos/apps/core/InstallTask.kt').read_text()
        self.assertEqual(1, install.count('withDeltaFallback(deltaBaseFd != null,'))
        self.assertEqual(1, install.count('throw DeltaHashException('))
        self.assertEqual(1, install.count('throw DeltaPatchException('))
        # The base APK check and the fallback each stop deltas for this version.
        self.assertEqual(2, install.count('packageState.ignoreDeltasForVersion(currentVersion!!)'))

    def test_fixed_pinned_domains_and_offline_catalog_key_default(self):
        doc = ET.parse(ROOT / 'app/src/main/res/xml/network_security_config.xml').getroot()
        config = doc.find('domain-config')
        self.assertEqual({x.text for x in config.findall('domain')}, {'apps.diamaneos.de', 'apps-na.diamaneos.de', 'apps.staging.diamaneos.de'})
        self.assertTrue(config.findall('pin-set/pin'))
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertNotIn('REPO_BASE_URL', gradle)
        key = re.search(r'val catalogKey = System.getenv\("REPO_PUBLIC_KEY"\)\s*\?: "([^"]+)"', gradle)
        self.assertIsNotNone(key)
        decoded = base64.b64decode(key[1], validate=True)
        self.assertEqual(42, len(decoded))
        self.assertEqual(b'Ed' + bytes.fromhex('4cefb04a1cf3d79c'), decoded[:10])
        self.assertIn('applicationId = "app.grapheneos.apps"', gradle)
        # GrapheneOS's key is refused for our catalog and used only for GrapheneOS's catalog.
        catalogs = (ROOT / 'app/src/main/java/app/grapheneos/apps/core/Catalogs.kt').read_text()
        grapheneos = re.search(r'const val GRAPHENEOS_CATALOG_KEY = "([^"]+)"', catalogs)[1]
        self.assertEqual(b'Ed' + bytes.fromhex('2d67012ed5baf5'), base64.b64decode(grapheneos, validate=True)[:9])
        self.assertNotEqual(key[1], grapheneos)
        self.assertIn(f'require(catalogKey != "{grapheneos}")', gradle)
        sources = ROOT / 'app/src/main/java/app/grapheneos/apps'
        self.assertEqual(1, sum(f.read_text().count(grapheneos) for f in sources.rglob('*.kt')))
        install = (sources / 'core/InstallTask.kt').read_text()
        self.assertIn('$REPO_BASE_URL/${rPackage.common.catalog.prefix}packages/', install)
        self.assertNotIn('$REPO_BASE_URL/packages/', install)


    def test_background_checks_use_the_report(self):
        jobs = ROOT / 'app/src/main/java/app/grapheneos/apps/autoupdate'
        for name in ('AutoUpdateJob.kt', 'UpdateCheckJob.kt'):
            text = (jobs / name).read_text()
            self.assertIn('checkReport(repoUpdateError != null, ', text)
            self.assertIn('showPartialFailure(report, repoUpdateError)', text)
            # The only caller of "all up to date" is the branch of that report.
            self.assertEqual(1, len(re.findall(r'(?<!fun )showAllUpToDateNotification\(\)', text)))
            self.assertRegex(text, r'CheckReport\.ALL_UP_TO_DATE\)?\s*(?:->|\{)\s*showAllUpToDateNotification\(\)')

    def test_every_glide_load_takes_a_verified_url(self):
        # Nothing loads a plain URL or string any more, so every icon passes the catalog's size and SHA-256.
        sources = list((ROOT / 'app/src/main/java').rglob('*.kt'))
        loads = 0
        for source in sources:
            text = source.read_text()
            for call in re.finditer(r'Glide\s*\.with\([^)]*\)\s*\.load\(([^)]*)\)', text):
                loads += 1
                argument = call.group(1).strip()
                self.assertTrue(re.fullmatch(r'\w+', argument), f'{source.name}: {argument}')
                self.assertRegex(text, rf'val {argument} = VerifiedUrl\(', source.name)
            self.assertNotRegex(text, r'\bGlide\b[^\n]*\.load\(\s*"')
        self.assertGreaterEqual(loads, 1)
        # Any other file that starts a Glide request would have to be named here.
        self.assertEqual(['PackageListAdapter.kt'], [f.name for f in sources if 'Glide.with(' in f.read_text()])


if __name__ == '__main__': unittest.main()
