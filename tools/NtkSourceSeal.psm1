Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-NtkGitText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot,
        [Parameter(Mandatory = $true)]
        [string]$Arguments
    )

    $git = (Get-Command git.exe -ErrorAction Stop).Source
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $git
    $startInfo.WorkingDirectory = $RepositoryRoot
    $startInfo.Arguments = $Arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false, $true)
    $startInfo.StandardErrorEncoding = [Text.UTF8Encoding]::new($false, $true)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if(-not $process.Start()) {
            throw "Unable to start git $Arguments"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0) {
            throw "git $Arguments failed exitCode=$($process.ExitCode): $($stderr.Trim())"
        }
        return $stdout
    } finally {
        $process.Dispose()
    }
}

function Test-NtkGeneratedSourcePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if($Path.StartsWith(".codex-remote-attachments/", [StringComparison]::Ordinal) -or
            $Path.StartsWith("app/.cxx/", [StringComparison]::Ordinal) -or
            $Path.StartsWith(".gradle/", [StringComparison]::Ordinal)) {
        return $true
    }
    $segments = $Path.Split('/')
    for($index = 0; $index -lt ($segments.Length - 1); $index++) {
        if($segments[$index] -ceq "build") {
            return $true
        }
    }
    return $false
}

function Get-NtkGitSourcePaths {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $raw = Invoke-NtkGitText $RepositoryRoot `
        "ls-files -z --cached --others --exclude-standard"
    $paths = New-Object System.Collections.Generic.List[string]
    foreach($entry in $raw.Split(@([char]0),
            [StringSplitOptions]::RemoveEmptyEntries)) {
        $path = ([string]$entry).Replace('\', '/')
        if([string]::IsNullOrWhiteSpace($path) -or
                [IO.Path]::IsPathRooted($path) -or
                $path -match '(^|/)\.\.(/|$)') {
            throw "git returned an invalid source-seal path '$path'"
        }
        # `git ls-files --cached` intentionally reports tracked deletions.  A source seal is an
        # identity of the candidate bytes that actually exist, so a deleted tracked file must be
        # absent from the file set (and therefore from the composite hash), not treated as a file
        # that mysteriously vanished during hashing.
        $fullPath = Join-Path $RepositoryRoot `
            ($path.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if((Test-Path -LiteralPath $fullPath -PathType Leaf) -and
                -not (Test-NtkGeneratedSourcePath $path)) {
            $paths.Add($path)
        }
    }

    # These ignored files are still real Gradle inputs: their presence changes whether the
    # Google Services plugin runs and/or which resources are packaged. Include their current
    # bytes explicitly so an ignored local build input cannot escape the source provenance seal.
    foreach($path in @(
        "app/google-services.json",
        "app/src/main/res/values/google_services.xml"
    )) {
        $fullPath = Join-Path $RepositoryRoot `
            ($path.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if((Test-Path -LiteralPath $fullPath -PathType Leaf) -and
                -not $paths.Contains($path)) {
            $paths.Add($path)
        }
    }
    $array = $paths.ToArray()
    [Array]::Sort($array, [StringComparer]::Ordinal)
    for($index = 1; $index -lt $array.Length; $index++) {
        if($array[$index - 1] -ceq $array[$index]) {
            throw "git returned a duplicate source-seal path '$($array[$index])'"
        }
    }
    return $array
}

function Get-NtkNativeSourceTreeSha256 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $Root -ErrorAction Stop).Path
    [string[]]$lines = @(
        Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File |
            Where-Object {
                $_.Name -ceq "CMakeLists.txt" -or
                $_.Extension -cin @(".c", ".cc", ".cpp", ".h", ".hpp")
            } |
            ForEach-Object {
                $relative = $_.FullName.Substring($resolvedRoot.Length).
                    TrimStart('\', '/').Replace('\', '/')
                $fileSha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).
                    Hash.ToLowerInvariant()
                "$relative=$fileSha256"
            }
    )
    if($lines.Length -eq 0) {
        throw "Native source tree contains no hashable files: $resolvedRoot"
    }

    # Sort-Object changed case ordering between Windows PowerShell 5.1 and PowerShell 7.
    # Ordinal relative-path ordering plus '/' separators makes this composite hash independent
    # of PowerShell version, current culture, and the host path separator.
    [Array]::Sort($lines, [StringComparer]::Ordinal)
    $bytes = [Text.UTF8Encoding]::new($false, $true).GetBytes(($lines -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).
            Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-NtkUInt32BigEndian([uint32]$Value) {
    $bytes = [BitConverter]::GetBytes($Value)
    if([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
    return ,$bytes
}

function Get-NtkUInt64BigEndian([uint64]$Value) {
    $bytes = [BitConverter]::GetBytes($Value)
    if([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
    return ,$bytes
}

function Add-NtkHashBytes {
    param(
        [Parameter(Mandatory = $true)]
        [Security.Cryptography.HashAlgorithm]$Hash,
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes,
        [Parameter(Mandatory = $true)]
        [int]$Count
    )

    if($Count -gt 0) {
        [void]$Hash.TransformBlock($Bytes, 0, $Count, $Bytes, 0)
    }
}

function Get-NtkSourceSeal {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    $root = (Resolve-Path -LiteralPath $RepositoryRoot -ErrorAction Stop).Path
    $gitRoot = (Invoke-NtkGitText $root "rev-parse --show-toplevel").Trim()
    $resolvedGitRoot = (Resolve-Path -LiteralPath $gitRoot -ErrorAction Stop).Path
    if($resolvedGitRoot -ine $root) {
        throw "RepositoryRoot must be the git worktree root: requested='$root' git='$resolvedGitRoot'"
    }

    $pathsBefore = @(Get-NtkGitSourcePaths $root)
    $composite = [Security.Cryptography.SHA256]::Create()
    $records = New-Object System.Collections.Generic.List[object]
    $buffer = New-Object byte[] (1024 * 1024)
    try {
        $header = [Text.Encoding]::ASCII.GetBytes("ntk-source-seal-v1`0")
        Add-NtkHashBytes $composite $header $header.Length
        foreach($path in $pathsBefore) {
            $fullPath = Join-Path $root ($path.Replace('/', [IO.Path]::DirectorySeparatorChar))
            if(-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
                throw "Source-seal file disappeared after git enumeration: $path"
            }
            $pathBytes = [Text.UTF8Encoding]::new($false, $true).GetBytes($path)
            $stream = [IO.File]::Open($fullPath, [IO.FileMode]::Open,
                [IO.FileAccess]::Read, [IO.FileShare]::Read)
            $fileHash = [Security.Cryptography.SHA256]::Create()
            try {
                [uint64]$length = [uint64]$stream.Length
                $pathLengthBytes = Get-NtkUInt32BigEndian ([uint32]$pathBytes.Length)
                $fileLengthBytes = Get-NtkUInt64BigEndian $length
                Add-NtkHashBytes $composite $pathLengthBytes $pathLengthBytes.Length
                Add-NtkHashBytes $composite $pathBytes $pathBytes.Length
                Add-NtkHashBytes $composite $fileLengthBytes $fileLengthBytes.Length
                [uint64]$readTotal = 0
                while(($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    [void]$composite.TransformBlock($buffer, 0, $read, $buffer, 0)
                    [void]$fileHash.TransformBlock($buffer, 0, $read, $buffer, 0)
                    $readTotal += [uint64]$read
                }
                if($readTotal -ne $length) {
                    throw "Source-seal file length changed while hashing: $path expected=$length read=$readTotal"
                }
                [void]$fileHash.TransformFinalBlock((New-Object byte[] 0), 0, 0)
                $fileSha256 = ([BitConverter]::ToString($fileHash.Hash)).Replace("-", "").ToLowerInvariant()
                $records.Add([pscustomobject][ordered]@{
                    path = $path
                    length = [long]$length
                    sha256 = $fileSha256
                })
            } finally {
                $fileHash.Dispose()
                $stream.Dispose()
            }
        }
        [void]$composite.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        $sha256 = ([BitConverter]::ToString($composite.Hash)).Replace("-", "").ToLowerInvariant()
    } finally {
        $composite.Dispose()
    }

    $pathsAfter = @(Get-NtkGitSourcePaths $root)
    if($pathsBefore.Count -ne $pathsAfter.Count) {
        throw "Source file set changed while hashing: before=$($pathsBefore.Count) after=$($pathsAfter.Count)"
    }
    for($index = 0; $index -lt $pathsBefore.Count; $index++) {
        if([string]$pathsBefore[$index] -cne [string]$pathsAfter[$index]) {
            throw "Source file set changed while hashing at index=$index before='$($pathsBefore[$index])' after='$($pathsAfter[$index])'"
        }
    }

    return [pscustomobject][ordered]@{
        sha256 = $sha256
        fileCount = $records.Count
        files = @($records.ToArray())
    }
}

Export-ModuleMember -Function Get-NtkSourceSeal, Get-NtkNativeSourceTreeSha256
