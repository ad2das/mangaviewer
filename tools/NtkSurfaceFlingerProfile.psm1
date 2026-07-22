Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:NtkNativeCommandModule = Join-Path $PSScriptRoot "NtkNativeCommand.psm1"
if(-not (Test-Path -LiteralPath $script:NtkNativeCommandModule -PathType Leaf)) {
    throw "Native-command module not found: $script:NtkNativeCommandModule"
}
Import-Module -Name $script:NtkNativeCommandModule -ErrorAction Stop

$script:NtkHighFpsLateAppPhaseProperty =
    "debug.sf.high_fps_late_app_phase_offset_ns"
$script:NtkHighFpsLateSfPhaseProperty =
    "debug.sf.high_fps_late_sf_phase_offset_ns"
$script:NtkSurfaceFlingerProfileFields = @(
    "vsyncPeriodNanos",
    "appVsyncOffsetNanos",
    "presentationDeadlineNanos",
    "lateAppPhaseNanos",
    "lateSfPhaseNanos",
    "earlyAppPhaseNanos",
    "earlySfPhaseNanos",
    "glEarlyAppPhaseNanos",
    "glEarlySfPhaseNanos",
    "highFpsLateAppPhaseOffsetProperty",
    "highFpsLateSfPhaseOffsetProperty"
)

function Invoke-NtkSurfaceFlingerAdbText {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$DeviceSerial,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowEmpty
    )

    $nativeArguments = @("-s", $DeviceSerial) + @($Arguments)
    $result = Invoke-NtkNativeCommand -FilePath "adb" `
        -ArgumentList $nativeArguments
    $text = $result.Text.Trim()
    if($result.ExitCode -ne 0) {
        throw "SurfaceFlinger profile adb command failed exitCode=$($result.ExitCode) arguments='$($Arguments -join ' ')' output='$text'"
    }
    if(-not $AllowEmpty -and [string]::IsNullOrWhiteSpace($text)) {
        throw "SurfaceFlinger profile adb command returned no output arguments='$($Arguments -join ' ')'"
    }
    return $text
}

function Get-NtkSurfaceFlingerExpectedValue {
    param(
        [Parameter(Mandatory = $true)]
        $ExpectedProfile,

        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$Name
    )

    $property = $ExpectedProfile.PSObject.Properties[$Name]
    if($null -eq $property) {
        throw "SurfaceFlinger profile manifest is missing required property '$Name'"
    }
    return $property.Value
}

function Assert-NtkSurfaceFlingerExpectedProfileDefinition {
    param(
        [Parameter(Mandatory = $true)]
        $ExpectedProfile
    )

    foreach($name in $script:NtkSurfaceFlingerProfileFields) {
        [void](Get-NtkSurfaceFlingerExpectedValue `
            -ExpectedProfile $ExpectedProfile -Name $name)
    }
}

function Get-NtkSurfaceFlingerProfileObservation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$DeviceSerial
    )

    $state = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("get-state")
    if($state -cne "device") {
        throw "SurfaceFlinger profile requires $DeviceSerial state=device; actual='$state'"
    }

    $display = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "dumpsys", "display")
    $surfaceFlinger = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "dumpsys", "SurfaceFlinger")
    $lateAppProperty = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "getprop", $script:NtkHighFpsLateAppPhaseProperty) `
        -AllowEmpty
    $lateSfProperty = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "getprop", $script:NtkHighFpsLateSfPhaseProperty) `
        -AllowEmpty

    $activeMode = [regex]::Match($display,
        '(?m)^[ \t]*mActiveSfDisplayMode=DisplayMode\{[^\r\n]*?\bvsyncRate=([0-9]+(?:\.[0-9]+)?)[^\r\n]*?\bappVsyncOffsetNanos=(-?[0-9]+)[^\r\n]*?\bpresentationDeadlineNanos=(-?[0-9]+)')
    $latePhase = [regex]::Match($surfaceFlinger,
        '(?m)^[ \t]*app phase:[ \t]+(-?[0-9]+)[ \t]+ns[ \t]+SF phase:[ \t]+(-?[0-9]+)[ \t]+ns[ \t]*$')
    $earlyPhase = [regex]::Match($surfaceFlinger,
        '(?m)^[ \t]*early app phase:[ \t]+(-?[0-9]+)[ \t]+ns[ \t]+early SF phase:[ \t]+(-?[0-9]+)[ \t]+ns[ \t]*$')
    $glEarlyPhase = [regex]::Match($surfaceFlinger,
        '(?m)^[ \t]*GL early app phase:[ \t]+(-?[0-9]+)[ \t]+ns[ \t]+GL early SF phase:[ \t]+(-?[0-9]+)[ \t]+ns[ \t]*$')
    $period = [regex]::Match($surfaceFlinger,
        '(?m)VSYNC period:[ \t]+([0-9]+)[ \t]+ns')
    $gles = [regex]::Match($surfaceFlinger,
        '(?im)^[ \t]*GLES:[ \t]*([^\r\n]+)')
    if(-not $activeMode.Success -or -not $latePhase.Success -or
            -not $earlyPhase.Success -or -not $glEarlyPhase.Success -or
            -not $period.Success -or -not $gles.Success) {
        throw "SurfaceFlinger profile could not parse exact active T/A/D, late/early/GL phases, or GLES"
    }

    $culture = [Globalization.CultureInfo]::InvariantCulture
    $displayPresentationDeadlineNanos =
        [long]::Parse($activeMode.Groups[3].Value, $culture)
    $frameTimelineDeadlineNanos =
        $displayPresentationDeadlineNanos - 1000000L
    if($frameTimelineDeadlineNanos -le 0) {
        throw "SurfaceFlinger display presentation deadline cannot produce a positive FrameTimeline D: displayDeadline=$displayPresentationDeadlineNanos"
    }
    return [pscustomobject][ordered]@{
        device = $DeviceSerial
        refreshHz = [double]::Parse($activeMode.Groups[1].Value, $culture)
        vsyncPeriodNanos = [long]::Parse($period.Groups[1].Value, $culture)
        appVsyncOffsetNanos = [long]::Parse($activeMode.Groups[2].Value, $culture)
        # Display deadline includes a historical 1 ms compositor allowance;
        # fixed phase authority is the exact FrameTimeline expected-deadline D.
        presentationDeadlineNanos = $frameTimelineDeadlineNanos
        lateAppPhaseNanos = [long]::Parse($latePhase.Groups[1].Value, $culture)
        lateSfPhaseNanos = [long]::Parse($latePhase.Groups[2].Value, $culture)
        earlyAppPhaseNanos = [long]::Parse($earlyPhase.Groups[1].Value, $culture)
        earlySfPhaseNanos = [long]::Parse($earlyPhase.Groups[2].Value, $culture)
        glEarlyAppPhaseNanos = [long]::Parse($glEarlyPhase.Groups[1].Value, $culture)
        glEarlySfPhaseNanos = [long]::Parse($glEarlyPhase.Groups[2].Value, $culture)
        highFpsLateAppPhaseOffsetProperty = $lateAppProperty.Trim()
        highFpsLateSfPhaseOffsetProperty = $lateSfProperty.Trim()
        gles = $gles.Groups[1].Value.Trim()
    }
}

function Get-NtkSurfaceFlingerProfileDifferences {
    param(
        [Parameter(Mandatory = $true)]
        $Observation,

        [Parameter(Mandatory = $true)]
        $ExpectedProfile
    )

    $differences = [Collections.Generic.List[string]]::new()
    foreach($name in $script:NtkSurfaceFlingerProfileFields) {
        $expected = Get-NtkSurfaceFlingerExpectedValue `
            -ExpectedProfile $ExpectedProfile -Name $name
        $actualProperty = $Observation.PSObject.Properties[$name]
        if($null -eq $actualProperty) {
            $differences.Add("$name actual='<missing>' expected='$expected'")
            continue
        }
        if([string]$actualProperty.Value -cne [string]$expected) {
            $differences.Add("$name actual='$($actualProperty.Value)' expected='$expected'")
        }
    }
    return @($differences)
}

function Assert-NtkSurfaceFlingerProfile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$DeviceSerial,

        [Parameter(Mandatory = $true)]
        $ExpectedProfile
    )

    Assert-NtkSurfaceFlingerExpectedProfileDefinition `
        -ExpectedProfile $ExpectedProfile
    $observation = Get-NtkSurfaceFlingerProfileObservation `
        -DeviceSerial $DeviceSerial
    $differences = @(Get-NtkSurfaceFlingerProfileDifferences `
        -Observation $observation -ExpectedProfile $ExpectedProfile)
    if($differences.Count -ne 0) {
        throw "SurfaceFlinger profile mismatch: $($differences -join '; ')"
    }
    return $observation
}

function Wait-NtkSurfaceFlingerAdbDevice {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DeviceSerial,

        [Parameter(Mandatory = $true)]
        [DateTime]$DeadlineUtc
    )

    do {
        $state = ""
        try {
            $state = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
                -Arguments @("get-state") -AllowEmpty
        } catch {
            $state = ""
        }
        if($state -ceq "device") {
            return
        }
        Start-Sleep -Milliseconds 250
    } while([DateTime]::UtcNow -lt $DeadlineUtc)
    throw "Timed out waiting for $DeviceSerial after adb root"
}

function Initialize-NtkSurfaceFlingerProfile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$DeviceSerial,

        [Parameter(Mandatory = $true)]
        $ExpectedProfile,

        [ValidateRange(1000, 120000)]
        [int]$TimeoutMilliseconds = 30000
    )

    # Validate the manifest completely before any possible adb mutation.
    Assert-NtkSurfaceFlingerExpectedProfileDefinition `
        -ExpectedProfile $ExpectedProfile
    $state = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("get-state")
    if($state -cne "device") {
        throw "SurfaceFlinger bootstrap requires $DeviceSerial state=device; actual='$state'"
    }

    $initialObservation = $null
    $initialDifferences = @()
    try {
        $initialObservation = Get-NtkSurfaceFlingerProfileObservation `
            -DeviceSerial $DeviceSerial
        $initialDifferences = @(Get-NtkSurfaceFlingerProfileDifferences `
            -Observation $initialObservation -ExpectedProfile $ExpectedProfile)
    } catch {
        $initialDifferences = @("observationError=$($_.Exception.Message)")
    }
    if($initialDifferences.Count -eq 0) {
        return [pscustomobject][ordered]@{
            bootstrapApplied = $false
            pollIntervalMilliseconds = 250
            mismatchBeforeBootstrap = @()
            observation = $initialObservation
        }
    }

    # The only mutation path is below this exact-profile mismatch guard.
    $deadlineUtc = [DateTime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    $rootOutput = Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("root") -AllowEmpty
    Wait-NtkSurfaceFlingerAdbDevice -DeviceSerial $DeviceSerial `
        -DeadlineUtc $deadlineUtc

    $expectedAppPhase = [string](Get-NtkSurfaceFlingerExpectedValue `
        -ExpectedProfile $ExpectedProfile `
        -Name "highFpsLateAppPhaseOffsetProperty")
    $expectedSfPhase = [string](Get-NtkSurfaceFlingerExpectedValue `
        -ExpectedProfile $ExpectedProfile `
        -Name "highFpsLateSfPhaseOffsetProperty")
    [void](Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "setprop", $script:NtkHighFpsLateAppPhaseProperty,
            $expectedAppPhase) -AllowEmpty)
    [void](Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "setprop", $script:NtkHighFpsLateSfPhaseProperty,
            $expectedSfPhase) -AllowEmpty)
    [void](Invoke-NtkSurfaceFlingerAdbText -DeviceSerial $DeviceSerial `
        -Arguments @("shell", "setprop", "ctl.restart", "surfaceflinger") `
        -AllowEmpty)

    $lastFailure = "SurfaceFlinger did not become observable"
    do {
        try {
            $bootCompleted = Invoke-NtkSurfaceFlingerAdbText `
                -DeviceSerial $DeviceSerial `
                -Arguments @("shell", "getprop", "sys.boot_completed") `
                -AllowEmpty
            if($bootCompleted.Trim() -ceq "1") {
                $observation = Get-NtkSurfaceFlingerProfileObservation `
                    -DeviceSerial $DeviceSerial
                $differences = @(Get-NtkSurfaceFlingerProfileDifferences `
                    -Observation $observation -ExpectedProfile $ExpectedProfile)
                if($differences.Count -eq 0) {
                    return [pscustomobject][ordered]@{
                        bootstrapApplied = $true
                        pollIntervalMilliseconds = 250
                        mismatchBeforeBootstrap = $initialDifferences
                        adbRootOutput = $rootOutput
                        observation = $observation
                    }
                }
                $lastFailure = $differences -join "; "
            } else {
                $lastFailure = "sys.boot_completed='$bootCompleted'"
            }
        } catch {
            $lastFailure = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 250
    } while([DateTime]::UtcNow -lt $deadlineUtc)

    throw "SurfaceFlinger exact-profile bootstrap timed out after ${TimeoutMilliseconds}ms: $lastFailure"
}

Export-ModuleMember -Function @(
    "Get-NtkSurfaceFlingerProfileObservation",
    "Assert-NtkSurfaceFlingerProfile",
    "Initialize-NtkSurfaceFlingerProfile"
)
