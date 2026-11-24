[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RepositoryPath
)

$ErrorActionPreference = 'Stop'

function Invoke-GitText {
    param([string]$Repo, [string[]]$GitArgs)
    $output = & git -C $Repo @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($GitArgs -join ' ') failed: $output"
    }
    return ($output | Out-String).Trim()
}

$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$repoRoot = (Resolve-Path -LiteralPath $RepositoryPath).Path
$manifestPath = Join-Path $sourceRoot 'SOURCE-MANIFEST-SHA256.txt'

if (!(Test-Path -LiteralPath (Join-Path $repoRoot '.git'))) {
    throw "RepositoryPath is not a Git working copy (.git was not found): $repoRoot"
}

$gitTop = Invoke-GitText -Repo $repoRoot -GitArgs @('rev-parse', '--show-toplevel')
$gitTop = (Resolve-Path -LiteralPath $gitTop).Path
if ($gitTop.TrimEnd('\') -ne $repoRoot.TrimEnd('\')) {
    throw "RepositoryPath must be the repository root. Git root is: $gitTop"
}

$branch = Invoke-GitText -Repo $repoRoot -GitArgs @('branch', '--show-current')
if ($branch -ne 'v2/full-source') {
    throw "Safety stop: current branch is '$branch'. Checkout exactly 'v2/full-source' before applying V2."
}

$status = Invoke-GitText -Repo $repoRoot -GitArgs @('status', '--porcelain')
if ($status) {
    throw "Safety stop: Git working tree is not clean. Commit/stash your changes before applying the snapshot.`n$status"
}

$required = @(
    'settings.gradle',
    'build.gradle',
    'app/build.gradle',
    'core/build.gradle',
    '.gitignore',
    '.gitattributes',
    '.github/workflows/build-apk.yml',
    'app/src/main/java/com/neonmaze/app/GameRenderer.java',
    'app/src/main/res/drawable-nodpi/prism_floor.webp',
    'design-source/reference/prism-industrial-surface-source.png',
    'core/src/main/java/com/neonmaze/core/GameEngine.java'
)
foreach ($relative in $required) {
    if (!(Test-Path -LiteralPath (Join-Path $sourceRoot $relative) -PathType Leaf)) {
        throw "Required V2 source file is missing: $relative"
    }
}
if (!(Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw 'SOURCE-MANIFEST-SHA256.txt is missing.'
}

$manifest = @{}
foreach ($line in Get-Content -LiteralPath $manifestPath) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line -notmatch '^([0-9a-fA-F]{64})  (.+)$') {
        throw "Invalid manifest line: $line"
    }
    $expectedHash = $Matches[1].ToLowerInvariant()
    $relative = $Matches[2].Replace('/', '\')
    if ($relative.StartsWith('\') -or $relative -match '(^|\\)\.\.(\\|$)' -or $relative.Contains(':')) {
        throw "Unsafe manifest path: $relative"
    }
    $sourceFile = Join-Path $sourceRoot $relative
    if (!(Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Manifest file is missing: $relative"
    }
    $actualHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Source SHA-256 mismatch: $relative"
    }
    $manifest[$relative] = $expectedHash
}
if ($manifest.Count -lt 80) {
    throw "Unexpectedly small V2 manifest ($($manifest.Count) files). Refusing to copy."
}

Write-Host "Source manifest verified: $($manifest.Count) files." -ForegroundColor Green
Write-Host "Target repository: $repoRoot"
Write-Host "Target branch: $branch"

# Remove only old tracked files absent from the verified V2 manifest.
# The .git database is not tracked and is never included in this operation.
$trackedText = Invoke-GitText -Repo $repoRoot -GitArgs @('ls-files')
$removed = 0
foreach ($trackedPath in ($trackedText -split "`r?`n")) {
    if ([string]::IsNullOrWhiteSpace($trackedPath)) { continue }
    $normalized = $trackedPath.Replace('/', '\')
    if (!$manifest.ContainsKey($normalized)) {
        $oldFile = Join-Path $repoRoot $normalized
        if (Test-Path -LiteralPath $oldFile -PathType Leaf) {
            Remove-Item -LiteralPath $oldFile -Force
            $removed++
        }
    }
}

# Copy exactly the files covered by the verified source manifest.
$copied = 0
foreach ($entry in $manifest.GetEnumerator()) {
    $relative = $entry.Key
    $from = Join-Path $sourceRoot $relative
    $to = Join-Path $repoRoot $relative
    $parent = Split-Path -Parent $to
    if (!(Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $from -Destination $to -Force
    $copied++
}

# Verify every copied file against the same source manifest.
foreach ($entry in $manifest.GetEnumerator()) {
    $to = Join-Path $repoRoot $entry.Key
    if (!(Test-Path -LiteralPath $to -PathType Leaf)) {
        throw "Destination file is missing after copy: $($entry.Key)"
    }
    $actualHash = (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $entry.Value) {
        throw "Destination SHA-256 mismatch: $($entry.Key)"
    }
}

Write-Host "V2 snapshot copied and verified: $copied files." -ForegroundColor Green
Write-Host "Obsolete tracked files removed: $removed"
Write-Host 'The .git directory and branch history were preserved.' -ForegroundColor Green
Write-Host ''
Write-Host 'Next: review GitHub Desktop Changes, commit, then Push origin.' -ForegroundColor Cyan
& git -C $repoRoot status --short
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read final Git status.'
}
