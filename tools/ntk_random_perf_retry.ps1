$ErrorActionPreference = "Stop"
if(Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$maxRetries = 2
$perfArgs = New-Object System.Collections.Generic.List[string]
for($i = 0; $i -lt $args.Count; $i++) {
    $arg = [string]$args[$i]
    if($arg -eq "-MaxInconclusiveRetries") {
        if($i + 1 -ge $args.Count) {
            throw "-MaxInconclusiveRetries requires a value"
        }
        $maxRetries = [int]$args[$i + 1]
        $i++
        continue
    }
    $perfArgs.Add($arg)
}

function Arg-Value([string[]]$ArgsList, [string]$Name, [string]$DefaultValue) {
    for($i = 0; $i -lt $ArgsList.Count; $i++) {
        if([string]$ArgsList[$i] -eq $Name -and $i + 1 -lt $ArgsList.Count) {
            return [string]$ArgsList[$i + 1]
        }
    }
    return $DefaultValue
}

function Has-Arg([string[]]$ArgsList, [string]$Name) {
    foreach($arg in $ArgsList) {
        if([string]$arg -eq $Name) {
            return $true
        }
    }
    return $false
}

function Add-Arg-If-Missing([string[]]$ArgsList, [string]$Name, [string]$Value) {
    if([string]::IsNullOrWhiteSpace($Value) -or (Has-Arg $ArgsList $Name)) {
        return $ArgsList
    }
    return @($ArgsList + @($Name, $Value))
}

function Latest-Summary([string]$OutDir, [datetime]$After) {
    $summary = Get-ChildItem -Path $OutDir -Recurse -Filter "summary.json" -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $After } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if($null -eq $summary) {
        return $null
    }
    return $summary.FullName
}

function Is-NoScroll([object]$Summary) {
    if($null -eq $Summary.scroll -or $Summary.scroll.Count -eq 0) {
        return $false
    }
    $noMovementCount = 0
    foreach($scroll in @($Summary.scroll)) {
        $before = [string]$scroll.progressBefore
        $afterQuiet = [string]$scroll.progressAfterQuiet
        $afterSettle = [string]$scroll.progressAfterSettle
        if($before -match "^0:0@0" -and $afterQuiet -match "^0:0@0" -and $afterSettle -match "^0:0@0") {
            $noMovementCount++
        }
    }
    return ($noMovementCount -eq @($Summary.scroll).Count)
}

function Is-StartupStall([object]$Summary) {
    if($null -eq $Summary -or -not $Summary.PSObject.Properties.Name.Contains("logcat")) {
        return $false
    }
    $logPath = [string]$Summary.logcat
    if([string]::IsNullOrWhiteSpace($logPath) -or -not (Test-Path $logPath)) {
        return $false
    }
    $logText = Get-Content -Path $logPath -Raw
    $activity = [regex]::Match($logText, "reader_activity_create_from_launch[^\r\n]*\bms=(\d+)")
    if($activity.Success -and [int64]$activity.Groups[1].Value -gt 8000L) {
        return $true
    }
    $displayed = [regex]::Match($logText, "Displayed ml\.melun\.mangaview/\.activity\.ReaderV2Activity[^\r\n]*\+(\d+)s(\d+)ms")
    if($displayed.Success) {
        $displayedMs = ([int64]$displayed.Groups[1].Value * 1000L) + [int64]$displayed.Groups[2].Value
        if($displayedMs -gt 15000L) {
            return $true
        }
    }
    return $false
}

function Is-Inconclusive([object]$Summary) {
    if($null -eq $Summary) {
        return $true
    }
    $failureText = (@($Summary.failures) -join "`n")
    if($failureText -match "Could not launch intent|startActivitySync|missing frame stats|NTK_ENSURE_ACCESS_FAILED") {
        return $true
    }
    foreach($scroll in @($Summary.scroll)) {
        if([string]$scroll.frameStats -eq "null") {
            return $true
        }
    }
    if(Is-NoScroll $Summary) {
        return $true
    }
    if(Is-StartupStall $Summary) {
        return $true
    }
    return $false
}

$outDir = Arg-Value $perfArgs.ToArray() "-OutDir" "build\ntk-random-perf"
$scriptPath = Join-Path $PSScriptRoot "ntk_random_perf.ps1"
$perfArgArray = @($perfArgs | ForEach-Object { [string]$_ })
$attempt = 0
$lastExit = 1
$lastSummaryPath = $null
$hasSkipBuild = $perfArgArray -contains "-SkipBuild"
$hasSkipInstall = $perfArgArray -contains "-SkipInstall"

while($true) {
    $attempt++
    $attemptStartedAt = Get-Date
    Write-Host ("NTK retry wrapper attempt {0}/{1}" -f $attempt, ($maxRetries + 1))
    $attemptArgs = @($perfArgArray)
    if($attempt -gt 1) {
        if(-not $hasSkipBuild) {
            $attemptArgs += "-SkipBuild"
        }
        if(-not $hasSkipInstall) {
            $attemptArgs += "-SkipInstall"
        }
    }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath @attemptArgs
    $lastExit = $LASTEXITCODE
    $lastSummaryPath = Latest-Summary $outDir $attemptStartedAt
    $summary = $null
    if($lastSummaryPath -and (Test-Path $lastSummaryPath)) {
        $summary = Get-Content -Path $lastSummaryPath -Raw | ConvertFrom-Json
        Write-Host ("  latestSummary={0}" -f $lastSummaryPath)
    } else {
        Write-Host "  latestSummary=<missing>"
    }
    $inconclusive = Is-Inconclusive $summary
    Write-Host ("  inconclusive={0} exitCode={1}" -f $inconclusive, $lastExit)
    if(-not $inconclusive -or $attempt -gt $maxRetries) {
        break
    }
    if($summary -ne $null) {
        $perfArgArray = Add-Arg-If-Missing $perfArgArray "-Seed" ([string]$summary.seed)
        $failurePath = [string]$summary.failurePath
        if(-not [string]::IsNullOrWhiteSpace($failurePath)) {
            $perfArgArray = Add-Arg-If-Missing $perfArgArray "-TargetEpisodePath" $failurePath
        }
    }
    Write-Host "  retrying same seed/path because the run was inconclusive"
}

if($lastSummaryPath) {
    Write-Host ("NTK retry wrapper finalSummary={0}" -f $lastSummaryPath)
}
exit $lastExit
