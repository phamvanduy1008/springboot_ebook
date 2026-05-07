param(
 [string]$Database='Ebook',
 [string]$DatabaseUser='postgres',
 [ValidateRange(1024,65535)][int]$DatabasePort=5432,
 [ValidateRange(1024,65535)][int]$Port=8080,
 [string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin',
 [switch]$AdoptLegacySchema
)
$ErrorActionPreference='Stop'
if($Database -notmatch '^[A-Za-z][A-Za-z0-9_]*$') {throw 'Use a simple database name'}
if($DatabaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {throw 'Use a simple database user name'}
$root=Split-Path -Parent $PSScriptRoot
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar=Join-Path $root 'api/target/api-0.1.0-SNAPSHOT-exec.jar'
if(-not(Test-Path -LiteralPath $java) -or -not(Test-Path -LiteralPath $jar)) {throw 'Set JAVA_HOME to JDK21 and build the backend first'}
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
try {$listener.Start()} finally {$listener.Stop()}
$runRoot=Join-Path $root ('var/local-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$adoption=''
if($AdoptLegacySchema) {
 & (Join-Path $PostgresBin 'psql.exe') -X -w -h 127.0.0.1 -p $DatabasePort -U $DatabaseUser -d $Database -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'sql/Check-LegacyPublic.sql')
 if($LASTEXITCODE -ne 0) {throw 'Legacy preflight failed; no baseline attempted'}
 $backup=Join-Path $runRoot 'before-adoption.dump'
 & (Join-Path $PostgresBin 'pg_dump.exe') -w -h 127.0.0.1 -p $DatabasePort -U $DatabaseUser -d $Database -Fc -f $backup
 if($LASTEXITCODE -ne 0) {throw 'Backup failed; no baseline attempted'}
 # This records an explicit BASELINE at 7, not fictitious executions of V001..V007.
 # V008 adds compatibility. Keep automatic baseline disabled in normal configuration.
 $adoption=' --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=7'
 Write-Output ('Backup: '+$backup)
}
$arguments='-jar "'+$jar+'" --server.port='+$Port+' --app.database-url=jdbc:postgresql://127.0.0.1:'+$DatabasePort+'/'+$Database+'?currentSchema=public --app.database-username='+$DatabaseUser+' --spring.profiles.active=local --app.local-seed.enabled=false --debug=false'+$adoption
$process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot 'api.log') -RedirectStandardError (Join-Path $runRoot 'api.err')
try {
 $ready=$false
 for($attempt=0;$attempt -lt 120;$attempt++) {
  if($process.HasExited) {throw ('Backend exited; inspect '+$runRoot)}
  try {$health=Invoke-RestMethod -TimeoutSec 2 ('http://127.0.0.1:'+$Port+'/actuator/health');if($health.status -eq 'UP') {$ready=$true;break}} catch { }
  Start-Sleep -Milliseconds 250
 }
 if(-not $ready) {throw ('Backend did not become healthy; inspect '+$runRoot)}
 [pscustomobject]@{ProcessId=$process.Id;Url=('http://127.0.0.1:'+$Port);Database=$Database;Schema='public';Logs=$runRoot}
} catch {
 if(-not $process.HasExited) {Stop-Process -Id $process.Id}
 throw
}
