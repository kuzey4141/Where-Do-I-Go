param(
    [ValidateSet('Start', 'Stop', 'Inspect', 'Shell')]
    [string]$Action = 'Inspect',
    [string]$BinaryDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\PostgreSQL17\pgsql\bin'),
    [string]$DataDirectory = (Join-Path $env:LOCALAPPDATA 'WhereDoIGo\postgres-data')
)

$ErrorActionPreference = 'Stop'
$pgCtl = Join-Path $BinaryDirectory 'pg_ctl.exe'
$psql = Join-Path $BinaryDirectory 'psql.exe'
if (!(Test-Path -LiteralPath $pgCtl)) {
    throw "PostgreSQL bulunamadi: $BinaryDirectory"
}

if ($Action -eq 'Start') {
    & $pgCtl -D $DataDirectory status *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Output 'PostgreSQL zaten calisiyor.'
        exit 0
    }
    $log = Join-Path (Split-Path $DataDirectory) 'postgres.log'
    & $pgCtl -D $DataDirectory -l $log -w start
    exit $LASTEXITCODE
}
if ($Action -eq 'Stop') {
    & $pgCtl -D $DataDirectory -m fast -w stop
    exit $LASTEXITCODE
}

$project = Split-Path $PSScriptRoot
$envFile = Join-Path $project '.env'
if (!(Test-Path -LiteralPath $envFile)) { throw '.env bulunamadi.' }
$settings = @{}
foreach ($line in (Get-Content -LiteralPath $envFile -Encoding UTF8)) {
    if ($line -match '^\s*([A-Z_]+)\s*=(.*)$') {
        $settings[$Matches[1]] = $Matches[2].Trim()
    }
}
if (!$settings.DB_PASSWORD) { throw '.env icinde DB_PASSWORD gerekli.' }
if ($settings.DB_URL) { throw 'Bu betik yerel baglanti icindir; DB_URL ayarini psql ile ayrica kullanin.' }

$oldPassword = $env:PGPASSWORD
$oldEncoding = $env:PGCLIENTENCODING
try {
    $env:PGPASSWORD = $settings.DB_PASSWORD
    $env:PGCLIENTENCODING = 'UTF8'
    $port = if ($settings.DB_PORT) { $settings.DB_PORT } else { '5432' }
    $dbUser = if ($settings.DB_USER) { $settings.DB_USER } else { 'where_do_i_go' }
    $dbName = if ($settings.DB_NAME) { $settings.DB_NAME } else { 'where_do_i_go' }
    $arguments = @('-X', '-h', 'localhost', '-p', $port, '-U', $dbUser, '-d', $dbName)
    if ($Action -eq 'Inspect') {
        $arguments += @('-v', 'ON_ERROR_STOP=1', '-f', (Join-Path $PSScriptRoot 'inspect-database.sql'))
    }
    & $psql @arguments
    $result = $LASTEXITCODE
} finally {
    $env:PGPASSWORD = $oldPassword
    $env:PGCLIENTENCODING = $oldEncoding
}
exit $result
