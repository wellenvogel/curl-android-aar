# curl-android-aar

Builds **curl 8.18.0** (libcurl) as an Android **Prefab AAR** using NDK 29.

The entire build — downloading the curl source from GitHub, verifying its
SHA-256, resolving the OpenSSL dependency via a Gradle Ivy repository,
locating or auto-installing the NDK and CMake, cross-compiling for all ABIs,
and packaging the Prefab AAR — is driven by a single Gradle invocation.

| Attribute       | Value                                        |
|-----------------|----------------------------------------------|
| Group           | `de.wellenvogel.android.ndk.thirdparty`      |
| NDK version     | **29** (`29.0.13113456`)                     |
| minSdk          | **21**                                       |
| curl            | **8.18.0**                                   |
| TLS backend     | OpenSSL 3.5.5 (from `openssl-android-aar`)   |
| ABIs            | `arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86` |
| 16 KB page-size | `-Wl,-z,max-page-size=16384`                 |
| CMake target    | `curl::curl`                                 |
| Repository      | Flat local Ivy                               |

---

## Prerequisites

| Tool          | Notes                                               |
|---------------|-----------------------------------------------------|
| JDK 17+       | Required by Gradle 8.9                              |
| Ninja         | Required by the CMake Ninja generator               |
| Android SDK   | With `sdkmanager` available (for NDK/CMake install) |

The NDK and CMake are located or auto-installed the same way as in
`openssl-android-aar`. Ninja must be separately available on `PATH`
(e.g. `sudo apt-get install ninja-build` on Debian/Ubuntu).

---

## OpenSSL dependency

curl links against OpenSSL. The build fetches the OpenSSL AAR automatically
from the flat Ivy repository configured via `opensslRepoUrl` in
`gradle/libs.versions.toml`. No manual download or local build is required.

- **Static curl** resolves `openssl` (contains `.a` files)
- **Shared curl** resolves `openssl-shared` (contains `.so` files)

The URL defaults to the GitHub releases of `openssl-android-aar` and can be
overridden at build time:

```bash
./gradlew assembleRelease -PopensslRepoUrl=https://example.com/ivy
```

---

## curl tarball SHA-256

curl does not publish `.sha256` companion files. The expected SHA-256 of the
tarball is pinned in `gradle/libs.versions.toml` as `curlSha256` and must be
updated whenever `curl` version is bumped. To obtain the hash:

```bash
curl -fsSL https://github.com/curl/curl/releases/download/curl-8_18_0/curl-8.18.0.tar.xz \
  | sha256sum
```

During local development you can bypass the check:
```bash
./gradlew assembleRelease -PskipHashCheck=true
```

---

## Build

### Step 1 — Install the Gradle wrapper jar (one-time)

```bash
curl -fSL https://services.gradle.org/distributions/gradle-8.9-wrapper.jar \
  -o gradle/wrapper/gradle-wrapper.jar
```

### Step 2 — Build

```bash
# Static libraries (default) — produces curl-release.aar
./gradlew assembleRelease

# Shared libraries — produces curl-shared-release.aar
./gradlew assembleRelease -PbuildShared=true
```

The OpenSSL AAR is resolved automatically from the Ivy repository. The build:
1. Resolves and unpacks the OpenSSL AAR via Gradle dependency resolution
2. Downloads `curl-8.18.0.tar.xz` from GitHub and verifies its SHA-256
3. Locates or auto-installs NDK 29 and CMake
4. Cross-compiles libcurl for all four ABIs using the NDK CMake toolchain
5. Assembles the Prefab AAR

Output:
```
build/outputs/aar/curl-release.aar
build/outputs/aar/curl-shared-release.aar
```

### Publish to local Ivy repository

```bash
# Static
./gradlew publishReleasePublicationToLocalRepository

# Shared
./gradlew publishReleasePublicationToLocalRepository -PbuildShared=true
```

Repository layout (`build/repository/`):
```
de.wellenvogel.android.ndk.thirdparty-curl-8.18.0.aar
de.wellenvogel.android.ndk.thirdparty-curl-8.18.0.ivy
de.wellenvogel.android.ndk.thirdparty-curl-shared-8.18.0.aar
de.wellenvogel.android.ndk.thirdparty-curl-shared-8.18.0.ivy
```

---

## GitHub Actions

Pushing to the `release` branch triggers `.github/workflows/release.yml`. It:
1. Reads all versions from `gradle/libs.versions.toml` using Python's `tomllib`
2. Installs Ninja via `apt-get install ninja-build`
3. Installs NDK and CMake via `sdkmanager`
4. Downloads the curl tarball and computes its SHA-256, patching the TOML
   so Gradle can verify it (avoids maintaining a stale pinned hash)
5. Builds both static and shared AARs, resolving OpenSSL from the Ivy repo
   configured by `opensslRepoUrl` in the TOML
6. Creates a GitHub release tagged with the bare curl version (e.g. `8.18.0`)
   and uploads all files from `build/repository/` as release assets

---

## AAR internal layout

**Static** (`curl-release.aar`):
```
curl-release.aar
├── AndroidManifest.xml     package="de.wellenvogel.android.ndk.thirdparty.curl"
├── classes.jar             ← empty stub (AAR spec requirement)
└── prefab/
    ├── prefab.json
    └── modules/
        └── curl/
            ├── module.json               export_libraries: ["ssl", "crypto"]
            ├── include/curl/curl.h …
            └── libs/android.<abi>/       abi.json  libcurl.a
```

**Shared** (`curl-shared-release.aar`) — same prefab structure with `libcurl.so`;
additionally contains `jni/<abi>/libcurl.so` for AGP's JNI packaging pipeline.
`module.json` has empty `export_libraries` (runtime linking handles OpenSSL deps).

---

## Consuming the AAR

**`build.gradle.kts`**:
```kotlin
repositories {
    // curl repository
    ivy {
        url = uri("https://github.com/<owner>/curl-android-aar/releases/download/8.18.0")
        patternLayout {
            artifact("[organisation]-[module]-[revision].[ext]")
            ivy("[organisation]-[module]-[revision].ivy")
        }
        metadataSources { ivyDescriptor() }
    }
    // OpenSSL repository (required for static curl — provides transitive ssl/crypto)
    ivy {
        url = uri("https://github.com/<owner>/openssl-android-aar/releases/download/3.5.5")
        patternLayout {
            artifact("[organisation]-[module]-[revision].[ext]")
            ivy("[organisation]-[module]-[revision].ivy")
        }
        metadataSources { ivyDescriptor() }
    }
}

android {
    buildFeatures { prefab = true }
}

dependencies {
    // Static curl (also needs openssl AAR for transitive link flags):
    implementation("de.wellenvogel.android.ndk.thirdparty:curl:8.18.0@aar")
    implementation("de.wellenvogel.android.ndk.thirdparty:openssl:3.5.5@aar")

    // Shared curl (self-contained, no openssl AAR needed at build time):
    // implementation("de.wellenvogel.android.ndk.thirdparty:curl-shared:8.18.0@aar")
}
```

**`CMakeLists.txt`**:
```cmake
find_package(curl REQUIRED CONFIG)
target_link_libraries(mylib curl::curl)
```

---

## Incremental builds

All tasks are fully incremental:
- `resolveOpenSSL` is skipped if the unpacked headers and libs already exist
- `downloadCurl` is skipped if the tarball exists and its SHA-256 matches
- `buildCurl` is skipped per-ABI if the output library already exists
- `assemblePrefab` and `packageAar` respect Gradle's up-to-date checks

Force a full rebuild:
```bash
./gradlew clean assembleRelease
```
