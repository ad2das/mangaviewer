$ErrorActionPreference = "Stop"

$checks = @(
    @{
        Name = "Episode screen must not declare a ProgressBar"
        Path = "app/src/main/res/layout/activity_episode.xml"
        Pattern = "<ProgressBar|progressBarStyle"
    },
    @{
        Name = "EpisodeActivity must not drive a progress widget"
        Path = "app/src/main/java/ml/melun/mangaview/activity/EpisodeActivity.java"
        Pattern = "ProgressBar|progress\.setVisibility|R\.id\.progress"
    },
    @{
        Name = "Primary strip viewer must not show placeholder as loading image"
        Path = "app/src/main/java/ml/melun/mangaview/adapter/StripAdapter.java"
        Pattern = "setImageResource\(R\.drawable\.placeholder\)|placeholder\(R\.drawable\.placeholder\)"
    },
    @{
        Name = "Fragment viewer must not show placeholder as loading image"
        Path = "app/src/main/java/ml/melun/mangaview/fragment/ViewerPageFragment.java"
        Pattern = "setImageResource\(R\.drawable\.placeholder\)|placeholder\(R\.drawable\.placeholder\)"
    },
    @{
        Name = "Legacy page viewer must not show placeholder as loading image"
        Path = "app/src/main/java/ml/melun/mangaview/activity/ViewerActivity2.java"
        Pattern = "setImageResource\(R\.drawable\.placeholder\)|placeholder\(R\.drawable\.placeholder\)"
    }
)

$failed = $false
foreach($check in $checks) {
    if(-not (Test-Path $check.Path)) {
        Write-Error "Missing audited file: $($check.Path)"
        $failed = $true
        continue
    }
    $matches = Select-String -Path $check.Path -Pattern $check.Pattern
    if($matches) {
        Write-Host "[FAIL] $($check.Name)"
        $matches | ForEach-Object { Write-Host "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
        $failed = $true
    } else {
        Write-Host "[PASS] $($check.Name)"
    }
}

if($failed) {
    exit 1
}
