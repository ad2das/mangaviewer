Set-StrictMode -Version Latest

function Invoke-NtkNativeCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$FilePath,

        [AllowEmptyCollection()]
        [string[]]$ArgumentList = @()
    )

    # Windows PowerShell 5.1 converts redirected native stderr into error
    # records.  With a caller's ErrorActionPreference=Stop, even a successful
    # command such as adb pull can otherwise terminate before LASTEXITCODE is
    # observed.  Keep both preference changes local to this invocation.
    $previousErrorActionPreference = $ErrorActionPreference
    $nativeErrorPreference = Get-Variable `
        -Name PSNativeCommandUseErrorActionPreference `
        -ErrorAction SilentlyContinue
    $hasNativeErrorPreference = $null -ne $nativeErrorPreference
    $previousNativeErrorPreference = if($hasNativeErrorPreference) {
        $nativeErrorPreference.Value
    } else {
        $null
    }
    $rawOutput = @()
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        if($hasNativeErrorPreference) {
            Set-Variable -Name PSNativeCommandUseErrorActionPreference `
                -Value $false -Scope Local
        }
        $rawOutput = @(& $FilePath @ArgumentList 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        if($hasNativeErrorPreference) {
            Set-Variable -Name PSNativeCommandUseErrorActionPreference `
                -Value $previousNativeErrorPreference -Scope Local
        }
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if($null -eq $exitCode) {
        throw "Native command did not publish LASTEXITCODE: $FilePath"
    }
    [string[]]$lines = @($rawOutput | ForEach-Object { [string]$_ })
    return [pscustomobject][ordered]@{
        FilePath = $FilePath
        ExitCode = [int]$exitCode
        Lines = $lines
        Text = ($lines -join "`n")
    }
}

function Invoke-NtkNativeCommandLive {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$FilePath,

        [AllowEmptyCollection()]
        [string[]]$ArgumentList = @()
    )

    # Keep child stdout/stderr live while preventing native stderr records from
    # inheriting the qualification script's terminating-error preference.
    $previousErrorActionPreference = $ErrorActionPreference
    $nativeErrorPreference = Get-Variable `
        -Name PSNativeCommandUseErrorActionPreference `
        -ErrorAction SilentlyContinue
    $hasNativeErrorPreference = $null -ne $nativeErrorPreference
    $previousNativeErrorPreference = if($hasNativeErrorPreference) {
        $nativeErrorPreference.Value
    } else {
        $null
    }
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        if($hasNativeErrorPreference) {
            Set-Variable -Name PSNativeCommandUseErrorActionPreference `
                -Value $false -Scope Local
        }
        & $FilePath @ArgumentList | Out-Host
        $exitCode = $LASTEXITCODE
    } finally {
        if($hasNativeErrorPreference) {
            Set-Variable -Name PSNativeCommandUseErrorActionPreference `
                -Value $previousNativeErrorPreference -Scope Local
        }
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if($null -eq $exitCode) {
        throw "Live native command did not publish LASTEXITCODE: $FilePath"
    }
    return [int]$exitCode
}

function Get-NtkPinnedAapt2Path {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$SdkRoot
    )

    # System.Version provides numeric, culture-independent identity.  Do not
    # choose a build-tools directory by lexical path ordering.
    $pinnedVersion = [Version]::Parse("35.0.0")
    $pinnedDirectoryName = $pinnedVersion.ToString(3)
    $aapt2Path = Join-Path $SdkRoot `
        ("build-tools\{0}\aapt2.exe" -f $pinnedDirectoryName)
    if(-not (Test-Path -LiteralPath $aapt2Path -PathType Leaf)) {
        throw "Pinned Android build-tools $pinnedDirectoryName aapt2.exe not found below SDK: $SdkRoot"
    }
    return (Get-Item -LiteralPath $aapt2Path -Force).FullName
}

Export-ModuleMember -Function @(
    "Invoke-NtkNativeCommand",
    "Invoke-NtkNativeCommandLive",
    "Get-NtkPinnedAapt2Path"
)
