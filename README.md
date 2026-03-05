# curl-android-aar

Builds **curl 8.x** (libcurl) as an Android **Prefab AAR** using NDK 29.

The entire build — downloading curl source, locating or auto-installing the NDK
and CMake, cross-compiling for all ABIs against a pre-built OpenSSL, and
packaging the AAR — is driven by a single Gradle invocation.

| Attribute       | Value                                        |
|-----------------|----------------------------------------------|
| NDK version     | **29** (`29.0.13113456`)                     |
| minSdk          | **21**                                       |
| curl            | **8.18.0**                                   |
| TLS backend     | OpenSSL (from `openssl-android-aar`)         |
| ABIs            | `arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86` |
| 16 KB page-size | `-Wl,-z,max-page-size=16384`                 |
| CMake target    | `curl::curl`                                 |

---

## Prerequisites

| Tool          | Notes                                              |
|---------------|----------------------------------------------------|
| JDK 17+       | Required by Gradle 8.9                             |
| Android SDK   | With `sdkmanager` available (for NDK/CMake install)|
| OpenSSL libs  | Pre-built by `openssl-android-aar` (see below)     |

The NDK and CMake are located or auto-installed the same way as in `openssl-android-aar`.

---

## OpenSSL dependency

curl requires OpenSSL headers and static/shared libraries for each ABI.
By default these are expected at:

```
../openssl-android-aar/src/main/cpp/openssl/
  include/openssl/ssl.h
  libs/arm64-v8a/   libssl.a  libcrypto.a
  libs/x86_64/      libssl.a  libcrypto.a
  libs/armeabi-v7a/ libssl.a  libcrypto.a
  libs/x86/         libssl.a  libcrypto.a
```

To use a different location:

```bash
./gradlew assembleRelease -PopensslDir=/absolute/path/to/openssl/out
```

---

## Build

### Step 1 — Install the Gradle wrapper jar (one-time)

```bash
curl -fSL https://services.gradle.org/distributions/gradle-8.9-wrapper.jar \
  -o gradle/wrapper/gradle-wrapper.jar
```

### Step 2 — Build OpenSSL first (if not already done)

```bash
cd ../openssl-android-aar
./gradlew assembleRelease
cd ../curl-android-aar
```

### Step 3 — Build the AAR

```bash
# Static (default)
./gradlew assembleRelease

# Shared
./gradlew assembleRelease -PbuildShared=true
```

Output:
```
build/outputs/aar/curl-release.aar
build/outputs/aar/curl-shared-release.aar
```

### Publish to local Ivy repository

```bash
./gradlew publishReleasePublicationToLocalRepository
# Repository: build/repository/
```

---

## AAR internal layout

```
curl-release.aar
├── AndroidManifest.xml
├── classes.jar                          ← empty stub (AAR spec requirement)
└── prefab/
    ├── prefab.json
    └── modules/
        └── curl/
            ├── module.json              ← export_libraries: [ssl, crypto] for static
            ├── include/curl/curl.h …
            └── libs/
                ├── android.arm64-v8a/   abi.json  libcurl.a
                ├── android.x86_64/      …
                ├── android.armeabi-v7a/ …
                └── android.x86/         …
```

---

## Consuming the AAR

**`build.gradle.kts`**:
```kotlin
android {
    buildFeatures { prefab = true }
}
dependencies {
    implementation("de.wellenvogel.android.ndk.thirdparty:curl:8.18.0@aar")
    implementation("de.wellenvogel.android.ndk.thirdparty:openssl:3.5.5@aar")
}
```

**`CMakeLists.txt`**:
```cmake
find_package(curl REQUIRED CONFIG)
target_link_libraries(mylib curl::curl)
```
