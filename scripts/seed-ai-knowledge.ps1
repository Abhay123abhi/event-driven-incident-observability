param(
    [string]$BaseUrl = "http://localhost:9000"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$documents = @(
    @{
        Title = "Database connection pool saturation"
        Path = Join-Path $root "docs/ai-knowledge/database-pool-saturation.md"
        Service = "inventory-service"
        Type = "RUNBOOK"
    },
    @{
        Title = "Service latency investigation"
        Path = Join-Path $root "docs/ai-knowledge/service-latency.md"
        Service = ""
        Type = "RUNBOOK"
    },
    @{
        Title = "Historical inventory database pool exhaustion"
        Path = Join-Path $root "docs/ai-knowledge/past-inventory-db-incident.md"
        Service = "inventory-service"
        Type = "POSTMORTEM"
    }
)

foreach ($document in $documents) {
    $body = @{
        title = $document.Title
        content = Get-Content -Raw $document.Path
        service = $document.Service
        documentType = $document.Type
    } | ConvertTo-Json -Depth 4

    $result = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/api/ai/knowledge" `
        -ContentType "application/json" `
        -Body $body

    Write-Host "Seeded $($document.Title) as knowledge document $($result.id)"
}

Write-Host "AI knowledge seed complete."
