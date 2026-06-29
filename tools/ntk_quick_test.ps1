param(
    [string]$DeviceSerial = "emulator-5554",
    [string]$TargetEpisodePath = "",
    [int]$FirstDrawableMaxMs = 8000,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)
$ErrorActionPreference = "Stop"
if(Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

if(-not $SkipBuild) {
    Write-Host "Building..."
    .\gradlew.bat assembleDebug 2>&1 | Out-Null
    .\gradlew.bat assembleDebugAndroidTest 2>&1 | Out-Null
}

if(-not $SkipInstall) {
    Write-Host "Installing..."
    adb -s $DeviceSerial install -r "app\build\outputs\apk\debug\mangaViewer_2112261629-debug.apk" 2>&1 | Out-Null
    adb -s $DeviceSerial install -r "app\build\outputs\apk\androidTest\debug\mangaViewer_2112261629-debug-androidTest.apk" 2>&1 | Out-Null
}

adb -s $DeviceSerial logcat -c

$extra = ""
if($TargetEpisodePath -ne "") {
    $extra = " -e ntkTargetEpisodePath $TargetEpisodePath"
}

Write-Host "Running quick test..."
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$result = & adb -s $DeviceSerial shell am instrument -w -r `
    -e runLiveNetworkTests true `
    -e ntkSafeNetwork true `
    -e ntkRandomRuns 1 `
    -e ntkScrollSteps 0 `
    -e ntkAppendSteps 0 `
    -e ntkFirstDrawableMaxMs $FirstDrawableMaxMs `
    -e ntkRequireStrictAck false `
    -e ntkAssertNoJank false `
    -e ntkMaxDroppedFrames 999 `
    -e ntkMaxMissedFrames 999 `
    -e ntkRenderFrameMaxMs 999 `
    -e ntkClearAckBeforeRun true `
    -e ntkClearReaderImageCacheBeforeRun true `
    -e ntkMode native-ack `
    -e class ml.melun.mangaview.reader.NtkRandomStressInstrumentedTest#randomNtkEpisodesOpenAndScroll `
    ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$sw.Stop()

Write-Host "`n=== Quick Test Result ($($sw.Elapsed.TotalSeconds)s) ==="
$result | ForEach-Object {
    if($_ -match "firstDrawable|drawableMs|ackMs|streamMs|passed=|FAILED|passed") {
        Write-Host $_
    }
}

$logcat = Get-ChildItem "build\ntk-random-perf\*\logcat.txt" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if($logcat) {
    Write-Host "`n=== Key Timing ==="
    Select-String -Path $logcat.FullName -Pattern "ntk_ack_pre_start|ntk_nv_issue |ntk_native_ack_prepare_challenge_code|ntk_native_ack_trusted|ntk_webview_ack_preflight_done|reader_first_drawable|ntk_foreground_image_race_done" |
        Select-Object -First 10 | ForEach-Object { Write-Host $_.Line }
}
