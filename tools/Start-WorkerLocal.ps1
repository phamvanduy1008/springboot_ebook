param(
 [string]$Database='Ebook',
 [string]$DatabaseUser='postgres',
 [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')][string]$Schema='public',
 [ValidateRange(1024,65535)][int]$DatabasePort=5432,
 [string]$StorageRoot=''
)
$ErrorActionPreference='Stop'
if($Database -notmatch '^[A-Za-z][A-Za-z0-9_]*$' -or $DatabaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {throw 'Use simple database/user names'}
$root=Split-Path -Parent $PSScriptRoot
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar=Join-Path $root 'worker/target/worker-0.1.0-SNAPSHOT-exec.jar'
if(-not(Test-Path -LiteralPath $java) -or -not(Test-Path -LiteralPath $jar)) {throw 'Set JAVA_HOME to JDK21 and build the worker first'}
if([string]::IsNullOrWhiteSpace($StorageRoot)) {$StorageRoot=Join-Path $root 'var/storage'}
$storageAbsolute=[IO.Path]::GetFullPath($StorageRoot)
$runRoot=Join-Path $root ('var/worker-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$previousStorage=$env:STORAGE_ROOT
try {
 $env:STORAGE_ROOT=$storageAbsolute
 $arguments='-Xmx1536m -jar "'+$jar+'" --app.database-url=jdbc:postgresql://127.0.0.1:'+$DatabasePort+'/'+$Database+'?currentSchema='+$Schema+' --app.database-username='+$DatabaseUser+' --spring.profiles.active=local --app.local-seed.enabled=false --debug=false'
 $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot 'worker.log') -RedirectStandardError (Join-Path $runRoot 'worker.err')
 Start-Sleep -Seconds 2
 if($process.HasExited) {throw ('Worker exited; inspect '+$runRoot)}
 [pscustomobject]@{ProcessId=$process.Id;Database=$Database;Storage=$storageAbsolute;Logs=$runRoot;Status='Starting; confirm Started WorkerApplication in log'}
} finally {
 if($null -eq $previousStorage) {Remove-Item Env:STORAGE_ROOT -ErrorAction SilentlyContinue} else {$env:STORAGE_ROOT=$previousStorage}
}
