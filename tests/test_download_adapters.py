"""Compile isolated JVM adapters with explicit API/verifier doubles; never Android or crypto acceptance."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = {
    'android/Manifest.java': 'package android; public class Manifest { public static class permission { public static final String WRITE_SECURE_SETTINGS="android.permission.WRITE_SECURE_SETTINGS"; } }',
    'android/os/UserManager.java': 'package android.os; public class UserManager { public boolean isSystemUser(){return false;} }',
    'android/provider/Settings.java': 'package android.provider; public class Settings { public static class Global {\n        public static String value;\n        public static String getString(android.content.ContentResolver r,String k){if(!k.equals("diamaneos_download_server_policy"))throw new AssertionError();return value;}\n        public static boolean putString(android.content.ContentResolver r,String k,String v){throw new SecurityException("Unprivileged fixture");} } }',
    'android/net/Network.java': '''package android.net; public class Network {
        public java.net.URLConnection openConnection(java.net.URL u) throws java.io.IOException {return u.openConnection();} }''',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int SIGNATURE_MATCH=0,PERMISSION_GRANTED=0; public int checkSignatures(String a,String b){return 0;} }',
    'android/content/Context.java': 'package android.content; public class Context {\n        public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}\n        public ContentResolver getContentResolver(){return new ContentResolver();}\n        public <T> T getSystemService(Class<T> t){return t.cast(new android.os.UserManager());}\n        public int checkSelfPermission(String p){return -1;} }',
    'android/content/ContentResolver.java': 'package android.content; public class ContentResolver {}',
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
        public static long nextTime=1770000001L; public static int parsed=0; public long time=nextTime;
        public JSONObject(){} public JSONObject(String s){parsed++;}
        public Object opt(String k){return "png";} public JSONObject put(String k,Object v){if(k.equals("time"))time=((Number)v).longValue();return this;} }''',
    'com/bumptech/glide/Priority.java': 'package com.bumptech.glide; public enum Priority { NORMAL }',
    'com/bumptech/glide/load/DataSource.java': 'package com.bumptech.glide.load; public enum DataSource { REMOTE }',
    'com/bumptech/glide/load/Options.java': 'package com.bumptech.glide.load; public class Options {}',
    'com/bumptech/glide/load/data/DataFetcher.java': '''package com.bumptech.glide.load.data; public interface DataFetcher<T> {
        interface DataCallback<T> {void onDataReady(T data);void onLoadFailed(Exception e);}
        void loadData(com.bumptech.glide.Priority p,DataCallback<? super T> c);void cleanup();void cancel();Class<T> getDataClass();com.bumptech.glide.load.DataSource getDataSource(); }''',
    'com/bumptech/glide/load/model/GlideUrl.java': '''package com.bumptech.glide.load.model; public class GlideUrl {
        String v; public GlideUrl(String v){this.v=v;} public String toStringUrl(){return v;} }''',
    'com/bumptech/glide/load/model/ModelLoader.java': '''package com.bumptech.glide.load.model; public interface ModelLoader<M,D> {
        class LoadData<D> { public final com.bumptech.glide.load.data.DataFetcher<D> fetcher;
        public LoadData(Object k,com.bumptech.glide.load.data.DataFetcher<D> f){fetcher=f;} }
        boolean handles(M m);LoadData<D> buildLoadData(M m,int w,int h,com.bumptech.glide.load.Options o); }''',
    'com/bumptech/glide/load/model/ModelLoaderFactory.java': '''package com.bumptech.glide.load.model; public interface ModelLoaderFactory<M,D> {
        ModelLoader<M,D> build(MultiModelLoaderFactory f);void teardown(); }''',
    'com/bumptech/glide/load/model/MultiModelLoaderFactory.java': 'package com.bumptech.glide.load.model; public class MultiModelLoaderFactory {}',
    'com/bumptech/glide/Glide.java': '''package com.bumptech.glide; public class Glide {
        public static Glide get(android.content.Context c){return new Glide();} public Registry getRegistry(){return new Registry();}
        public static class Registry { public <M,D> void replace(Class<M> m,Class<D> d,com.bumptech.glide.load.model.ModelLoaderFactory<M,D> f){} } }''',
}
CORE = '''package app.grapheneos.apps.core
import android.content.Context
import org.json.JSONObject
import java.security.GeneralSecurityException
val appContext = Context()
const val REPO_BASE_URL = "https://apps.diamaneos.de"
class Repo(json: JSONObject, val eTag: String, val isDummy: Boolean = false) { val timestamp = json.time }
// Explicit verifier double: verifies call ordering and failure propagation, never cryptography.
class FileVerifier(key: String) {
    init { if (key.isEmpty()) throw GeneralSecurityException("fixture key absent") }
    fun verifySignature(message: ByteArray, signature: String) {
        calls++
        if (!allowed) throw GeneralSecurityException("fixture signature refused")
    }
    companion object { var allowed = true; var calls = 0 }
}
'''
STORAGE = '''package app.grapheneos.apps.util
class AtomicFile2(name: String) {
    fun write(v: ByteArray) { bytes=v }
    fun read(): ByteArray? { reads++;return bytes }
    companion object { var bytes: ByteArray?=null;var reads=0 }
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

object NetworkFixture {
    val requests = mutableListOf<URL>()
    var code = 200
    var error: IOException? = null
    var body = "fixture catalog JSON\\n".toByteArray() + "X".repeat(100).toByteArray() + "\\n".toByteArray()
    fun install() {
        // Keep TLS entirely synthetic, including HttpsURLConnection's constructor default.
        HttpsURLConnection.setDefaultSSLSocketFactory(org.grapheneos.tls.ModernTLSSocketFactory())
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") throw AssertionError("No other protocol in fixture")
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host in setOf("apps.diamaneos.de", "apps-na.diamaneos.de"))
                    requests += url
                    return object : HttpsURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int {
                            check(!instanceFollowRedirects)
                            if (requests.size == 1) error?.let { throw it }
                            return if(requests.size == 1) code else 200
                        }
                        override fun getInputStream(): InputStream = body.inputStream()
                        override fun getCipherSuite(): String = throw UnsupportedOperationException()
                        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
                        override fun getServerCertificates(): Array<java.security.cert.Certificate> = throw UnsupportedOperationException()
                    }
                }
            }
        }
    }
    fun policy(region: String, fallback: Boolean) {
        android.provider.Settings.Global.value = DownloadPolicy.require(1,region,fallback).encode()
    }
}
fun refuse(action: () -> Unit) {
    try { action() } catch (e: java.security.GeneralSecurityException) { return }
    throw AssertionError("Expected verification refusal")
}
fun main(args: Array<String>) {
    NetworkFixture.install()
    NetworkFixture.policy("primary_eu", true)
    when(args[0]) {
        "missing_key" -> {
            check(getCachedRepo().isDummy)
            refuse { fetchRepo(Repo(JSONObject(), "fixture", true)) }
            check(NetworkFixture.requests.isEmpty() && AtomicFile2.reads == 0)
        }
        "selected_region" -> {
            NetworkFixture.policy("canada", false)
            val result = fetchRepo(Repo(JSONObject(), "fixture", true))
            check(result.timestamp == 1770000001L)
            check(NetworkFixture.requests.single().host == "apps-na.diamaneos.de")
            check(FileVerifier.calls == 1 && JSONObject.parsed == 1)
        }
        "fallback_once" -> {
            NetworkFixture.code = 503
            fetchRepo(Repo(JSONObject(), "fixture", true))
            check(NetworkFixture.requests.map { it.host } == listOf("apps.diamaneos.de", "apps-na.diamaneos.de"))
            check(FileVerifier.calls == 1)
        }
        "signature_failure" -> {
            FileVerifier.allowed = false
            refuse { fetchRepo(Repo(JSONObject(), "fixture", true)) }
            check(FileVerifier.calls == 1 && JSONObject.parsed == 0 && AtomicFile2.bytes == null)
            check(NetworkFixture.requests.size == 1)
        }
        "downgrade_failure" -> {
            JSONObject.nextTime = 1L
            refuse { fetchRepo(Repo(JSONObject(), "fixture", true)) }
            check(FileVerifier.calls == 1 && NetworkFixture.requests.size == 1 && AtomicFile2.bytes == null)
        }
        "tls_or_redirect" -> {
            NetworkFixture.error = SSLHandshakeException("fixture")
            try { openConnection(null, "https://apps.diamaneos.de/metadata.1.0.sjson", {}) ; error("Expected refusal") }
            catch (e: SSLException) { }
            check(NetworkFixture.requests.size == 1)
        }
        "redirect" -> {
            NetworkFixture.code = 302
            try { openConnection(null, "https://apps.diamaneos.de/metadata.1.0.sjson", {});error("Expected refusal") }
            catch (e: ProtocolException) { }
            check(NetworkFixture.requests.size == 1)
        }
        "short_metadata" -> {
            NetworkFixture.body = "fixture".toByteArray()
            refuse { fetchRepo(Repo(JSONObject(), "fixture", true)) }
            check(FileVerifier.calls == 0 && NetworkFixture.requests.size == 1)
        }
        "metadata_bound" -> {
            NetworkFixture.body = ByteArray(8*1024*1024 + 1)
            try { fetchRepo(Repo(JSONObject(), "fixture", true));error("Expected refusal") }
            catch (e: IOException) { }
            check(FileVerifier.calls == 0 && NetworkFixture.requests.size == 1)
        }
        "cache_binding" -> {
            val data = ByteArrayOutputStream()
            DataOutputStream(data).use { it.writeInt(2);it.writeString("different fixture authority");it.writeString("0") }
            AtomicFile2.bytes = data.toByteArray()
            check(getCachedRepo().isDummy && NetworkFixture.requests.isEmpty())
        }
        "icon_policy" -> {
            val icon = IconFixture(JSONObject())
            check(icon.iconUrl == "https://apps.diamaneos.de/packages/app.fixture/icon.png")
            NetworkFixture.policy("canada", false)
            check(icon.iconUrl == "https://apps-na.diamaneos.de/packages/app.fixture/icon.png")
            check(NetworkFixture.requests.isEmpty())
        }
        "icons" -> {
            val loader = DownloadIconLoader()
            val model = com.bumptech.glide.load.model.GlideUrl("https://evil.invalid/icon.png")
            var failed = false
            loader.buildLoadData(model,1,1,com.bumptech.glide.load.Options()).fetcher.loadData(
                com.bumptech.glide.Priority.NORMAL, object : com.bumptech.glide.load.data.DataFetcher.DataCallback<InputStream> {
                    override fun onDataReady(value: InputStream?) { error("Unapproved icon reached decoder") }
                    override fun onLoadFailed(error: Exception) { failed = true }
                })
            check(failed && NetworkFixture.requests.isEmpty())
        }
        else -> error("Unknown fixture")
    }
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
            files['app/grapheneos/apps/BuildConfig.java'] = f'package app.grapheneos.apps; public class BuildConfig {{ public static final String REPO_PUBLIC_KEY="{key}", REPO_KEY_VERSION="0"; }}'
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
            icon_source = 'package app.grapheneos.apps.core\nclass IconFixture(json: org.json.JSONObject) {\n val manifestPackageName = "app.fixture"\n' + repo_source[begin:end] + '\n}\n'
            (out / 'IconFixture.kt').write_text(icon_source)
            kotlin = [out / p for p in ('Core.kt', 'Storage.kt', 'Harness.kt', 'IconFixture.kt')]
            kotlin += [src / 'app/grapheneos/apps' / p for p in ('util/HttpUtils.kt', 'util/DownloadIconLoader.kt', 'util/DataStreamUtils.kt', 'core/RepoRetriever.kt')]
            cmd = [str(cls.jdk / 'bin/java'), '-cp', str(cls.kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-kotlin-home', str(cls.kotlin), '-jvm-target', '17', '-classpath', str(out), '-d', str(out)] + [str(p) for p in kotlin]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=45)
            if result.returncode: raise RuntimeError(result.stderr)
            cls.outputs[variant] = out

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def scenario(self, name, variant='configured'):
        cp = str(self.outputs[variant]) + os.pathsep + str(self.kotlin / 'lib/kotlin-stdlib.jar')
        result = subprocess.run([str(self.jdk / 'bin/java'), '-cp', cp, 'app.grapheneos.apps.core.HarnessKt', name], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_key_has_no_network_or_cache_effect(self): self.scenario('missing_key', 'disabled')
    def test_shared_region_and_verifier_calls(self): self.scenario('selected_region')
    def test_one_opted_in_fallback(self): self.scenario('fallback_once')
    def test_signature_failure_never_parses_caches_or_falls_back(self): self.scenario('signature_failure')
    def test_downgrade_never_caches_or_falls_back(self): self.scenario('downgrade_failure')
    def test_tls_failure_not_retried(self): self.scenario('tls_or_redirect')
    def test_redirect_never_retried(self): self.scenario('redirect')
    def test_short_catalog_never_parsed_or_retried(self): self.scenario('short_metadata')
    def test_metadata_bound_before_verifier(self): self.scenario('metadata_bound')
    def test_cached_catalog_tied_to_public_authority(self): self.scenario('cache_binding')
    def test_icon_origin_tracks_shared_policy(self): self.scenario('icon_policy')
    def test_icon_external_origin_refused(self): self.scenario('icons')

    def test_fixed_pinned_domains_and_no_catalog_key_default(self):
        doc = ET.parse(ROOT / 'app/src/main/res/xml/network_security_config.xml').getroot()
        config = doc.find('domain-config')
        self.assertEqual({x.text for x in config.findall('domain')}, {'apps.diamaneos.de', 'apps-na.diamaneos.de'})
        self.assertTrue(config.findall('pin-set/pin'))
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertNotIn('REPO_BASE_URL', gradle)
        self.assertIn('System.getenv("REPO_PUBLIC_KEY") ?: ""', gradle)
        self.assertIn('applicationId = "app.grapheneos.apps"', gradle)


if __name__ == '__main__': unittest.main()
