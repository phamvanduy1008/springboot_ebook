param(
    [Parameter(Mandatory = $true)][string]$EnvFile,
    [string]$Email = 'admin@local.test',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin'
)
$ErrorActionPreference = 'Stop'
if ($Email -notmatch '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' -or $Email.Length -gt 320) {
    throw 'Use a valid email address for the local administrator.'
}
$Email = $Email.ToLowerInvariant()
$resolvedEnv = (Resolve-Path -LiteralPath $EnvFile).Path
$config = @{}
foreach ($line in Get-Content -LiteralPath $resolvedEnv) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $config[$matches[1]] = $matches[2] }
}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
    if ([string]::IsNullOrEmpty($config[$name])) { throw "Missing $name in the selected environment file." }
}
$databaseUri = [Uri]($config['DB_URL'] -replace '^jdbc:', '')
if ($databaseUri.Host -notin @('localhost', '127.0.0.1')) { throw 'This seed only runs against a loopback PostgreSQL database.' }
$psql = Join-Path $PostgresBin 'psql.exe'
if (-not (Test-Path -LiteralPath $psql)) { throw 'psql.exe was not found.' }
$sql = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sql\Seed-LocalAdmin.sql') -Raw
$random = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$initialPassword = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')

$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $psql
foreach ($argument in @('-h', $databaseUri.Host, '-p', [string]$databaseUri.Port, '-U', $config['DB_USERNAME'], '-d', $databaseUri.AbsolutePath.TrimStart('/'), '-X', '-A', '-t', '-f', '-')) {
    [void]$start.ArgumentList.Add($argument)
}
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.Environment['PGPASSWORD'] = $config['DB_PASSWORD']
$start.Environment['PGCONNECT_TIMEOUT'] = '5'
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start
try {
    if (-not $process.Start()) { throw 'Could not start psql.' }
    $outputTask = $process.StandardOutput.ReadToEndAsync()
    $errorTask = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.WriteLine("\set admin_email '$Email'")
    $process.StandardInput.WriteLine("\set admin_password '$initialPassword'")
    $process.StandardInput.Write($sql)
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Admin seed timed out.' }
    $output = $outputTask.GetAwaiter().GetResult()
    $errors = $errorTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Admin seed failed: $errors $output" }
    if (-not $output.Contains("SEED_OK|$Email|true")) { throw 'Admin seed did not verify the new password.' }
    Write-Output "Admin email: $Email"
    Write-Output "Initial password: $initialPassword"
    Write-Output 'Change this password in Account settings after the first login.'
} finally {
    $process.Dispose()
}
