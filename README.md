# App Store (DiamaneOS)

DiamaneOS's build of [GrapheneOS's App Store](https://github.com/GrapheneOS/AppStore), the client
for the DiamaneOS app repository. The package name stays `app.grapheneos.apps`; the update,
download and verification code is GrapheneOS's, unchanged.

DiamaneOS changes:

- The repository is `https://apps.diamaneos.de`, pinned to the ISRG root keys.
- The repository metadata key is a build input with no fallback to GrapheneOS's key: the build
  reads `REPO_PUBLIC_KEY`, else the signify public key in `repo-public-key.pub`, and fails when
  neither holds a valid key. `repo-public-key.pub` is a placeholder for now, so every build
  names its key explicitly.
- Version codes are the upstream version x 100 plus a DiamaneOS revision (36.1 = 3601).

Release builds are signed with DiamaneOS's own App Store key (v2 and v4 signatures, as upstream).
