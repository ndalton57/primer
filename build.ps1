#requires -Version 5.1
<#
.SYNOPSIS
  Ephemeral builder for Primer. Compiles the agent loader into primer.jar using a
  project-local JDK downloaded into .build\. Nothing is installed on the host
  (no PATH / Program Files / registry changes).

.PARAMETER Purge
  Delete the project-local .build\ folder and exit.
#>
[CmdletBinding()]
param([switch]$Purge)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root     = $PSScriptRoot
$build    = Join-Path $root '.build'
$jdkDir   = Join-Path $build 'jdk'
$zip      = Join-Path $build 'jdk.zip'
$out      = Join-Path $build 'out'
$srcDir   = Join-Path $root 'src'
$jarOut   = Join-Path $root 'primer.jar'
$manifest = Join-Path $root 'manifest.txt'

if ($Purge) {
    if (Test-Path $build) { Remove-Item $build -Recurse -Force; Write-Host "[build] Purged $build" }
    else { Write-Host "[build] Nothing to purge ($build does not exist)." }
    return
}

New-Item -ItemType Directory -Force -Path $build | Out-Null

# Acquire a JDK into the project-local .build\ folder (transient, reused on re-runs).
$javac = Get-ChildItem -Path $jdkDir -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javac) {
    $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse'
    Write-Host "[build] Downloading Temurin JDK 21 (one-time, into .build\) ..."
    Invoke-WebRequest -Uri $url -OutFile $zip
    if (Test-Path $jdkDir) { Remove-Item $jdkDir -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $jdkDir -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    $javac = Get-ChildItem -Path $jdkDir -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $javac) { throw "javac.exe not found." }
$bin = $javac.Directory.FullName
$jar = Join-Path $bin 'jar.exe'
Write-Host "[build] Using JDK: $($javac.Directory.Parent.FullName)"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$srcFiles = @(Get-ChildItem -Path $srcDir -Recurse -Filter *.java | ForEach-Object FullName)
if ($srcFiles.Count -eq 0) { throw "No .java sources found under $srcDir" }
Write-Host "[build] Compiling $($srcFiles.Count) source file(s) ..."
& $javac --release 21 -d $out @srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

Write-Host "[build] Packaging primer.jar ..."
& $jar --create --file $jarOut --manifest $manifest -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }

Write-Host ""
Write-Host "[build] Done -> $jarOut"
Write-Host "[build] Use it once in your launch command (before -jar):  -javaagent:primer.jar"
Write-Host "[build] Then drop agent jars into the .\agents\ folder next to your application."
