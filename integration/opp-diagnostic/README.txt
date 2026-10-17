Read-only RG477V OPP diagnostic, Android 14 / exact fingerprint only

Package: io.github.breeder1229.rg477voppdiag
Version: 0.1 / code 1. Target/min API 34. Platform certificate pinned to the
same public AOSP test certificate as the backed-up Control Center.
sharedUserId android.uid.system is used only to test read access as UID1000.
There are no requested permissions, network calls, settings writes, hardware
Binder calls, shell commands, services, providers or receivers. The only
exported component is the launcher Activity; Intent data/extras are unused.
It reads only the fixed paths listed in MainActivity.java, at most 128 KiB
per file, once per Activity creation. Failure to read a file is reported.
It writes only its own external files/opp-diagnostic.txt and logs the same
report under RG477VOppDiag. It does not change or replace Control Center.

Rebuild using the existing SDK tools, no Gradle project needed:
- javac -encoding UTF-8 -source 8 -target 8 -classpath android.jar -d classes MainActivity.java
- jar --create --file helper.jar -C classes .
- java -cp d8.jar com.android.tools.r8.D8 --min-api 34 --lib android.jar --output dex helper.jar
- aapt2 link -I android.jar --manifest AndroidManifest.xml --min-sdk-version 34
  --target-sdk-version 34 --version-code 1 --version-name 0.1 -o resources.apk
- Add only dex/classes.dex to a copy of resources.apk.
- zipalign -P 16, sign with the external matching platform PKCS8/certificate.
- Verify signature, UID request, package, no permissions and DEX read paths.

The coordinator independently reviews the APK before installation/execution.
Recovery: uninstall this diagnostic package after retrieving the report.
Existing Control Center/underclock app, fan, performance mode and profiles
remain controlled by their existing apps.
