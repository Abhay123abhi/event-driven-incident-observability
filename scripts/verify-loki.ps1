param(
    [string]$LokiUrl = 'http://127.0.0.1:3100',
    [int]$LookbackMinutes = 15
)

$ErrorActionPreference = 'Stop'
$services = @('api-gateway', 'order-service', 'inventory-service', 'incident-service')
$failed = $false

foreach ($service in $services) {
    $query = '{service="' + $service + '"}'
    $url = "$LokiUrl/loki/api/v1/query_range?query=$([uri]::EscapeDataString($query))&limit=5&start=$([DateTimeOffset]::UtcNow.AddMinutes(-$LookbackMinutes).ToUnixTimeSeconds())&end=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    try {
        $response = Invoke-RestMethod -Uri $url -TimeoutSec 15
        if ($response.status -ne 'success' -or @($response.data.result).Count -eq 0) {
            Write-Warning "NO_LOGS: $service — no Loki stream during the last $LookbackMinutes minutes"
            $failed = $true
            continue
        }
        $sample = @($response.data.result)[0].values[0][1]
        try {
            $payload = $sample | ConvertFrom-Json -ErrorAction Stop
            if (-not $payload.message) {
                Write-Warning "INVALID_JSON: $service — log line has no message property"
                $failed = $true
            } else {
                Write-Host "PASS: $service — JSON logs received in Loki"
            }
        } catch {
            Write-Warning "NOT_JSON: $service — received logs, but not the expected JSON format"
            $failed = $true
        }
    } catch {
        Write-Warning "LOKI_QUERY_FAILED: $service — $($_.Exception.Message)"
        $failed = $true
    }
}

if ($failed) {
    throw 'Loki verification did not pass for all core services. Check container logs, traffic, and Loki ingestion.'
}
Write-Host 'PASS: All core services have searchable JSON logs in Loki.'
