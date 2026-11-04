"""Builds the JVM harness: the app's catalog, delta and install code against Android stand-ins, the
JNI code of ina for the host, the install fixtures and a throwaway test key.

build.py <deps dir> <out dir> [source root]

The source root holds app/ and ina/ (default: this checkout), so an older tree can be tested with
the same harness. Needs a JDK (DOWNLOAD_POLICY_JAVA_HOME or JAVA_HOME), cargo and a C compiler.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fetch_deps  # noqa: E402

CORE = ['Repo.kt', 'Catalogs.kt', 'RepoRetriever.kt', 'FileVerifier.kt', 'DeltaFallback.kt', 'InstallTask.kt',
        'PackageState.kt']
UTIL = ['DataStreamUtils.kt', 'HttpUtils.kt', 'Utils.kt', 'FdUtils.kt', 'AtomicFile2.kt']
# Android's FileInputStream and FileOutputStream on a descriptor do not own it, OpenJDK's close it.
FD_STREAMS = {'Utils.kt', 'InstallTask.kt'}


def jdk():
    home = os.environ.get('DOWNLOAD_POLICY_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if home:
        return Path(home)
    return Path(shutil.which('javac')).resolve().parents[1]


def run(cmd, **kw):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True, **kw)
    if r.returncode:
        raise RuntimeError(f'{cmd[0]} failed:\n{r.stdout}\n{r.stderr}')
    return r.stdout


def snippet(text, start, end):
    i = text.index(start)
    return text[i:text.index(end, i) + len(end)]


def classpath(deps):
    for name, digest, _ in fetch_deps.entries():
        if not fetch_deps.check(deps / name, digest):
            raise RuntimeError(f'{name} is missing or does not match deps.lock, run fetch_deps.py')
    jars = [deps / n for n in ('bcprov-jdk18on-1.86.jar', 'android-json-0.0.20131108.vaadin1.jar',
                               'jazzer-api-0.30.0.jar')]
    return jars + [deps / 'kotlinc/lib' / j for j in ('kotlinx-coroutines-core-jvm.jar', 'kotlin-stdlib.jar')]


def build(deps, out, root):
    app = root / 'app/src/main/java'
    java_home = jdk()
    cp = classpath(deps)
    if out.exists():
        shutil.rmtree(out)
    classes, gen, native, signatures = out / 'classes', out / 'gen', out / 'native', out / 'compileonly'
    for d in (classes, gen, native, signatures):
        d.mkdir(parents=True)

    # Java: stand-ins, the key tool, the download policy and transport of the app
    java = sorted((HERE / 'stubs').rglob('*.java')) + sorted((HERE / 'harness').glob('*.java'))
    java += sorted((app / 'de/diamaneos/downloads').glob('*.java'))
    javac = [java_home / 'bin/javac', '--release', '17', '-nowarn', '-d', classes, '-cp', os.pathsep.join(map(str, cp))]
    run(javac + java)
    run(javac[:5] + [signatures] + sorted((HERE / 'compileonly').rglob('*.java')))
    key = out / 'test-key.bin'
    # A throwaway key from an earlier build may be reused, so its signed files replay.
    if os.environ.get('HARNESS_TEST_KEY'):
        shutil.copy(os.environ['HARNESS_TEST_KEY'], key)
    public = run([java_home / 'bin/java', '-cp', os.pathsep.join(map(str, [classes] + cp)), 'harness.KeyGen', key])

    texts = {name: (app / 'app/grapheneos/apps/core' / name).read_text() for name in CORE}
    texts.update({name: (app / 'app/grapheneos/apps/util' / name).read_text() for name in UTIL})
    strings = sorted(set(re.findall(r'R\.string\.(\w+)', ''.join(texts.values()))))
    (gen / 'R.java').write_text('package app.grapheneos.apps; public final class R { public static final class string {\n'
                                + ''.join(f'public static final int {s} = {i + 1};\n' for i, s in enumerate(strings)) + '} }\n')
    (gen / 'BuildConfig.java').write_text('package app.grapheneos.apps; public final class BuildConfig {\n'
                                          f'public static final String REPO_PUBLIC_KEY = "{public}";\n'
                                          'public static final String REPO_KEY_VERSION = "0";\n'
                                          'public static final boolean REPO_STAGING = false; }\n')
    run(javac + [gen / 'R.java', gen / 'BuildConfig.java'])

    # Kotlin: the app's sources, unchanged apart from the stream imports, and parts of three others
    kotlin = []
    for name, text in texts.items():
        if name in FD_STREAMS:
            text = text.replace('import java.io.FileInputStream', 'import jvmshim.FileInputStream')
            text = text.replace('import java.io.FileOutputStream', 'import jvmshim.FileOutputStream')
        (gen / name).write_text(text)
        kotlin.append(gen / name)
    core = app / 'app/grapheneos/apps/core'
    deps_text = (core / 'Dependencies.kt').read_text()
    start_text = (core / 'InstallStart.kt').read_text()
    errors_text = (core / 'Errors.kt').read_text()
    (gen / 'Extracted.kt').write_text(
        'package app.grapheneos.apps.core\nimport android.net.Network\nimport android.util.ArraySet\nimport android.util.Log\n'
        + snippet(deps_text, 'class Dependency(', '\n}\n') + '\n'
        + snippet(start_text, 'class InstallParams(', '\n)\n') + '\n'
        + snippet(errors_text, 'class InvalidDeltaBaseApkException', '\n') + '\n')
    kotlin.append(gen / 'Extracted.kt')
    kotlin.append(root / 'ina/android/src/main/java/app/accrescent/ina/Ina.kt')
    kotlin += sorted((HERE / 'stubs').glob('*.kt')) + sorted((HERE / 'harness').glob('*.kt'))
    kotlinc = deps / 'kotlinc'
    run([java_home / 'bin/java', '-cp', kotlinc / 'lib/*', 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-kotlin-home', kotlinc, '-jvm-target', '17', '-nowarn', '-Xsuppress-version-warnings',
         '-classpath', os.pathsep.join(map(str, [signatures, classes] + cp)), '-d', classes] + kotlin)

    # Native: libnativehelper stand-in, ina's JNI code built for the host, fixtures
    run(['cc', '-shared', '-fPIC', '-O2', f'-I{java_home}/include', f'-I{java_home}/include/linux', '-o',
         native / 'libnativehelper.so', HERE / 'native/nativehelper.c'])
    crate = out / 'native-src'
    shutil.copytree(HERE / 'native', crate)
    ina = (root / 'ina/ina').resolve()
    for f in (crate / 'Cargo.toml', crate / 'src/lib.rs'):
        f.write_text(f.read_text().replace('../../../../ina/ina', str(ina)).replace('../../../ina/ina', str(ina)))
    lock = HERE / 'native/Cargo.lock'
    env = dict(os.environ, RUSTFLAGS=f'-L {native} -C link-arg=-Wl,-rpath,$ORIGIN')
    target = Path(os.environ.get('HARNESS_CARGO_TARGET', out / 'cargo-target'))
    cargo = ['cargo', 'build', '--release', '--manifest-path', crate / 'Cargo.toml', '--target-dir', target]
    if lock.exists():
        cargo.append('--locked')
    run(cargo, env=env)
    shutil.copy(target / 'release/libina_host.so', native / 'libina.so')
    run(['cargo', 'run', '-q', '--release', '--manifest-path', crate / 'Cargo.toml', '--target-dir', target,
         '--example', 'make_fixtures', out / 'fixtures'], env=env)

    (out / 'classpath.txt').write_text(os.pathsep.join(map(str, [classes] + cp)))
    (out / 'source.txt').write_text(hashlib.sha256(b''.join(
        p.read_bytes() for p in kotlin + java)).hexdigest())


def java_command(out, main, *args, root_dir):
    """The command that runs a harness class with the build in out."""
    cp = (out / 'classpath.txt').read_text()
    return [str(jdk() / 'bin/java'), '-Xss4m', '-Xmx2g', f'-Djava.library.path={out / "native"}',
            f'-Dfixture.root={root_dir}', f'-Dfixture.key={out / "test-key.bin"}', f'-Dfixture.files={out / "fixtures"}',
            '--add-opens', 'java.base/java.io=ALL-UNNAMED', '-cp', cp, main, *map(str, args)]


if __name__ == '__main__':
    build(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(),
          Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else HERE.parents[1])
