"""Builds the JVM harness of tests/jvm and runs its property and regression checks on the app's own
catalog, delta and install code, with ina's JNI code built for the host. Needs a JDK, cargo, a C
compiler and the files of tests/jvm/deps.lock in the folder FUZZ_DEPS names (tests/jvm/fetch_deps.py)."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / 'jvm'))
import build  # noqa: E402

PROPERTIES = [
    'signed_catalog_byte_changes', 'catalog_signature_and_rollback_model', 'catalog_paths_stay_in_the_app',
    'delta_failures_fall_back', 'full_apk_failures_never_install', 'wrong_bytes_never_install',
    'channels_and_versions', 'cache_file_corruption', 'transport_bounds', 'delta_fallback_unit',
]


class PropertyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        deps = os.environ.get('FUZZ_DEPS')
        if not deps:
            raise RuntimeError('FUZZ_DEPS must name a folder with the files of tests/jvm/deps.lock')
        cls.tmp = tempfile.TemporaryDirectory(prefix='apps-properties-')
        cls.out = Path(cls.tmp.name) / 'build'
        build.build(Path(deps).resolve(), cls.out, HERE.parent)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def check(self, name):
        root = Path(self.tmp.name) / f'root-{name}'
        root.mkdir()
        result = subprocess.run(build.java_command(self.out, 'harness.PropertiesKt', name, root_dir=root),
                                capture_output=True, text=True, timeout=1200)
        self.assertEqual(result.returncode, 0, result.stderr[-3000:])


for _name in PROPERTIES:
    setattr(PropertyTests, f'test_{_name}', lambda self, n=_name: self.check(n))

if __name__ == '__main__':
    unittest.main()
