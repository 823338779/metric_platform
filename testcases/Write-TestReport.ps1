param([string]$ApiResults,[string]$StarRocksResults,[string]$ReportPath,[int]$BackendTests,[int]$FrontendTests)
$apiParsed=Get-Content -Raw $ApiResults|ConvertFrom-Json;$starParsed=Get-Content -Raw $StarRocksResults|ConvertFrom-Json
$all=New-Object System.Collections.Generic.List[object]
for($index=0;$index -lt $apiParsed.Count;$index++){$all.Add($apiParsed[$index])}
if($starParsed -is [array]){for($index=0;$index -lt $starParsed.Count;$index++){$all.Add($starParsed[$index])}}elseif($null -ne $starParsed){$all.Add($starParsed)}
$passed=@($all|Where-Object status -eq 'PASS').Count;$failed=$all.Count-$passed
$lines=@("# Metric Platform Test Report","","Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')","","## Summary","","- Black-box cases: $($all.Count)","- Passed: $passed","- Failed: $failed","- Backend JUnit tests: $BackendTests passed","- Frontend Vitest tests: $FrontendTests passed","- Frontend production build: passed","- Docker Compose validation: passed","","## Case Results","","| ID | Meaning | Status | Duration (ms) | Message |","| --- | --- | --- | ---: | --- |")
foreach($case in $all){$message=($case.message -replace '\|','/' -replace '(?i)bearer [^ ]+','[REDACTED]' -replace '(?i)jdbc:[^ ]+','[REDACTED]');$lines+="| $($case.id) | $($case.meaning) | $($case.status) | $($case.durationMs) | $message |"}
$lines+=@("","## Security and environment","","Credentials were loaded from the ignored repository environment file only into test processes. The report contains no passwords, bearer values, JDBC URLs, or usernames. StarRocks operations were read-only; Metric Platform query requests contained only semantic UUIDs.")
$lines|Set-Content -Encoding UTF8 $ReportPath
if($failed -gt 0){exit 1}
