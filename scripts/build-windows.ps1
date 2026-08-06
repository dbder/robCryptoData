# One-shot native-image build for robCryptoData on a fresh Windows machine/VM.
# Run from an elevated PowerShell inside the project directory:
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1
# Produces target\robCryptoData.exe

$ErrorActionPreference = "Stop"
$tools = "$env:USERPROFILE\build-tools"
New-Item -ItemType Directory -Force -Path $tools | Out-Null

# --- 1. Visual Studio Build Tools (C++ workload) - required by native-image ---
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$hasVc = (Test-Path $vswhere) -and (& $vswhere -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -latest -property installationPath)
if (-not $hasVc) {
    Write-Host "Installing Visual Studio Build Tools (this takes a while)..."
    winget install -e --id Microsoft.VisualStudio.2022.BuildTools `
        --accept-source-agreements --accept-package-agreements `
        --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
} else {
    Write-Host "Visual Studio C++ build tools already present."
}

# --- 2. Oracle GraalVM for JDK 25 (x64) ---
$graalHome = Get-ChildItem -Path $tools -Directory -Filter "graalvm-jdk-25*" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not $graalHome) {
    Write-Host "Downloading GraalVM for JDK 25..."
    $graalZip = "$tools\graalvm.zip"
    Invoke-WebRequest -Uri "https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_windows-x64_bin.zip" -OutFile $graalZip
    Expand-Archive -Path $graalZip -DestinationPath $tools
    Remove-Item $graalZip
    $graalHome = Get-ChildItem -Path $tools -Directory -Filter "graalvm-jdk-25*" | Select-Object -First 1 -ExpandProperty FullName
}
Write-Host "GraalVM: $graalHome"

# --- 3. Apache Maven ---
$mavenHome = Get-ChildItem -Path $tools -Directory -Filter "apache-maven-*" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not $mavenHome) {
    Write-Host "Downloading Maven..."
    $mavenZip = "$tools\maven.zip"
    Invoke-WebRequest -Uri "https://dlcdn.apache.org/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip" -OutFile $mavenZip
    Expand-Archive -Path $mavenZip -DestinationPath $tools
    Remove-Item $mavenZip
    $mavenHome = Get-ChildItem -Path $tools -Directory -Filter "apache-maven-*" | Select-Object -First 1 -ExpandProperty FullName
}
Write-Host "Maven: $mavenHome"

# --- 4. Build ---
$env:JAVA_HOME = $graalHome
$env:Path = "$graalHome\bin;$mavenHome\bin;$env:Path"
Set-Location (Split-Path -Parent $PSScriptRoot)
mvn -B -Pnative package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host ""
Write-Host "Done: $(Resolve-Path target\robCryptoData.exe)"
