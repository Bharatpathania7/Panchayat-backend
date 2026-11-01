$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env"

if (!(Test-Path $envFile)) {
    throw ".env file nahi mili: $envFile"
}

Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()

    if (!$line -or $line.StartsWith("#") -or !$line.Contains("=")) {
        return
    }

    $parts = $line -split "=", 2
    $key = $parts[0].Trim()
    $value = $parts[1].Trim()

    if ($key -match '^[A-Za-z_][A-Za-z0-9_]*$') {
        [Environment]::SetEnvironmentVariable(
                $key, $value, "Process"
        )
    }
}

Write-Host "Environment variables loaded from .env"
mvn spring-boot:run