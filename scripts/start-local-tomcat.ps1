param([switch]$Initialize)
$ErrorActionPreference = 'Stop'
$tomcat = 'D:\workspaces\apache-tomcat-9.0.124'
$privateRoot = Join-Path $env:LOCALAPPDATA 'DentalHospital'
$secretsRoot = Join-Path $privateRoot 'secrets'

function Save-Protected([string]$Name, [string]$Value) {
    $secure = ConvertTo-SecureString -String $Value -AsPlainText -Force
    ConvertFrom-SecureString -SecureString $secure | Set-Content -LiteralPath (Join-Path $secretsRoot "$Name.dpapi") -NoNewline
}
function Read-Protected([string]$Name) {
    $file = Join-Path $secretsRoot "$Name.dpapi"
    if (-not (Test-Path -LiteralPath $file)) { throw "缺少本机密钥文件：$file；先用 -Initialize 初始化。" }
    $secure = ConvertTo-SecureString (Get-Content -LiteralPath $file -Raw)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
function New-RandomSecret {
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

New-Item -ItemType Directory -Force -Path $secretsRoot, (Join-Path $privateRoot 'verification-outbox'), (Join-Path $privateRoot 'avatars') | Out-Null
if ($Initialize) {
    if (-not $env:DB_PASSWORD) { throw '初始化时请先在当前 PowerShell 会话设置 DB_PASSWORD 环境变量。' }
    if (Test-Path -LiteralPath (Join-Path $secretsRoot 'jwt-secret.dpapi')) { throw '本机密钥已存在；直接运行脚本启动 Tomcat。' }
    Save-Protected 'db-password' $env:DB_PASSWORD
    Save-Protected 'jwt-secret' (New-RandomSecret)
    Save-Protected 'verification-secret' (New-RandomSecret)
}

$env:DB_USER = 'root'
$env:DB_PASSWORD = Read-Protected 'db-password'
$env:JWT_SECRET = Read-Protected 'jwt-secret'
$env:VERIFICATION_SECRET = Read-Protected 'verification-secret'
$env:VERIFICATION_DELIVERY_MODE = 'local-file'
$env:VERIFICATION_OUTBOX_DIR = Join-Path $privateRoot 'verification-outbox'
$env:AVATAR_DIR = Join-Path $privateRoot 'avatars'
$env:CATALINA_HOME = $tomcat
$env:CATALINA_BASE = $tomcat
$process = Start-Process -FilePath (Join-Path $tomcat 'bin\catalina.bat') -ArgumentList 'run' -WorkingDirectory $tomcat -WindowStyle Hidden -PassThru
Write-Output "Tomcat 启动进程 ID：$($process.Id)。本地验证码目录：$env:VERIFICATION_OUTBOX_DIR"
