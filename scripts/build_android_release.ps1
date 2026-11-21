# Use CI-injected credentials or the current Windows user's protected release credential.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$local = Join-Path $env:LOCALAPPDATA 'TimePin/signing'
$priorStore = $env:TIMEPIN_RELEASE_STORE
$priorPassword = $env:TIMEPIN_RELEASE_PASSWORD
try {
    if (-not $priorStore -and -not $priorPassword) {
        $env:TIMEPIN_RELEASE_STORE = Join-Path $local 'release.p12'
        $protected = Get-Content -Raw -LiteralPath (Join-Path $local 'release.dpapi')
        $secure = ConvertTo-SecureString $protected.Trim()
        $env:TIMEPIN_RELEASE_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
    }
    & (Join-Path $repo 'android/gradlew.bat') -p (Join-Path $repo 'android') :app:assembleRelease @args
    if ($LASTEXITCODE) { throw "Release build failed ($LASTEXITCODE)" }
} finally {
    $env:TIMEPIN_RELEASE_STORE = $priorStore
    $env:TIMEPIN_RELEASE_PASSWORD = $priorPassword
}
