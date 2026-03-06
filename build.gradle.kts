import org.gradle.api.GradleException
import java.net.URI
import java.security.MessageDigest

plugins {
    `ivy-publish`
}

group   = "de.wellenvogel.android.ndk.thirdparty"
version = libs.versions.curl.get()

// ─────────────────────────────────────────────────────────────────────────────
// Build configuration
// ─────────────────────────────────────────────────────────────────────────────
val curlVersion      = libs.versions.curl.get()
val ndkVersion       = libs.versions.ndkVersion.get()
val minSdkVersion    = libs.versions.minSdk.get().toInt()
val sdkToolsVersion  = libs.versions.sdkToolsVersion.get()
val cmakeVersion     = libs.versions.cmakeVersion.get()
val opensslVersion   = libs.versions.opensslVersion.get()
val abis             = listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")

// ─────────────────────────────────────────────────────────────────────────────
// Build type property: -PbuildShared=true  → shared (.so)
//                      default             → static (.a)
//
// Usage:
//   ./gradlew assembleRelease                       # static (default)
//   ./gradlew assembleRelease -PbuildShared=true    # shared
//
// OpenSSL dependency:
//   curl links against the static OpenSSL AAR produced by openssl-android-aar.
//   The base URL of that flat Ivy repository defaults to the value in
//   libs.versions.toml (opensslRepoUrl) and can be overridden at build time:
//     -PopensslRepoUrl=https://example.com/ivy
// ─────────────────────────────────────────────────────────────────────────────
val buildShared: Boolean = (findProperty("buildShared") as String?)
    ?.trim()?.lowercase() == "true"

val libType    = if (buildShared) "shared" else "static"
val artifactId = if (buildShared) "curl-shared" else "curl"

// Base URL of the flat Ivy repository that holds the OpenSSL AAR.
// Gradle resolves the dependency via a normal ivy { } repository block; no manual
// HTTP download is needed. Override at build time with -PopensslRepoUrl=<url>.
val opensslRepoUrl: String = (findProperty("opensslRepoUrl") as String?)
    ?.trimEnd('/')
    ?: libs.versions.opensslRepoUrl.get().trimEnd('/')

val opensslGroup  = "de.wellenvogel.android.ndk.thirdparty"
// For a shared curl build we need the OpenSSL shared AAR (contains .so files);
// for a static curl build we need the static AAR (contains .a files).
val opensslModule = if (buildShared) "openssl-shared" else "openssl"

// opensslAar configuration declared here, after opensslModule is known, so the
// dependency string resolves to the correct module (openssl vs openssl-shared).
// The dir is build-type-specific so static and shared extractions don't collide.
val opensslAar: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive    = false
}

// Where the unpacked OpenSSL headers and libs land (inside our build dir).
// Use a type-specific subdirectory so static and shared builds don't collide.
val opensslDir: File = layout.buildDirectory.dir("openssl-prefab-$libType").get().asFile

// curl releases tarballs from GitHub; the tag uses underscores (curl-8_18_0).
// curl.se does not publish .sha256 companion files for .tar.xz archives.
// We download from GitHub (HTTPS) and store the expected hash in libs.versions.toml.
val curlGithubTag  = "curl-" + curlVersion.replace(".", "_")
val tarballBaseUrl = "https://github.com/curl/curl/releases/download/$curlGithubTag"
val moduleDir      = projectDir
val curlOut        = layout.buildDirectory.dir("curl-out-$libType").get().asFile
val scratchDir = layout.buildDirectory.dir("curl-build").get().asFile

// Declare the OpenSSL Ivy repository and wire the dependency into opensslAar.
// These blocks must come after the val declarations (opensslRepoUrl / opensslModule
// / opensslVersion) but before any task references the configuration.
repositories {
    ivy {
        name = "openssl-ivy"
        url  = uri(opensslRepoUrl)
        patternLayout {
            artifact("[organisation]-[module]-[revision].[ext]")
            ivy("[organisation]-[module]-[revision].ivy")
        }
        metadataSources {
            // The Ivy descriptor is present; fall back to artifact-only if missing
            ivyDescriptor()
            artifact()
        }
        content {
            includeGroup(opensslGroup)
        }
    }
}

dependencies {
    opensslAar("$opensslGroup:$opensslModule:$opensslVersion@aar")
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(65536)
        var n = ins.read(buf)
        while (n != -1) { digest.update(buf, 0, n); n = ins.read(buf) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// java.net.http.HttpClient (Java 11+) handles all redirects natively and never
// produces an FtpURLConnection, unlike the old URLConnection cast approach.
val httpClient: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
    .build()

fun download(url: String, dest: File) {
    logger.lifecycle("  Downloading $url")
    val request = java.net.http.HttpRequest.newBuilder()
        .uri(URI(url))
        .header("User-Agent", "Gradle/curl-android-aar")
        .GET().build()
    val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(dest.toPath()))
    if (response.statusCode() !in 200..299) {
        dest.delete()
        throw GradleException("GET $url returned HTTP ${response.statusCode()}")
    }
}

fun resolveNdk(): File {
    for (envVar in listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")) {
        val v = System.getenv(envVar) ?: continue
        val f = File(v)
        if (f.resolve("source.properties").exists()) {
            logger.lifecycle("NDK found via \$$envVar: $f"); return f
        }
    }
    val sdkRoots = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home")?.let { "$it/Library/Android/sdk" },
        System.getProperty("user.home")?.let { "$it/Android/Sdk" },
        "/opt/android/sdk"
    )
    for (sdkRoot in sdkRoots) {
        File("$sdkRoot/ndk/$ndkVersion").takeIf { it.resolve("source.properties").exists() }
            ?.let { logger.lifecycle("NDK found: $it"); return it }
        File("$sdkRoot/ndk").takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.name.startsWith("29.") && it.resolve("source.properties").exists() }
            ?.maxByOrNull { it.name }
            ?.let { logger.lifecycle("NDK 29.x found: $it"); return it }
    }
    for (sdkRoot in sdkRoots) {
        val sdkManager = listOf(
            "$sdkRoot/cmdline-tools/latest/bin/sdkmanager",
            "$sdkRoot/cmdline-tools/$sdkToolsVersion/bin/sdkmanager",
            "$sdkRoot/tools/bin/sdkmanager"
        ).map(::File).firstOrNull(File::exists) ?: continue
        logger.lifecycle("Installing NDK $ndkVersion via sdkmanager ...")
        val rc = ProcessBuilder(sdkManager.absolutePath, "--install", "ndk;$ndkVersion")
            .inheritIO().start().waitFor()
        if (rc != 0) throw GradleException("sdkmanager exited $rc")
        File("$sdkRoot/ndk/$ndkVersion").takeIf { it.resolve("source.properties").exists() }
            ?.let { return it }
    }
    throw GradleException("""
        |Android NDK $ndkVersion not found and could not be installed automatically.
        |Options:
        |  A) Install via Android Studio: SDK Manager → SDK Tools → NDK (Side by side) → $ndkVersion
        |  B) export ANDROID_NDK_HOME=/path/to/ndk/$ndkVersion
        |  C) sdkmanager "ndk;$ndkVersion"
        """.trimMargin())
}

// Locate the cmake binary — prefer the one bundled inside the NDK's SDK, fall back to PATH
fun resolveCmake(sdkRoot: File?): String {
    if (sdkRoot != null) {
        // SDK cmake installations live at <sdk>/cmake/<version>/bin/cmake
        val sdkCmake = sdkRoot.parentFile?.parentFile   // sdk root from ndk/<ver>
            ?.resolve("cmake")
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.sortedDescending()
            ?.mapNotNull { it.resolve("bin/cmake").takeIf { f -> f.canExecute() } }
            ?.firstOrNull()
        if (sdkCmake != null) {
            logger.lifecycle("CMake found in SDK: $sdkCmake"); return sdkCmake.absolutePath
        }
    }
    // Fall back to system cmake
    val which = ProcessBuilder("which", "cmake").start()
    val path  = which.inputStream.bufferedReader().readText().trim()
    if (which.waitFor() == 0 && path.isNotEmpty()) {
        logger.lifecycle("CMake found on PATH: $path"); return path
    }
    throw GradleException(
        "cmake not found. Install via sdkmanager: sdkmanager \"cmake;$cmakeVersion\""
    )
}

fun hostTag(): String {
    val os   = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("linux")                                                    -> "linux-x86_64"
        os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) -> "darwin-arm64"
        os.contains("mac")                                                      -> "darwin-x86_64"
        else -> throw GradleException("Unsupported host OS: $os")
    }
}

fun exec(workDir: File, env: Map<String, String>, vararg cmd: String) {
    logger.lifecycle("  > ${cmd.joinToString(" ")}")
    val pb = ProcessBuilder(*cmd).directory(workDir).redirectErrorStream(true)
    pb.environment().putAll(env)
    val proc = pb.start()
    proc.inputStream.bufferedReader().forEachLine { logger.info(it) }
    val rc = proc.waitFor()
    if (rc != 0) throw GradleException("Command failed (exit $rc): ${cmd.first()}")
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: resolveOpenSSL
//
// Gradle resolves the opensslAar configuration (declared above) from the Ivy
// repository — no manual HTTP download needed. This task unpacks the AAR into
// build/openssl-prefab/ so CMake can find headers and libraries:
//
//   build/openssl-prefab/
//     include/openssl/         ← ABI-independent headers (from crypto module)
//     libs/
//       arm64-v8a/   libssl.{a|so}  libcrypto.{a|so}
//       x86_64/      …
//       armeabi-v7a/ …
//       x86/         …
//
// The task is skipped when all expected outputs already exist (incremental).
// ─────────────────────────────────────────────────────────────────────────────
val resolveOpenSSL by tasks.registering {
    // Declare the resolved AAR file as an input so the task re-runs if it changes
    inputs.files(opensslAar)
    outputs.dir(opensslDir)
    outputs.upToDateWhen {
        val libExt = if (buildShared) "so" else "a"
        abis.all { abi ->
            opensslDir.resolve("libs/$abi/libssl.$libExt").exists() &&
            opensslDir.resolve("libs/$abi/libcrypto.$libExt").exists()
        } && opensslDir.resolve("include/openssl/ssl.h").exists()
    }

    doLast {
        // Gradle has already downloaded the AAR into its cache; get the local path
        val aarFile = opensslAar.singleFile
        logger.lifecycle("Unpacking OpenSSL AAR: ${aarFile.name}")

        opensslDir.mkdirs()
        val extractDir = opensslDir.resolve(".extract")
        extractDir.deleteRecursively()
        extractDir.mkdirs()

        // Use java.util.zip — no external tools required
        java.util.zip.ZipFile(aarFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { entry ->
                    // Headers and libs live under prefab/ for both static and shared AARs
                    entry.name.startsWith("prefab/modules/crypto/include/") ||
                    entry.name.startsWith("prefab/modules/crypto/libs/") ||
                    entry.name.startsWith("prefab/modules/ssl/libs/")
                }
                .forEach { entry ->
                    val dest = extractDir.resolve(entry.name)
                    dest.parentFile.mkdirs()
                    zip.getInputStream(entry).use { ins ->
                        dest.outputStream().use { ins.copyTo(it) }
                    }
                }
        }

        // Headers: prefab/modules/crypto/include/ → build/openssl-prefab/include/
        if (!opensslDir.resolve("include/openssl/ssl.h").exists()) {
            extractDir.resolve("prefab/modules/crypto/include")
                .copyRecursively(opensslDir.resolve("include"), overwrite = true)
            logger.lifecycle("OpenSSL headers → ${opensslDir.resolve("include")}")
        }

        // Libs: one directory per ABI
        // Static AAR: libs live at prefab/modules/<mod>/libs/android.<abi>/lib<mod>.a
        // Shared AAR: libs live at prefab/modules/<mod>/libs/android.<abi>/lib<mod>.so
        val libExt = if (buildShared) "so" else "a"
        abis.forEach { abi ->
            val libDst = opensslDir.resolve("libs/$abi")
            libDst.mkdirs()
            for (pair in listOf(
                "prefab/modules/crypto/libs/android.$abi/libcrypto.$libExt" to "libcrypto.$libExt",
                "prefab/modules/ssl/libs/android.$abi/libssl.$libExt"       to "libssl.$libExt"
            )) {
                val (srcPath, dstName) = pair
                extractDir.resolve(srcPath)
                    .copyTo(libDst.resolve(dstName), overwrite = true)
            }
            logger.lifecycle("[$abi] OpenSSL libs ($libExt) → $libDst")
        }

        extractDir.deleteRecursively()
        logger.lifecycle("OpenSSL $opensslVersion unpacked into $opensslDir")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: downloadCurl
// Downloads curl-<version>.tar.xz from GitHub releases and verifies its SHA-256
// against the expected hash stored in libs.versions.toml (curlSha256).
//
// curl.se does not publish .sha256 companion files for .tar.xz archives and the
// GitHub release assets have no checksum sidecar either. The expected hash must
// therefore be pinned in the version catalog and updated whenever curlVersion
// changes. Run with -PskipHashCheck=true to bypass verification during testing.
// ─────────────────────────────────────────────────────────────────────────────
val tarballName  = "curl-${curlVersion}.tar.xz"
val tarballFile  = scratchDir.resolve(tarballName)
val expectedSha256 = libs.versions.curlSha256.get()
val skipHashCheck  = (findProperty("skipHashCheck") as String?)?.trim()?.lowercase() == "true"

val downloadCurl by tasks.registering {
    outputs.file(tarballFile)
    outputs.upToDateWhen {
        tarballFile.exists() && (skipHashCheck || sha256(tarballFile) == expectedSha256)
    }

    doLast {
        scratchDir.mkdirs()

        if (tarballFile.exists() && (skipHashCheck || sha256(tarballFile) == expectedSha256)) {
            logger.lifecycle("$tarballName already downloaded and verified — skipping.")
            return@doLast
        }

        val tarballUrl = "$tarballBaseUrl/$tarballName"
        download(tarballUrl, tarballFile)

        if (!skipHashCheck) {
            val actual = sha256(tarballFile)
            if (actual != expectedSha256) {
                tarballFile.delete()
                throw GradleException(
                    "SHA-256 mismatch for $tarballName" +
                    "\n  expected: $expectedSha256" +
                    "\n  actual:   $actual" +
                    "\nUpdate curlSha256 in gradle/libs.versions.toml if you bumped the version."
                )
            }
            logger.lifecycle("$tarballName downloaded and verified.")
        } else {
            logger.lifecycle("$tarballName downloaded (hash check skipped).")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: buildCurl
//
// Cross-compiles libcurl for every ABI using the NDK CMake toolchain.
// curl is built with OpenSSL as the TLS backend (--with-ssl / CURL_USE_OPENSSL).
//
// The OpenSSL pre-built headers and libraries are expected at:
//   $opensslDir/include/openssl/ssl.h
//   $opensslDir/libs/<abi>/libssl.{a|so}
//   $opensslDir/libs/<abi>/libcrypto.{a|so}
//
// Override with -PopensslDir=/path if the openssl-android-aar project lives
// elsewhere. The OpenSSL link type (static/shared) is matched to buildShared.
//
// Static:  produces  libs/<abi>/libcurl.a
// Shared:  produces  libs/<abi>/libcurl.so
//
// 16 KB page-size: passed via CMAKE_SHARED_LINKER_FLAGS / CMAKE_EXE_LINKER_FLAGS.
// ─────────────────────────────────────────────────────────────────────────────
val buildCurl by tasks.registering {
    dependsOn(downloadCurl, resolveOpenSSL)
    outputs.dir(curlOut)
    outputs.upToDateWhen {
        val libExt = if (buildShared) "so" else "a"
        abis.all { abi ->
            curlOut.resolve("libs/$abi/libcurl.$libExt").exists()
        } && curlOut.resolve("include/curl/curl.h").exists()
    }

    doLast {
        // Validate OpenSSL inputs
        val opensslLibExt = if (buildShared) "so" else "a"
        require(opensslDir.resolve("include/openssl/ssl.h").exists()) {
            "OpenSSL headers not found at $opensslDir/include/openssl/ssl.h\n" +
            "Check that opensslRepoUrl is reachable: $opensslRepoUrl"
        }
        abis.forEach { abi ->
            for (lib in listOf("libssl.$opensslLibExt", "libcrypto.$opensslLibExt")) {
                require(opensslDir.resolve("libs/$abi/$lib").exists()) {
                    "OpenSSL library not found: $opensslDir/libs/$abi/$lib\n" +
                    "Check that opensslRepoUrl is reachable: $opensslRepoUrl"
                }
            }
        }

        logger.lifecycle("Build type: $libType")
        logger.lifecycle("OpenSSL:    $opensslDir")

        val ndkRoot   = resolveNdk()
        val cmake     = resolveCmake(ndkRoot)
        val toolchain = ndkRoot.resolve("build/cmake/android.toolchain.cmake")
        val jobs      = Runtime.getRuntime().availableProcessors().toString()

        logger.lifecycle("NDK:        $ndkRoot")
        logger.lifecycle("Toolchain:  $toolchain")
        logger.lifecycle("CMake:      $cmake")
        logger.lifecycle("CPUs:       $jobs")

        abis.forEach { abi ->
            val libDst = curlOut.resolve("libs/$abi")
            val libExt = if (buildShared) "so" else "a"

            if (libDst.resolve("libcurl.$libExt").exists()) {
                logger.lifecycle("[$abi] Already built ($libType) — skipping.")
                return@forEach
            }

            logger.lifecycle("")
            logger.lifecycle("━━━  $abi  (API $minSdkVersion, $libType)  ━━━")

            val srcDir   = scratchDir.resolve("curl-${curlVersion}-${abi}-${libType}")
            val buildDir = scratchDir.resolve("build-${abi}-${libType}")
            val instDir  = scratchDir.resolve("install-${abi}-${libType}")

            srcDir.deleteRecursively()
            buildDir.deleteRecursively()
            instDir.deleteRecursively()

            // Extract source
            exec(scratchDir, emptyMap(),
                "tar", "xJf", tarballFile.absolutePath, "-C", scratchDir.absolutePath)
            scratchDir.resolve("curl-$curlVersion").renameTo(srcDir)

            buildDir.mkdirs()

            val opensslLibDir    = opensslDir.resolve("libs/$abi").absolutePath
            val opensslIncDir    = opensslDir.resolve("include").absolutePath
            val pageAlignFlags   = "-Wl,-z,max-page-size=16384"
            val hardenFlags      = "-fstack-protector-strong -D_FORTIFY_SOURCE=2"
            val gcFlags          = "-ffunction-sections -fdata-sections"
            val cFlags           = "$gcFlags $hardenFlags"

            // cmake -S <src> -B <build> ...
            val configArgs = mutableListOf(
                cmake,
                "-S", srcDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-$minSdkVersion",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_INSTALL_PREFIX=${instDir.absolutePath}",
                // Library type
                "-DBUILD_SHARED_LIBS=${if (buildShared) "ON" else "OFF"}",
                "-DBUILD_STATIC_LIBS=${if (buildShared) "OFF" else "ON"}",
                // Don't build the curl tool, tests, docs or examples
                "-DBUILD_CURL_EXE=OFF",
                "-DBUILD_TESTING=OFF",
                "-DBUILD_LIBCURL_DOCS=OFF",
                "-DBUILD_MISC_DOCS=OFF",
                "-DBUILD_EXAMPLES=OFF",
                // TLS: OpenSSL only
                "-DCURL_USE_OPENSSL=ON",
                "-DCURL_USE_MBEDTLS=OFF",
                "-DCURL_USE_BEARSSL=OFF",
                "-DCURL_USE_GNUTLS=OFF",
                "-DCURL_USE_RUSTLS=OFF",
                // Point cmake at our pre-built OpenSSL
                "-DOPENSSL_ROOT_DIR=${opensslDir.absolutePath}",
                "-DOPENSSL_INCLUDE_DIR=$opensslIncDir",
                "-DOPENSSL_SSL_LIBRARY=$opensslLibDir/libssl.$opensslLibExt",
                "-DOPENSSL_CRYPTO_LIBRARY=$opensslLibDir/libcrypto.$opensslLibExt",
                // Disable optional deps that won't be present on the build machine
                "-DCURL_USE_LIBPSL=OFF",
                "-DCURL_USE_LIBSSH2=OFF",
                "-DCURL_USE_LIBSSH=OFF",
                "-DCURL_USE_GSASL=OFF",
                "-DCURL_BROTLI=OFF",
                "-DCURL_ZSTD=OFF",
                "-DUSE_NGHTTP2=OFF",
                "-DUSE_NGTCP2=OFF",
                "-DUSE_QUICHE=OFF",
                // Use Android's built-in zlib
                "-DCURL_ZLIB=ON",
                // Compiler and linker flags
                "-DCMAKE_C_FLAGS=$cFlags",
                "-DCMAKE_SHARED_LINKER_FLAGS=$pageAlignFlags",
                "-DCMAKE_EXE_LINKER_FLAGS=$pageAlignFlags",
                // pkg-config is unreliable in cross-compile context
                "-DCURL_USE_PKGCONFIG=OFF"
            )

            exec(buildDir, emptyMap(), *configArgs.toTypedArray())
            exec(buildDir, emptyMap(), cmake, "--build", ".", "--parallel", jobs)
            exec(buildDir, emptyMap(), cmake, "--install", ".")

            // Copy lib to output
            libDst.mkdirs()
            if (buildShared) {
                // Find the plain .so (not versioned symlinks)
                val soPlain = instDir.resolve("lib/libcurl.so")
                if (soPlain.exists()) {
                    soPlain.copyTo(libDst.resolve("libcurl.so"), overwrite = true)
                } else {
                    val versioned = instDir.resolve("lib").listFiles()
                        ?.filter { it.name.startsWith("libcurl.so.") &&
                                   !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                        ?.maxByOrNull { it.name }
                        ?: throw GradleException("libcurl.so not found in ${instDir.resolve("lib")}")
                    versioned.copyTo(libDst.resolve("libcurl.so"), overwrite = true)
                }
            } else {
                instDir.resolve("lib/libcurl.a")
                    .copyTo(libDst.resolve("libcurl.a"), overwrite = true)
            }

            // Copy headers once (ABI-independent)
            if (!curlOut.resolve("include/curl/curl.h").exists()) {
                instDir.resolve("include")
                    .copyRecursively(curlOut.resolve("include"), overwrite = true)
                logger.lifecycle("Headers installed → ${curlOut.resolve("include")}")
            }

            logger.lifecycle("[$abi] ✓  libcurl.$libExt → $libDst")
        }

        logger.lifecycle("\ncurl $curlVersion build complete ($libType).")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: assemblePrefab
//
// Static:  abi.json has "static": true,  libs contain libcurl.a
// Shared:  abi.json has "static": false, libs contain libcurl.so
//
// The curl module declares a dependency on the openssl prefab package so that
// consumers automatically get the correct link flags when using static curl.
// ─────────────────────────────────────────────────────────────────────────────
val assemblePrefab by tasks.registering {
    dependsOn(buildCurl)
    outputs.dir(layout.buildDirectory.dir("prefab-$libType"))

    doLast {
        val prefabRoot = layout.buildDirectory.dir("prefab-$libType").get().asFile
        prefabRoot.deleteRecursively()
        prefabRoot.mkdirs()

        prefabRoot.resolve("prefab.json").writeText(
            """{"schema_version":2,"name":"curl","version":"$curlVersion","dependencies":[]}"""
        )

        val modDir = prefabRoot.resolve("modules/curl")
        modDir.mkdirs()

        // For static curl, consumers must also link against ssl and crypto.
        // Declare them as export_libraries so the prefab CMake integration
        // adds them automatically.
        val exportLibs = if (!buildShared) """["ssl","crypto"]""" else "[]"
        modDir.resolve("module.json").writeText(
            """{"export_libraries":$exportLibs,"android":{"export_libraries":$exportLibs}}"""
        )

        curlOut.resolve("include")
            .copyRecursively(modDir.resolve("include"), overwrite = true)

        val libExt      = if (buildShared) "so" else "a"
        val libFileName = "libcurl.$libExt"

        abis.forEach { abi ->
            val libDir = modDir.resolve("libs/android.$abi")
            libDir.mkdirs()
            libDir.resolve("abi.json").writeText(
                """{
  "abi": "$abi",
  "api": $minSdkVersion,
  "ndk": 29,
  "stl": "none",
  "static": ${!buildShared}
}"""
            )
            curlOut.resolve("libs/$abi/$libFileName")
                .copyTo(libDir.resolve(libFileName), overwrite = true)
        }

        logger.lifecycle("Prefab package assembled ($libType): ${prefabRoot.absolutePath}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// emptyJar + packageAar
// ─────────────────────────────────────────────────────────────────────────────
val emptyJar by tasks.registering(Jar::class) {
    archiveFileName.set("classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/empty_jar"))
}

val aarName = if (buildShared) "curl-shared-release.aar" else "curl-release.aar"

val packageAar by tasks.registering(Zip::class) {
    dependsOn(assemblePrefab, emptyJar)

    archiveFileName.set(aarName)
    destinationDirectory.set(layout.buildDirectory.dir("outputs/aar"))

    from(moduleDir.resolve("src/main/AndroidManifest.xml"))
    from(emptyJar.get().archiveFile)
    from(layout.buildDirectory.dir("prefab-$libType")) { into("prefab") }

    if (buildShared) {
        abis.forEach { abi ->
            from(curlOut.resolve("libs/$abi")) {
                include("*.so")
                into("jni/$abi")
            }
        }
    }
}

tasks.register("assembleRelease") { dependsOn(packageAar) }
tasks.register("assemble")        { dependsOn(packageAar) }
tasks.register("clean") {
    group       = "build"
    description = "Deletes all build outputs for the current build type ($libType). " +
                  "Use 'cleanAll' to remove outputs for both types."
    doLast {
        listOf(
            layout.buildDirectory.dir("curl-out-$libType").get().asFile,
            layout.buildDirectory.dir("openssl-prefab-$libType").get().asFile,
            layout.buildDirectory.dir("prefab-$libType").get().asFile,
            layout.buildDirectory.dir("outputs/aar").get().asFile,
            layout.buildDirectory.dir("repository").get().asFile,
            layout.buildDirectory.dir("intermediates").get().asFile,
        ).forEach {
            if (it.deleteRecursively()) logger.lifecycle("Deleted $it")
        }
    }
}

tasks.register("cleanAll") {
    group       = "build"
    description = "Deletes all build outputs for both static and shared build types, " +
                  "including downloaded curl source and resolved OpenSSL artifacts."
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        if (buildDir.deleteRecursively()) logger.lifecycle("Deleted $buildDir")
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Ivy publishing — flat local Ivy repository.
//
// All files land directly in build/repository/:
//   de.wellenvogel.android.ndk.thirdparty-curl[-shared]-8.18.0-1.aar
//   de.wellenvogel.android.ndk.thirdparty-curl[-shared]-8.18.0-1.ivy
//
// To consume from another Gradle project:
//   repositories {
//     ivy {
//       url = uri("/path/to/curl-android-aar/build/repository")
//       patternLayout {
//         artifact("[organisation]-[module]-[revision].[ext]")
//         ivy("[organisation]-[module]-[revision].ivy")
//       }
//       metadataSources { ivyDescriptor() }
//     }
//   }
//   dependencies {
//     implementation("de.wellenvogel.android.ndk.thirdparty:curl:8.18.0@aar")
//   }
// ─────────────────────────────────────────────────────────────────────────────
publishing {
    publications {
        create<IvyPublication>("release") {
            organisation = project.group.toString()
            module       = artifactId
            revision     = project.version.toString()

            artifact(packageAar.get().archiveFile) {
                builtBy(packageAar)
                name      = artifactId
                extension = "aar"
                type      = "aar"
            }

            descriptor {
                description {
                    text.set(
                        "curl $curlVersion for Android — Prefab AAR " +
                        "($libType, NDK $ndkVersion, 16 KB page-size)"
                    )
                }
                license {
                    name.set("curl")
                    url.set("https://curl.se/docs/copyright.html")
                }
            }
        }
    }

    repositories {
        ivy {
            name = "local"
            url  = uri(layout.buildDirectory.dir("repository"))
            patternLayout {
                artifact("[organisation]-[module]-[revision].[ext]")
                ivy("[organisation]-[module]-[revision].ivy")
            }
        }
    }
}
