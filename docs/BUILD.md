# Build V1.00

Toolchain: JDK21, Gradle8.13(wrapper with pinned checksum), Android Gradle Plugin8.13.2, SDK36, Build Tools35.0.0.

The source snapshot contains frontend and independently written native helper source/tests. It excludes vendor APKs, signing keys, SDK/JDK, local paths and build caches. The public APK carries a byte-identical v0.5.1 native payload; V1.00 changes frontend version metadata/title and bundled explanatory text.

1. Obtain the V1.00 APK from this repository's Releases and check SHA256SUMS.txt.
2. Run `python tools/prepare_payload.py --release-apk /path/to/rg477v-underclock-V1.00.apk`. This verifies the two pinned native/stock payload hashes before extracting them into the ignored generated asset directory.
3. Set JAVA_HOME, ANDROID_HOME/ANDROID_SDK_ROOT and optional GRADLE_USER_HOME to your locally installed tools. Accept the SDK license through the official SDK installer.
4. Run `gradlew assembleRelease testReleaseUnitTest lintRelease` (Windows: `gradlew.bat`). Do not run clean after preparing generated assets.
5. Align with zipalign `-P 16 -f 4`, sign the frontend with the certificate matching the supported firmware platform identity, then verify with apksigner and `zipalign -c -P 16 -v 4`. A normal personal/debug signer cannot provide the current system shared UID. Signing inputs remain outside the project.

To rebuild the native helper independently, follow integration/control-center/BUILD-NOTES.txt with exact stock1.0.29, the reviewed v0.5.1 66-class `--power-overlay` mode and external matching signing material. The protected vendor entries, original hooks and fixed firmware checks must remain intact. That document distinguishes legacy build modes from the current release.

`python tools/check_publication.py` scans source/doc filenames and content for personal paths, device serials, LAN addresses, credential patterns, unsupported binary artifacts and broken local Markdown links. Its report contains relative paths and categories only. It does not claim to detect every possible secret or replace an independent security review.

No vendor source or signing key is granted a new license by this source publication. See NOTICE.md.
