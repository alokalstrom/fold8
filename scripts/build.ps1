param([string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed; no release artifact copied.' }
    New-Item -ItemType Directory -Force artifacts | Out-Null
    $metadata = Get-Content -LiteralPath 'app\build\outputs\apk\debug\output-metadata.json' -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    if ($version -notmatch '^[a-zA-Z0-9._-]+$') { throw 'Unexpected APK version identifier' }
    $target = "artifacts\fold-probe-$version-debug.apk"
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination $target
    (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash | Set-Content -LiteralPath "$target.sha256"
    Write-Output (Join-Path $repo $target)
} finally { Pop-Location }
