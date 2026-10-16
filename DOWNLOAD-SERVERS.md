# Download servers

Apps reads the shared Settings.Global policy and links to Network & internet. Europe is the default. Canada and fallback are optional. Catalogs, packages and icons use fixed HTTPS Apps origins. Redirects and TLS failures are refused.

A missing or unknown policy selects Europe with fallback off. If the policy cannot be read, no server is contacted.

Sources in `app/src/main/java/de/diamaneos/downloads` match Settings' `DiamaneOSDownloadPolicyClient`. Reads use the public SDK without extra permissions. Settings' main-user controls use the existing `WRITE_SECURE_SETTINGS`, and trusted platform writers keep device-wide control.

`REPO_PUBLIC_KEY` supplies the independently approved catalog key. There is no inherited key or configurable origin. `REPO_KEY_VERSION` is bounded decimal text. Missing keys disable network and cached-catalog use. Signature, timestamp, hash and APK identity checks remain required. Caches bind the key and version.

Run `python3 -m unittest discover -s tests -p 'test_download_adapters.py'`. Host JDK/Kotlin compilers are selected through `DOWNLOAD_POLICY_JAVA_HOME` and `DOWNLOAD_POLICY_KOTLIN_HOME`. The tests use stand-ins for Android, TLS, JSON and the verifier, so they do not test Android or the cryptography. The inherited namespace is `app.grapheneos.apps`.
