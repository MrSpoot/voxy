@echo off
setlocal

set "BASE_DIR=%~dp0"
set "PROPERTIES_FILE=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties"

if not exist "%PROPERTIES_FILE%" (
  echo Missing %PROPERTIES_FILE% 1>&2
  exit /b 1
)

for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$line = Get-Content '%PROPERTIES_FILE%' | Where-Object { $_ -like 'distributionUrl=*' } | Select-Object -First 1; if ($null -eq $line) { exit 1 }; $line.Substring('distributionUrl='.Length)"`) do set "DISTRIBUTION_URL=%%I"

if not defined DISTRIBUTION_URL (
  echo distributionUrl is not configured in %PROPERTIES_FILE% 1>&2
  exit /b 1
)

if not defined MAVEN_USER_HOME set "MAVEN_USER_HOME=%USERPROFILE%\.m2"

for %%I in ("%DISTRIBUTION_URL%") do set "ARCHIVE_NAME=%%~nxI"
for /f "tokens=1 delims=?" %%I in ("%ARCHIVE_NAME%") do set "ARCHIVE_NAME=%%I"

set "MAVEN_DIR_NAME=%ARCHIVE_NAME:-bin.zip=%"
set "MAVEN_DIR_NAME=%MAVEN_DIR_NAME:-bin.tar.gz=%"
set "INSTALL_DIR=%MAVEN_USER_HOME%\wrapper\dists\%MAVEN_DIR_NAME%"
set "MAVEN_CMD=%INSTALL_DIR%\bin\mvn.cmd"

if not exist "%MAVEN_CMD%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$distributionUrl = '%DISTRIBUTION_URL%';" ^
    "$archiveName = '%ARCHIVE_NAME%';" ^
    "$mavenUserHome = '%MAVEN_USER_HOME%';" ^
    "$installDir = '%INSTALL_DIR%';" ^
    "$archivePath = Join-Path $mavenUserHome ('wrapper\dists\' + $archiveName);" ^
    "$extractDir = Join-Path $mavenUserHome ('wrapper\dists\.extract-' + [guid]::NewGuid().ToString('N'));" ^
    "New-Item -ItemType Directory -Force -Path (Join-Path $mavenUserHome 'wrapper\dists') | Out-Null;" ^
    "Invoke-WebRequest -Uri $distributionUrl -OutFile $archivePath;" ^
    "if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir };" ^
    "New-Item -ItemType Directory -Force -Path $extractDir | Out-Null;" ^
    "Expand-Archive -LiteralPath $archivePath -DestinationPath $extractDir -Force;" ^
    "$extractedDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1;" ^
    "if ($null -eq $extractedDir) { throw 'Failed to locate extracted Maven distribution.' };" ^
    "if (Test-Path $installDir) { Remove-Item -Recurse -Force $installDir };" ^
    "Move-Item -LiteralPath $extractedDir.FullName -Destination $installDir;" ^
    "Remove-Item -Recurse -Force $extractDir;" ^
    "Remove-Item -Force $archivePath;"

  if errorlevel 1 exit /b %errorlevel%
)

call "%MAVEN_CMD%" %*
