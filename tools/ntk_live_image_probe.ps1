param(
    [Parameter(Mandatory = $true)]
    [string]$WorkId,
    [Parameter(Mandatory = $true)]
    [string]$EpisodeId,
    [string]$Kind = "webtoon",
    [string]$Root = "https://sbxh9.com",
    [string]$RefererPath,
    [string]$OutDir = "build\ntk-live-image-probe",
    [string[]]$Hosts = @(
        "moamoabon.com",
        "flysky3m.com",
        "flysky4m.com",
        "fvcdn.com",
        "fvcdn1.com",
        "aws-cdn.site",
        "toonflix.app",
        "i.fvcdn.io",
        "img.fvcdn.io",
        "cdn.fvcdn.com"
    ),
    [string[]]$PathTemplates = @(
        "/blacktoon/episodes/{work}/{episode}/p001.jpg",
        "/black/episodes/{work}/{episode}/p001.jpg",
        "/webtoon/{work}/{episode}/p001.jpg",
        "/wt/episodes/{work}/{episode}/p001.jpg",
        "/blacktoon/episodes/{work}/{episode}/p001.webp",
        "/blacktoon/episodes/{work}/{episode}/p001.jpeg",
        "/blacktoon/episodes/{work}/{episode}/001.jpg",
        "/blacktoon/episodes/{work}/{episode}/1.jpg"
    ),
    [int]$TimeoutSeconds = 8,
    [switch]$StopOnFirstImage
)

$ErrorActionPreference = "Stop"
if(Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Safe-Name($Value) {
    return ($Value -replace "[^A-Za-z0-9._-]", "_")
}

if(-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    throw "Required command not found: curl.exe"
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$runDir = Join-Path $OutDir ("{0}_{1}_{2}_{3}" -f $timestamp, (Safe-Name $Kind), (Safe-Name $WorkId), (Safe-Name $EpisodeId))
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

if([string]::IsNullOrWhiteSpace($RefererPath)) {
    $RefererPath = "/$Kind/$WorkId/$EpisodeId"
}
$referer = $RefererPath
if($referer -notmatch "^https?://") {
    $referer = $Root.TrimEnd("/") + "/" + $referer.TrimStart("/")
}

$ua = "Mozilla/5.0 (Linux; Android 15; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
$results = New-Object System.Collections.Generic.List[object]
$found = $false

foreach($hostName in $Hosts) {
    foreach($template in $PathTemplates) {
        $path = $template.Replace("{work}", $WorkId).Replace("{episode}", $EpisodeId)
        $safe = Safe-Name ($hostName + $path)
        $headersPath = Join-Path $runDir ($safe + ".headers.txt")
        $bodyPath = Join-Path $runDir ($safe + ".body.bin")
        $url = "https://$hostName$path"

        $curlOut = & curl.exe -L -s -m $TimeoutSeconds -A $ua -e $referer -D $headersPath -o $bodyPath -w "%{http_code}`t%{content_type}`t%{size_download}`t%{url_effective}" $url 2>$null
        $parts = $curlOut -split "`t", 4
        $status = if($parts.Count -ge 1) { $parts[0] } else { "ERR" }
        $contentType = if($parts.Count -ge 2) { $parts[1] } else { "" }
        $sizeDownload = if($parts.Count -ge 3) { $parts[2] } else { "" }
        $effectiveUrl = if($parts.Count -ge 4) { $parts[3] } else { $url }
        $bytes = if(Test-Path $bodyPath) { (Get-Item $bodyPath).Length } else { 0 }
        $isImage = $status -eq "200" -and $contentType -like "image/*" -and $bytes -gt 0
        if($isImage) {
            $found = $true
            Write-Host ("FOUND_IMAGE status={0} type={1} bytes={2} url={3}" -f $status, $contentType, $bytes, $url)
        }

        $results.Add([pscustomobject]@{
            host = $hostName
            path = $path
            status = $status
            contentType = $contentType
            sizeDownload = $sizeDownload
            bytes = $bytes
            isImage = $isImage
            url = $url
            effectiveUrl = $effectiveUrl
        })

        if($isImage -and $StopOnFirstImage) {
            break
        }
    }
    if($found -and $StopOnFirstImage) {
        break
    }
}

$csvPath = Join-Path $runDir "summary.csv"
$jsonPath = Join-Path $runDir "summary.json"
$results | Export-Csv -NoTypeInformation -Encoding UTF8 $csvPath
[pscustomobject]@{
    workId = $WorkId
    episodeId = $EpisodeId
    kind = $Kind
    referer = $referer
    foundImage = $found
    resultCount = $results.Count
    imageCount = @($results | Where-Object { $_.isImage }).Count
    csv = $csvPath
    results = $results
} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $jsonPath

$results |
    Sort-Object @{ Expression = "isImage"; Descending = $true }, @{ Expression = "status"; Descending = $true }, @{ Expression = "bytes"; Descending = $true } |
    Format-Table -AutoSize host,path,status,contentType,bytes,isImage |
    Out-String -Width 240 |
    Write-Host

Write-Host ("summaryCsv={0}" -f $csvPath)
Write-Host ("summaryJson={0}" -f $jsonPath)
if(-not $found) {
    Write-Host "NO_IMAGE_BYTES_FOUND"
    exit 2
}
