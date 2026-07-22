param(
    [Parameter(Mandatory = $true)][string]$MetaEglPath,
    [Parameter(Mandatory = $true)][string]$GfxstreamEglPath,
    [string]$DeviceSerial = "emulator-5554"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$expectedMeta = "8d9e24d362025f216ad9539a6903479a7b2cb775c8279b54330596fd24306d6f"
$expectedGfxstream = "782683ce65e41dc5dfd12cb89a75f49d7bdadf0a0831b9c6652fdc408cf493f3"
$meta = Get-Item -LiteralPath $MetaEglPath -ErrorAction Stop
$gfxstream = Get-Item -LiteralPath $GfxstreamEglPath -ErrorAction Stop
if($meta.Length -ne 702016 -or
        (Get-FileHash -LiteralPath $meta.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            $expectedMeta) {
    throw "META-EGL artifact identity mismatch: $($meta.FullName)"
}
if($gfxstream.Length -ne 238336 -or
        (Get-FileHash -LiteralPath $gfxstream.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            $expectedGfxstream) {
    throw "gfxstream EGL artifact identity mismatch: $($gfxstream.FullName)"
}

$emulator = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq "emulator.exe" -and
    $_.CommandLine -match '(?i)(?:^|\s)-port\s+5554(?:\s|$)'
} | Select-Object -First 1
if($null -eq $emulator -or
        [string]$emulator.CommandLine -notmatch '(?i)(?:^|\s)-gpu\s+host(?:\s|$)' -or
        [string]$emulator.CommandLine -notmatch '(?i)(?:^|\s)-writable-system(?:\s|$)') {
    throw "Pinned emulator must be running with -gpu host -writable-system"
}

function Invoke-Adb([string[]]$Arguments) {
    & adb -s $DeviceSerial @Arguments
    if($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

Invoke-Adb @("root")
Start-Sleep -Seconds 2
Invoke-Adb @("wait-for-device")
Invoke-Adb @("remount")
Invoke-Adb @("push", $meta.FullName, "/system/lib64/libEGL.so")
Invoke-Adb @("push", $gfxstream.FullName,
    "/vendor/lib64/egl/libEGL_emulation.so")
Invoke-Adb @("shell", "chmod", "0644", "/system/lib64/libEGL.so",
    "/vendor/lib64/egl/libEGL_emulation.so")
Invoke-Adb @("shell", "chown", "root:root", "/system/lib64/libEGL.so",
    "/vendor/lib64/egl/libEGL_emulation.so")
Invoke-Adb @("shell", "restorecon", "/system/lib64/libEGL.so",
    "/vendor/lib64/egl/libEGL_emulation.so")
$remote = (& adb -s $DeviceSerial shell sha256sum /system/lib64/libEGL.so `
    /vendor/lib64/egl/libEGL_emulation.so) -join "`n"
if($LASTEXITCODE -ne 0 -or $remote -notmatch [regex]::Escape($expectedMeta) -or
        $remote -notmatch [regex]::Escape($expectedGfxstream)) {
    throw "Remote EGL fingerprint mismatch: $remote"
}
Invoke-Adb @("reboot")
