# Fuzzing

`../tests/jvm` compiles the App Store's own catalog, download and install sources against small
Android stand-ins, with the JNI code of `ina` built for the host, and runs them on a plain JVM.
Catalogs are signed with a throwaway test key made at build time, so inputs get past the signature.
Set `HARNESS_TEST_KEY` to the `test-key.bin` of an earlier build to replay its signed corpus.

```bash
python3 ../tests/jvm/fetch_deps.py <deps dir>
./run_jvm.sh <deps dir> <work dir> <seconds per target>
```

The Jazzer targets are in `../tests/jvm/harness/Targets.kt`: `catalog_bytes` (any bytes before the
signature check), `signed_catalog`, `catalog_sequence`, `install_flow`, `transport` and `cache`.
The same properties run as fixed checks with the unit tests:

```bash
FUZZ_DEPS=<deps dir> python3 -m unittest tests/test_jvm_properties.py
```

Corpora and findings stay in the work dir. The harness itself sends nothing over the network, both
download servers are in memory. Only `fetch_deps.py` and cargo download the pinned test dependencies.
