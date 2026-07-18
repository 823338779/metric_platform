param(
    [string]$BaseUrl = "http://localhost:8090",
    [string]$OutputPath = ""
)
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$expectations = Get-Content -Raw (Join-Path $repoRoot "testcases\fixtures\expectations.json") | ConvertFrom-Json
Get-Content (Join-Path $repoRoot ".env") | Where-Object { $_ -match '^[^#][^=]*=' } | ForEach-Object {
    $parts = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], "Process")
}
$admin = @{ Authorization = "Bearer admin-token" }
$query = @{ Authorization = "Bearer query-token" }
$results = New-Object System.Collections.Generic.List[object]

function Invoke-Api([string]$Method,[string]$Path,[hashtable]$Headers,[object]$Body=$null) {
    try {
        $params=@{Method=$Method;Uri=($BaseUrl+$Path);Headers=$Headers;UseBasicParsing=$true}
        if($null -ne $Body){$params.ContentType="application/json";$params.Body=($Body|ConvertTo-Json -Depth 8 -Compress)}
        $response=Invoke-WebRequest @params
        $parsed=$null;if($response.Content){$parsed=$response.Content|ConvertFrom-Json}
        return @{Status=[int]$response.StatusCode;Body=$parsed}
    } catch {
        if($_.Exception.Response){return @{Status=[int]$_.Exception.Response.StatusCode;Body=$null}}
        throw
    }
}
function Case([string]$Id,[string]$Meaning,[scriptblock]$Test) {
    $watch=[Diagnostics.Stopwatch]::StartNew();$status="PASS";$message=""
    try { & $Test } catch {$status="FAIL";$message=$_.Exception.Message -replace '(?i)(password|token|jdbc)[^ ]*','[REDACTED]'}
    $watch.Stop();$results.Add([pscustomobject]@{id=$Id;meaning=$Meaning;status=$status;durationMs=$watch.ElapsedMilliseconds;message=$message})
}
function Expect-Status($response,[int]$expected){if($response.Status -ne $expected){throw "expected HTTP $expected, got $($response.Status)"}}

Case "AUTH-001" "Anonymous discovery is rejected" {Expect-Status (Invoke-Api GET "/api/v1/datasets" @{}) 401}
Case "AUTH-002" "Unknown bearer token is rejected" {Expect-Status (Invoke-Api GET "/api/v1/datasets" @{Authorization="Bearer invalid"}) 401}
Case "AUTH-003" "Query client can discover datasets" {Expect-Status (Invoke-Api GET "/api/v1/datasets" $query) 200}
Case "AUTH-004" "Every authenticated user can administer" {Expect-Status (Invoke-Api POST "/api/v1/admin/data-sources" $query @{}) 400}
Case "SRC-001" "Non-StarRocks JDBC URL is rejected" {Expect-Status (Invoke-Api POST "/api/v1/admin/data-sources" $admin @{name="invalid-$([guid]::NewGuid())";jdbcUrl="jdbc:postgresql://invalid/db";databaseName="x";username="x";secretReference="STARROCKS_PASSWORD"}) 422}

$suffix=(Get-Date -Format "yyyyMMddHHmmssfff")
$sourceResponse=Invoke-Api POST "/api/v1/admin/data-sources" $admin @{name="e2e-source-$suffix";jdbcUrl=$env:STARROCKS_JDBC_URL;databaseName=$env:STARROCKS_DATABASE;username=$env:STARROCKS_USERNAME;secretReference="STARROCKS_PASSWORD"}
Case "SRC-002" "Valid StarRocks source is created with redacted response" {Expect-Status $sourceResponse 201;if($sourceResponse.Body.PSObject.Properties.Name -contains "jdbcUrl" -or $sourceResponse.Body.PSObject.Properties.Name -contains "username"){throw "source response exposes connection metadata"}}
$sourceId=$sourceResponse.Body.id
Case "DATASET-001" "Dataset requires a nonempty field list" {Expect-Status (Invoke-Api POST "/api/v1/admin/datasets" $admin @{dataSourceId=$sourceId;name="empty-$suffix";schemaName=$expectations.schema;objectName=$expectations.object;fields=@()}) 400}
Case "DATASET-002" "Dataset rejects duplicate physical fields" {Expect-Status (Invoke-Api POST "/api/v1/admin/datasets" $admin @{dataSourceId=$sourceId;name="duplicate-$suffix";schemaName=$expectations.schema;objectName=$expectations.object;fields=@(@{physicalName="order_date";displayName="A";starrocksType="DATE";dimensionEnabled=$true;metricEnabled=$false},@{physicalName="ORDER_DATE";displayName="B";starrocksType="DATE";dimensionEnabled=$true;metricEnabled=$false})}) 422}
Case "DATASET-003" "Dataset rejects a missing source" {Expect-Status (Invoke-Api POST "/api/v1/admin/datasets" $admin @{dataSourceId=[guid]::NewGuid();name="missing-$suffix";schemaName=$expectations.schema;objectName=$expectations.object;fields=@(@{physicalName="order_date";displayName="Day";starrocksType="DATE";dimensionEnabled=$true;metricEnabled=$false})}) 404}
$datasetResponse=Invoke-Api POST "/api/v1/admin/datasets" $admin @{dataSourceId=$sourceId;name="fact-orders-$suffix";schemaName=$expectations.schema;objectName=$expectations.object;fields=@(@{physicalName=$expectations.dimensionField;displayName="Order day";starrocksType="DATE";dimensionEnabled=$true;metricEnabled=$false},@{physicalName=$expectations.metricField;displayName="Gross amount";starrocksType="DECIMAL(18,2)";dimensionEnabled=$false;metricEnabled=$true})}
Case "DATASET-004" "Valid explicit dataset is created" {Expect-Status $datasetResponse 201}
$datasetId=$datasetResponse.Body.id
$fieldsResponse=Invoke-Api GET "/api/v1/admin/datasets/$datasetId/fields" $admin
Case "DATASET-005" "Administrator can list explicit fields" {Expect-Status $fieldsResponse 200;if($fieldsResponse.Body.Count -ne 2){throw "expected 2 fields"}}
Case "DATASET-006" "Every authenticated user can list fields" {Expect-Status (Invoke-Api GET "/api/v1/admin/datasets/$datasetId/fields" $query) 200}
$dayField=($fieldsResponse.Body|Where-Object physicalName -eq $expectations.dimensionField).id
$amountField=($fieldsResponse.Body|Where-Object physicalName -eq $expectations.metricField).id
Case "SEM-001" "Dimension rejects a field from another dataset" {Expect-Status (Invoke-Api POST "/api/v1/admin/dimensions" $admin @{datasetId=$datasetId;datasetFieldId=[guid]::NewGuid();name="bad-dim";displayName="Bad"}) 422}
Case "SEM-002" "Unsupported aggregation is rejected" {Expect-Status (Invoke-Api POST "/api/v1/admin/metrics" $admin @{datasetId=$datasetId;datasetFieldId=$amountField;name="median";displayName="Median";aggregation="MEDIAN"}) 422}
$countResponse=Invoke-Api POST "/api/v1/admin/metrics" $admin @{datasetId=$datasetId;datasetFieldId=$null;name="orders";displayName="Orders";aggregation="COUNT"}
Case "SEM-003" "COUNT accepts no field" {Expect-Status $countResponse 201}
$metricIds=@{};foreach($aggregation in @("COUNT_DISTINCT","SUM","AVG","MIN","MAX")){$metricName=$(if($aggregation -eq "SUM"){"gross_sales"}else{$aggregation.ToLower()+"-$suffix"});$response=Invoke-Api POST "/api/v1/admin/metrics" $admin @{datasetId=$datasetId;datasetFieldId=$amountField;name=$metricName;displayName=$aggregation;aggregation=$aggregation};$metricIds[$aggregation]=$response.Body.id;Case ("SEM-"+(4+$metricIds.Count).ToString("000")) "$aggregation accepts metric-enabled field" {Expect-Status $response 201}}
$dimensionResponse=Invoke-Api POST "/api/v1/admin/dimensions" $admin @{datasetId=$datasetId;datasetFieldId=$dayField;name="order_day";displayName="Order day"}
Case "SEM-010" "Dimension accepts dimension-enabled field" {Expect-Status $dimensionResponse 201}
$dimensionId=$dimensionResponse.Body.id
Case "QUERY-001" "Semantic model is readable" {Expect-Status (Invoke-Api GET "/api/v1/datasets/$datasetId/semantic-model" $query) 200}
Case "QUERY-002" "Semantic search is readable" {Expect-Status (Invoke-Api POST "/api/v1/semantic-objects:search" $query @{query="gross";datasetIds=@($datasetId);objectTypes=@("METRIC")}) 200}
Case "QUERY-003" "Validation rejects empty metric selection" {Expect-Status (Invoke-Api POST "/api/v1/queries:validate" $query @{metricIds=@();dimensions=@();relationshipIds=@()}) 400}
Case "QUERY-004" "Validation rejects malformed metric UUID" {Expect-Status (Invoke-Api POST "/api/v1/queries:validate" $query @{metricIds=@("bad");dimensions=@();relationshipIds=@()}) 400}
Case "QUERY-005" "Validation rejects a foreign metric" {Expect-Status (Invoke-Api POST "/api/v1/queries:validate" $query @{metricIds=@([guid]::NewGuid());dimensions=@();relationshipIds=@()}) 404}
$selection=@{metricIds=@($metricIds.SUM);dimensions=@(@{dimensionId=$dimensionId});relationshipIds=@()}
$validateResponse=Invoke-Api POST "/api/v1/queries:validate" $query $selection
Case "QUERY-006" "Valid selection returns a redacted validation summary" {Expect-Status $validateResponse 200;if($validateResponse.Body.PSObject.Properties.Name -contains "sql"){throw "validation response exposes SQL"}}
$queryResponse=Invoke-Api POST "/api/v1/queries" $query $selection
Case "QUERY-007" "Real governed StarRocks query returns bounded grouped rows" {Expect-Status $queryResponse 200;if($queryResponse.Body.columns.Count -ne 2){throw "expected 2 columns"};if($queryResponse.Body.rows.Count -lt 1 -or $queryResponse.Body.rows.Count -gt $expectations.maximumRows){throw "unexpected row count"};$names=@($queryResponse.Body.columns|ForEach-Object name);foreach($name in $expectations.expectedColumns){if($names -notcontains $name){throw "missing expected column"}}}

if(!$OutputPath){$OutputPath=Join-Path $repoRoot "test-reports\api-results.json"}
$results | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $OutputPath
$failed=@($results|Where-Object status -eq "FAIL").Count
Write-Output "API_CASES=$($results.Count) PASSED=$($results.Count-$failed) FAILED=$failed"
if($failed -gt 0){exit 1}
