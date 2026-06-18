param(
    [Parameter(Mandatory=$true)]
    [string]$RunDir,

    [int]$MaxEvents = 140
)

$ErrorActionPreference = "Stop"

$logcatPath = Join-Path $RunDir "logcat.txt"
$summaryPath = Join-Path $RunDir "summary.json"
if(-not (Test-Path $logcatPath)) {
    throw "Missing logcat: $logcatPath"
}

function Parse-Kv($Text) {
    $result = [ordered]@{}
    if(-not $Text) { return $result }
    $Text = $Text.Trim()
    if($Text.StartsWith(",")) {
        $Text = $Text.Substring(1)
    }
    $parts = $Text -split ",(?=[A-Za-z0-9_]+=)"
    foreach($part in $parts) {
        $idx = $part.IndexOf("=")
        if($idx -lt 0) { continue }
        $key = $part.Substring(0, $idx).Trim()
        $value = $part.Substring($idx + 1).Trim()
        if($key.Length -gt 0) {
            $result[$key] = $value
        }
    }
    return $result
}

function Parse-LogLine($Line) {
    if($Line -notmatch "^(?<time>\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d{3}).*?ViewerPerf\(\s*\d+\):\s+(?<event>[A-Za-z0-9_]+)\s*(?<rest>.*)$") {
        return $null
    }
    $rest = $Matches.rest.Trim()
    $kv = Parse-Kv $rest
    [pscustomobject]@{
        Time = $Matches.time
        Event = $Matches.event
        Path = if($kv.Contains("path")) { $kv["path"] } elseif($kv.Contains("targetPath")) { $kv["targetPath"] } elseif($kv.Contains("nextPath")) { $kv["nextPath"] } elseif($kv.Contains("previousPath")) { $kv["previousPath"] } else { "" }
        Direction = if($kv.Contains("direction")) { $kv["direction"] } else { "" }
        Result = if($kv.Contains("result")) { $kv["result"] } else { "" }
        Images = if($kv.Contains("images")) { $kv["images"] } elseif($kv.Contains("count")) { $kv["count"] } else { "" }
        Ms = if($kv.Contains("ms")) { $kv["ms"] } elseif($kv.Contains("totalMs")) { $kv["totalMs"] } else { "" }
        Success = if($kv.Contains("success")) { $kv["success"] } else { "" }
        Before = if($kv.Contains("before")) { $kv["before"] } else { "" }
        After = if($kv.Contains("after")) { $kv["after"] } else { "" }
        Progress = if($kv.Contains("progress")) { $kv["progress"] } else { "" }
        Raw = $Line
    }
}

$interesting = @(
    "ntk_true_random_case_start",
    "ntk_webview_ack_preflight_enter",
    "ntk_webview_ack_preflight_done",
    "ntk_webview_ack_preflight_stage",
    "ntk_images_api_pre_ack_try",
    "ntk_images_api_skip_unacked",
    "ntk_images_api_partial_urls",
    "ntk_images_api_trusted_result",
    "reader_early_ntk_urls_remember",
    "append_adjacent_start",
    "append_adjacent_target",
    "append_adjacent_verified_fetch_start",
    "append_adjacent_verified_fetch",
    "append_adjacent_early_generated_installed",
    "append_adjacent_early_api_handoff",
    "append_adjacent_resolved_inserted",
    "ntk_true_random_append_next",
    "ntk_true_random_append_previous",
    "ntk_true_random_append_previous_probe"
)

$events = Get-Content $logcatPath |
    ForEach-Object { Parse-LogLine $_ } |
    Where-Object { $_ -and $interesting -contains $_.Event }

if(Test-Path $summaryPath) {
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    Write-Host ("summary passed={0} seed={1} firstDrawable={2}" -f `
        $summary.passed, $summary.seed, (($summary.firstDrawable | Select-Object -First 1).ms))
    if($summary.failures) {
        Write-Host "failures:"
        $summary.failures | Select-Object -First 3 | ForEach-Object { Write-Host ("  " + $_) }
    }
}

Write-Host "timeline:"
$displayEvents = @($events)
if($MaxEvents -gt 0 -and $displayEvents.Count -gt $MaxEvents) {
    $headCount = [Math]::Min(35, [Math]::Floor($MaxEvents / 3))
    $tailCount = $MaxEvents - $headCount
    $displayEvents = @($displayEvents | Select-Object -First $headCount) +
        @([pscustomobject]@{
            Time = "--"
            Event = "timeline_omitted"
            Path = ""
            Direction = ""
            Result = ""
            Images = ""
            Ms = ""
            Success = ""
            Before = ""
            After = ""
            Progress = ""
            Raw = ""
            Omitted = $events.Count - $MaxEvents
        }) +
        @($displayEvents | Select-Object -Last $tailCount)
}

$displayEvents | ForEach-Object {
    if($_.Event -eq "timeline_omitted") {
        Write-Host ("  -- timeline_omitted events={0}; use -MaxEvents 0 for full output" -f $_.Omitted)
        return
    }
    $bits = @($_.Time, $_.Event)
    if($_.Direction) { $bits += "dir=$($_.Direction)" }
    if($_.Path) { $bits += "path=$($_.Path)" }
    if($_.Result) { $bits += "result=$($_.Result)" }
    if($_.Images) { $bits += "images=$($_.Images)" }
    if($_.Ms) { $bits += "ms=$($_.Ms)" }
    if($_.Success) { $bits += "success=$($_.Success)" }
    if($_.Before -or $_.After) { $bits += "pages=$($_.Before)->$($_.After)" }
    if($_.Progress) { $bits += "progress=$($_.Progress)" }
    Write-Host ("  " + ($bits -join " "))
}
