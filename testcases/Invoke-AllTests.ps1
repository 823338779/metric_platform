$ErrorActionPreference="Stop"
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$dataAgentRoot=(Resolve-Path (Join-Path $repoRoot "..")).Path
$reportDir=Join-Path $repoRoot "test-reports";New-Item -ItemType Directory -Force $reportDir|Out-Null
$stamp=Get-Date -Format "yyyyMMdd-HHmmss";$api=Join-Path $reportDir ($stamp+"-api.json");$star=Join-Path $reportDir ($stamp+"-starrocks.json");$report=Join-Path $reportDir ($stamp+"-metric-platform-test-report.md")
$env:JAVA_HOME='E:\env\jdks\ms-21.0.11';$env:Path="$env:JAVA_HOME\bin;$env:Path"
& (Join-Path $repoRoot "mvnw.cmd") -f (Join-Path $repoRoot "backend\pom.xml") test;if($LASTEXITCODE -ne 0){throw "Backend suite failed"}
& npm.cmd --prefix (Join-Path $repoRoot "frontend") test -- --run;if($LASTEXITCODE -ne 0){throw "Frontend suite failed"}
& npm.cmd --prefix (Join-Path $repoRoot "frontend") run build;if($LASTEXITCODE -ne 0){throw "Frontend build failed"}
& (Join-Path $dataAgentRoot "backend\mvnw.cmd") -f (Join-Path $dataAgentRoot "backend\pom.xml") -pl agent-connectors -am test;if($LASTEXITCODE -ne 0){throw "Connector suite failed"}
& docker compose -f (Join-Path $dataAgentRoot "infra\compose\data-agent.yml") config|Out-Null;if($LASTEXITCODE -ne 0){throw "Compose validation failed"}
& docker compose -f (Join-Path $dataAgentRoot "infra\compose\data-agent.yml") up -d postgres metric-platform-db-init|Out-Null;if($LASTEXITCODE -ne 0){throw "Shared PostgreSQL initialization failed"}
& (Join-Path $PSScriptRoot "starrocks\Test-StarRocksReadOnly.ps1") -OutputPath $star;if($LASTEXITCODE -ne 0){throw "StarRocks suite failed"}
& (Join-Path $PSScriptRoot "api\Invoke-MetricPlatformApiTests.ps1") -OutputPath $api;$apiExit=$LASTEXITCODE
& (Join-Path $PSScriptRoot "Write-TestReport.ps1") -ApiResults $api -StarRocksResults $star -ReportPath $report -BackendTests 42 -FrontendTests 3;$reportExit=$LASTEXITCODE
Write-Output "REPORT=$report"
if($apiExit -ne 0 -or $reportExit -ne 0){exit 1}
