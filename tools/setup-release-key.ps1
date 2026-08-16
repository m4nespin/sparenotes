$ErrorActionPreference = "Stop"

$projectRoot = Split-Path $PSScriptRoot -Parent
$signingDirectory = Join-Path $projectRoot ".release-signing"
$keystore = Join-Path $signingDirectory "trailsafe-release.p12"
$properties = Join-Path $signingDirectory "keystore.properties"

if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $properties)) {
    throw "Release signing files already exist. Refusing to overwrite them."
}

New-Item -ItemType Directory -Path $signingDirectory | Out-Null
$passwordBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes)

try {
    & keytool -genkeypair -noprompt `
        -keystore $keystore `
        -storetype PKCS12 `
        -storepass $password `
        -keypass $password `
        -alias trailsafe `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname "CN=TrailSafe Release, O=TrailSafe"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

    [IO.File]::WriteAllLines($properties, @(
        "storeFile=.release-signing/trailsafe-release.p12"
        "storePassword=$password"
        "keyAlias=trailsafe"
        "keyPassword=$password"
    ))
} catch {
    if (Test-Path -LiteralPath $signingDirectory) {
        $resolvedSigningDirectory = (Resolve-Path -LiteralPath $signingDirectory).Path
        if ($resolvedSigningDirectory -ne (Join-Path $projectRoot ".release-signing")) {
            throw "Refusing to clean unexpected path: $resolvedSigningDirectory"
        }
        Remove-Item -LiteralPath $resolvedSigningDirectory -Recurse -Force
    }
    throw
} finally {
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    $password = $null
}

& icacls $signingDirectory /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not restrict release signing file permissions" }

Write-Host "Release key created in $signingDirectory"
Write-Host "Back up this directory securely before publishing TrailSafe."
