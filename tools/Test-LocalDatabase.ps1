param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [ValidateRange(1024,65535)][int]$Port = 55434
)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$testRoot=Join-Path $repoRoot ('var/db-test-'+[guid]::NewGuid().ToString('N'))
$resolvedRoot=[System.IO.Path]::GetFullPath($testRoot)
if (-not $resolvedRoot.StartsWith([System.IO.Path]::GetFullPath((Join-Path $repoRoot 'var'))+[System.IO.Path]::DirectorySeparatorChar,[System.StringComparison]::OrdinalIgnoreCase)) {throw 'Invalid test directory'}
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$data=Join-Path $testRoot 'data';$passwordFile=Join-Path $testRoot 'password.tmp'
$password=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
[System.IO.File]::WriteAllText($passwordFile,$password,[System.Text.Encoding]::ASCII)
$saved=@{};foreach($name in @('PGPASSWORD','DB_URL','DB_USERNAME','DB_PASSWORD','EPUB_IDENTITY_DB_TEST')) {$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$started=$false
try {
    & (Join-Path $PostgresBin 'initdb.exe') -D $data -U epub_test --pwfile=$passwordFile --encoding=UTF8 --locale=C -A scram-sha-256 *> (Join-Path $testRoot 'initdb.log')
    if ($LASTEXITCODE -ne 0) {throw 'initdb failed; inspect local initdb.log'}
    $startArgs='-D "'+$data+'" -l "'+(Join-Path $testRoot 'postgres.log')+'" -o "-h 127.0.0.1 -p '+$Port+'" -w start'
    $control=Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList $startArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'start.out') -RedirectStandardError (Join-Path $testRoot 'start.err')
    # -Wait waits for the entire descendant tree, including the long-lived server.
    $null=$control.Handle
    $control.WaitForExit()
    $control.Refresh()
    $started=Test-Path -LiteralPath (Join-Path $data 'postmaster.pid')
    if ($control.ExitCode -ne 0) {throw 'PostgreSQL test cluster could not start; no existing cluster will be stopped'}
    $started=$true;$env:PGPASSWORD=$password
    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $Port -U epub_test epub_identity_test
    if ($LASTEXITCODE -ne 0) {throw 'Test database creation failed'}
    $env:DB_URL="jdbc:postgresql://127.0.0.1:$Port/epub_identity_test";$env:DB_USERNAME='epub_test';$env:DB_PASSWORD=$password;$env:EPUB_IDENTITY_DB_TEST='true'
    Push-Location $repoRoot
    try { & .\mvnw.cmd -B -ntp verify; if ($LASTEXITCODE -ne 0) {throw 'Database verify failed'} }
    finally {Pop-Location}
} finally {
    try { if ($started) {
        $stopArgs='-D "'+$data+'" -m fast -w stop'
        $control=Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList $stopArgs -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $testRoot 'stop.out') -RedirectStandardError (Join-Path $testRoot 'stop.err')
        if ($control.ExitCode -ne 0) {Write-Error 'Test cluster could not be stopped; inspect stop.err'}
    } } finally {
        foreach($name in $saved.Keys) {[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
        if (Test-Path -LiteralPath $passwordFile) {Remove-Item -LiteralPath $passwordFile}
    }
}
