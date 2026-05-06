# Firebase Setup

`app/google-services.json` must be downloaded from Firebase. Do not use the example file as-is.

Use these Android app values in Firebase Console:

- Android package name: `ml.melun.mangaview`
- Current local debug SHA-1: `6E:E0:32:DF:25:14:80:D6:72:03:5B:8B:1E:25:63:92:F6:65:2A:BF`
- Current local debug SHA-256: `B9:4F:A4:70:18:A7:1D:A3:EA:EE:BA:1B:63:B2:ED:1A:04:20:6C:33:45:72:14:80:2B:0E:06:C8:92:D3:66:CA`
- Legacy test APK SHA-1: `0F:17:07:A8:7E:EA:6D:30:6F:F8:04:ED:AD:DD:6E:95:AB:43:CF:9E`
- Legacy test APK SHA-256: `B6:29:91:23:8A:A4:82:34:C7:6B:4A:31:7E:FC:B6:4F:EA:0D:B3:7C:38:7B:4C:5D:F3:B8:FA:41:9F:C7:CB:27`

Steps:

1. Create or open a Firebase project.
2. Add an Android app with package name `ml.melun.mangaview`.
3. Add the SHA-1 for the APK you install to the Android app settings. Google sign-in fails with `DEVELOPER_ERROR (10)` when the installed APK signature is not registered.
4. Download `google-services.json`.
5. Put it at `app/google-services.json`.
6. In Firebase Authentication, enable the Google sign-in provider.
7. In Firestore Database, create a database and publish `firestore.rules` from this repo.
8. Rebuild with Java 11 or newer:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```
