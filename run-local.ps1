
$ErrorActionPreference = "Stop"

# Resolve .env from the same directory as this script
$envFile = Join-Path $PSScriptRoot ".env"

if (!(Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw ".env file nahi mili: $envFile"
}

Write-Host "Loading environment variables from .env..."

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()

    # Skip empty lines and comments
    if (!$line -or $line.StartsWith("#") -or !$line.Contains("=")) {
        return
    }

    # Split only at the first '='
    $parts = $line -split "=", 2
    $key = $parts[0].Trim()
    $value = $parts[1].Trim()

    # Validate environment variable names
    if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        Write-Warning "Skipping invalid environment variable name."
        return
    }

    # Remove matching surrounding quotes, if present
    if (
    ($value.Length -ge 2) -and
            (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'"))
            )
    ) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    # Set variables for this script and child processes
    [Environment]::SetEnvironmentVariable(
            $key,
            $value,
            [EnvironmentVariableTarget]::Process
    )
}

Write-Host "Environment variables loaded."

# Validate PayU production credentials without printing their values
if ([string]::IsNullOrWhiteSpace($env:PAYU_MERCHANT_KEY)) {
    throw "PAYU_MERCHANT_KEY missing or empty in .env"
}

if ([string]::IsNullOrWhiteSpace($env:PAYU_MERCHANT_SALT)) {
    throw "PAYU_MERCHANT_SALT missing or empty in .env"
}

# Set production defaults only if .env does not define them
if ([string]::IsNullOrWhiteSpace($env:PAYU_PAYMENT_URL)) {
    $env:PAYU_PAYMENT_URL = "https://api.payu.in/v2/payments"
}

if ([string]::IsNullOrWhiteSpace($env:PAYU_VERIFY_PAYMENT_URL)) {
    $env:PAYU_VERIFY_PAYMENT_URL = "https://info.payu.in/v3/transaction"
}

if ([string]::IsNullOrWhiteSpace($env:PAYU_ENVIRONMENT)) {
    $env:PAYU_ENVIRONMENT = "production"
}

Write-Host "PayU merchant key: loaded"
Write-Host "PayU merchant salt: loaded"
Write-Host "PayU environment: $env:PAYU_ENVIRONMENT"

# Verify Maven is installed
if (!(Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven (mvn) nahi mila. Maven install karo ya Maven Wrapper use karo."
}

Write-Host "Starting Panchayat backend..."
mvn spring-boot:run
