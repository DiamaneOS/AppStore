# Download servers

Apps reads the shared Settings.Global policy and links to Network & internet. Automatic is the default. Europe only and Canada only use one server. Catalogs, packages and icons use fixed HTTPS Apps origins.

Missing or unknown policy selects Automatic. Authority read failures stop requests before contacting a server.

Automatic sends HEAD for the catalog to both servers and starts on the faster. A connection or temporary server failure before the body moves to the other server. An APK download that fails or stalls mid-way continues there from the same offset, with the exact `Content-Range` of a file of the same size.

Redirects and TLS failures are refused and never move to the other server. Signature, timestamp, hash and APK identity checks remain required.

Sources in `app/src/main/java/de/diamaneos/downloads` match Settings' `DiamaneOSDownloadPolicyClient`. Reads use the public SDK without extra permissions. Settings' main-user controls use existing `WRITE_SECURE_SETTINGS`. Trusted platform writers retain device-wide control.

Apps trusts two signed catalogs. Our catalog (`metadata.1.0.sjson`) may list only our App Store, Camera and Auditor. GrapheneOS's catalog, served unchanged under `grapheneos/`, is checked with GrapheneOS's key and may list only GrapheneOS's other packages, without its Info and Auditor apps (`core/Catalogs.kt`). Each catalog has its own downgrade check and cache. A failure of one keeps the other's update.

`REPO_PUBLIC_KEY` overrides our catalog key, for example with a test key for test builds. The default is DiamaneOS catalog key 0, and GrapheneOS's key is refused for our catalog. `REPO_KEY_VERSION` is bounded decimal text. Missing keys disable network and cached-catalog use. Caches bind the key and version.

`REPO_SERVER=staging` makes a test build send every request to `apps.staging.diamaneos.de`. Official builds use only `apps.diamaneos.de` and `apps-na.diamaneos.de`.

Run `python3 -m unittest discover -s tests -p 'test_download_adapters.py'`. Host JDK/Kotlin compilers are selected through `DOWNLOAD_POLICY_JAVA_HOME` and `DOWNLOAD_POLICY_KOTLIN_HOME`. Tests use explicit Android, TLS, JSON and verifier doubles. They are not Android or cryptographic qualification. The inherited namespace is `app.grapheneos.apps`.
