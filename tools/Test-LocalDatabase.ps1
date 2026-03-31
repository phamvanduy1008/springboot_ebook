param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [ValidateRange(1024,65535)][int]$Port = 55434,
    [string]$MailpitBin = $env:MAILPIT_BIN
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
$saved=@{};foreach($name in @('PGPASSWORD','DB_URL','DB_USERNAME','DB_PASSWORD','EPUB_IDENTITY_DB_TEST','SMTP_HOST','SMTP_PORT','EPUB_MAILPIT_URL')) {$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$started=$false
$apiProcess=$null;$workerProcess=$null;$mailpitProcess=$null
function Get-TestPort {
    $socket=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
    $socket.Start();$available=$socket.LocalEndpoint.Port;$socket.Stop();return $available
}
try {
    if(-not [string]::IsNullOrWhiteSpace($MailpitBin)) {
        if(-not (Test-Path -LiteralPath $MailpitBin -PathType Leaf)) {throw 'Mailpit executable not found'}
        $smtpPort=Get-TestPort;$mailpitPort=Get-TestPort
        $mailpitArgs='--listen 127.0.0.1:'+$mailpitPort+' --smtp 127.0.0.1:'+$smtpPort+' --database "'+(Join-Path $testRoot 'mailpit.db')+'" --disable-version-check'
        $mailpitProcess=Start-Process -FilePath $MailpitBin -ArgumentList $mailpitArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'mailpit.log') -RedirectStandardError (Join-Path $testRoot 'mailpit.err')
        $env:SMTP_HOST='127.0.0.1';$env:SMTP_PORT=[string]$smtpPort;$env:EPUB_MAILPIT_URL='http://127.0.0.1:'+$mailpitPort
        $mailpitReady=$false
        for($attempt=0;$attempt -lt 60;$attempt++) {
            if($mailpitProcess.HasExited) {throw 'Mailpit exited; inspect its local logs'}
            try {$null=Invoke-RestMethod -TimeoutSec 2 ($env:EPUB_MAILPIT_URL+'/api/v1/info');$mailpitReady=$true;break} catch {Start-Sleep -Milliseconds 250}
        }
        if(-not $mailpitReady) {throw 'Mailpit did not become ready'}
    }
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
    try {
        & .\mvnw.cmd -B -ntp verify; if ($LASTEXITCODE -ne 0) {throw 'Database verify failed'}
        $listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
        $listener.Start();$apiPort=$listener.LocalEndpoint.Port;$listener.Stop()
        $java=Join-Path $env:JAVA_HOME 'bin/java.exe'
        $apiJar=Join-Path $repoRoot 'api/target/api-0.1.0-SNAPSHOT-exec.jar'
        $workerJar=Join-Path $repoRoot 'worker/target/worker-0.1.0-SNAPSHOT-exec.jar'
        $apiProcess=Start-Process -FilePath $java -ArgumentList ('-jar "'+$apiJar+'" --server.port='+$apiPort+' --spring.profiles.active=local --app.local-seed.enabled=false --debug=false') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'api.log') -RedirectStandardError (Join-Path $testRoot 'api.err')
        $workerProcess=Start-Process -FilePath $java -ArgumentList ('-jar "'+$workerJar+'" --spring.profiles.active=local --app.local-seed.enabled=false --debug=false') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'worker.log') -RedirectStandardError (Join-Path $testRoot 'worker.err')
        $healthy=$false
        for($attempt=0;$attempt -lt 100;$attempt++) {
            if($apiProcess.HasExited -or $workerProcess.HasExited) {throw 'Packaged process exited; inspect local api/worker logs'}
            try {
                $health=Invoke-RestMethod -TimeoutSec 2 ("http://127.0.0.1:"+$apiPort+'/actuator/health')
                $workerReady=Select-String -Path (Join-Path $testRoot 'worker.log') -SimpleMatch 'Started WorkerApplication' -Quiet
                if($health.status -eq 'UP' -and $workerReady) {$healthy=$true;break}
            } catch { }
            Start-Sleep -Milliseconds 250
        }
        if(-not $healthy) {throw 'Packaged API/worker did not become healthy with the local PostgreSQL database'}
        Write-Output 'PASS: packaged API health UP and headless worker started with the isolated PostgreSQL database'
    }
    finally {Pop-Location}
} finally {
    try {
        foreach($process in @($apiProcess,$workerProcess,$mailpitProcess)) {
            if($null -ne $process -and -not $process.HasExited) {Stop-Process -Id $process.Id -ErrorAction SilentlyContinue}
        }
        if ($started) {
        $stopArgs='-D "'+$data+'" -m fast -w stop'
        $control=Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList $stopArgs -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $testRoot 'stop.out') -RedirectStandardError (Join-Path $testRoot 'stop.err')
        if ($control.ExitCode -ne 0) {Write-Error 'Test cluster could not be stopped; inspect stop.err'}
    } } finally {
        foreach($name in $saved.Keys) {[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
        if (Test-Path -LiteralPath $passwordFile) {Remove-Item -LiteralPath $passwordFile}
    }
}
