param([string]$OutputPath="")
$ErrorActionPreference="Stop"
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Get-Content (Join-Path $repoRoot ".env")|Where-Object{$_ -match '^[^#][^=]*='}|ForEach-Object{$parts=$_ -split '=',2;[Environment]::SetEnvironmentVariable($parts[0].Trim(),$parts[1],"Process")}
$jar=Get-ChildItem "$env:USERPROFILE\.m2\repository\com\mysql\mysql-connector-j\*\mysql-connector-j-*.jar"|Where-Object{$_.Name -notlike '*sources*'}|Select-Object -First 1 -ExpandProperty FullName
if(!$jar){throw "MySQL Connector/J is unavailable"}
$build=Join-Path $repoRoot "testcases\.build"
New-Item -ItemType Directory -Force $build|Out-Null
& javac -cp $jar -d $build (Join-Path $PSScriptRoot "StarRocksReadOnlyProbe.java")
if($LASTEXITCODE -ne 0){throw "StarRocks probe compilation failed"}
$watch=[Diagnostics.Stopwatch]::StartNew();$output=& java -cp ($build+";"+$jar) StarRocksReadOnlyProbe 2>&1;$exit=$LASTEXITCODE;$watch.Stop()
$result=[pscustomobject]@{id="STARROCKS-001";meaning="Real read-only metadata and aggregate probe";status=$(if($exit -eq 0){"PASS"}else{"FAIL"});durationMs=$watch.ElapsedMilliseconds;message=$(if($exit -eq 0){$output -join " "}else{"read-only probe failed"})}
if(!$OutputPath){$OutputPath=Join-Path $repoRoot "test-reports\starrocks-results.json"}
$result|ConvertTo-Json|Set-Content -Encoding UTF8 $OutputPath
Write-Output $result.message
if($exit -ne 0){exit 1}
