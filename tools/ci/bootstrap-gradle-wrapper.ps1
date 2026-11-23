# Fetch the official Gradle 9.6.0 Wrapper JAR and verify it before installation.
# curl.exe is used deliberately so HTTPS is enforced across every redirect.
$ErrorActionPreference = 'Stop'

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$WrapperDir = Join-Path $RootDir 'gradle\wrapper'
$PropertiesPath = Join-Path $WrapperDir 'gradle-wrapper.properties'
$Destination = Join-Path $WrapperDir 'gradle-wrapper.jar'
$DownloadUrl = 'https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar'
$ExpectedSha256 = '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7'
$ExpectedDistributionSha256 = 'bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01'

if (-not (Test-Path -LiteralPath $PropertiesPath -PathType Leaf)) {
    throw 'gradle-wrapper.properties is missing.'
}
$Properties = Get-Content -LiteralPath $PropertiesPath -Raw
if ($Properties -notmatch '(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-9\.6\.0-bin\.zip\s*$') {
    throw 'Wrapper distribution URL does not match the pinned Gradle 9.6.0 release.'
}
if ($Properties -notmatch "(?m)^distributionSha256Sum=$ExpectedDistributionSha256\s*$") {
    throw 'Wrapper distribution checksum is missing or unexpected.'
}

New-Item -ItemType Directory -Force -Path $WrapperDir | Out-Null
if (Test-Path -LiteralPath $Destination -PathType Leaf) {
    $ExistingHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ExistingHash -eq $ExpectedSha256) {
        Write-Host "Gradle Wrapper JAR is already present and verified ($ExistingHash)."
        exit 0
    }
    Write-Warning 'Existing Wrapper JAR checksum is invalid; it will be replaced only after a verified download.'
}

$Curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
if ($null -eq $Curl) {
    throw 'curl.exe is required (available by default on supported Windows 10/11 installations).'
}

$Temporary = Join-Path $WrapperDir ('.gradle-wrapper.jar.' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    $CurlArguments = @(
        '--fail', '--location', '--silent', '--show-error',
        '--proto', '=https', '--proto-redir', '=https', '--tlsv1.2',
        '--retry', '3', '--retry-delay', '2',
        '--connect-timeout', '20', '--max-time', '120',
        '--output', $Temporary, $DownloadUrl
    )
    & $Curl.Source @CurlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Official Gradle Wrapper download failed (curl exit $LASTEXITCODE)."
    }

    $DownloadedHash = (Get-FileHash -LiteralPath $Temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($DownloadedHash -ne $ExpectedSha256) {
        throw "Gradle Wrapper JAR SHA-256 mismatch. Expected $ExpectedSha256; received $DownloadedHash."
    }
    Move-Item -LiteralPath $Temporary -Destination $Destination -Force
    Write-Host 'Installed the official Gradle 9.6.0 Wrapper JAR after SHA-256 verification.'
}
finally {
    if (Test-Path -LiteralPath $Temporary) {
        Remove-Item -LiteralPath $Temporary -Force
    }
}
