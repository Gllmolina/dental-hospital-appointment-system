$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $projectRoot 'frontend'
$backend = Join-Path $projectRoot 'backend'
$static = Join-Path $backend 'src/main/resources/static'

Push-Location $frontend
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
    $distAssets = Join-Path $frontend 'dist/assets'
    $staticAssets = Join-Path $static 'assets'
    if (Test-Path -LiteralPath $staticAssets) {
        $currentNames = @(Get-ChildItem -LiteralPath $distAssets -File | ForEach-Object Name)
        Get-ChildItem -LiteralPath $staticAssets -File | Where-Object { $_.Name -notin $currentNames } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    }
    Copy-Item -Path (Join-Path $frontend 'dist/*') -Destination $static -Recurse -Force
} finally { Pop-Location }

Push-Location $backend
try {
    & '.\mvnw.cmd' clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verify failed' }
} finally { Pop-Location }

Write-Output (Join-Path $backend 'target/hospital.war')
