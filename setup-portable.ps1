param(
  [switch]$SkipNode,
  [switch]$SkipJdk,
  [switch]$SkipAndroidSdk
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Temp = Join-Path $Root ".web2blue-setup-temp"
$NodeDir = Join-Path $Root "node"
$JdkDir = Join-Path $Root "jdk17"
$SdkDir = Join-Path $Root "android-sdk"
$CliDir = Join-Path $Root "packages\cli"

function Write-Step($Text) {
  Write-Host ""
  Write-Host "[Web2Blue] $Text" -ForegroundColor Cyan
}

function Download-File($Url, $OutFile) {
  Write-Host "[Web2Blue] Downloading: $Url"
  Invoke-WebRequest -Uri $Url -OutFile $OutFile -MaximumRedirection 10
}

function Reset-Dir($Path) {
  if (Test-Path -LiteralPath $Path) {
    Remove-Item -LiteralPath $Path -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Copy-DirectoryContents($From, $To) {
  New-Item -ItemType Directory -Force -Path $To | Out-Null
  Get-ChildItem -LiteralPath $From -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $To -Recurse -Force
  }
}

function Install-PortableNode {
  Write-Step "Installing portable Node.js"
  $base = "https://nodejs.org/dist/latest-v22.x"
  $sums = (Invoke-WebRequest -Uri "$base/SHASUMS256.txt").Content
  $zipName = (($sums -split "`n") | Where-Object { $_ -match "node-v.+-win-x64\.zip" } | Select-Object -First 1) -replace "^.*\s+", ""
  if (-not $zipName) { throw "Could not find Node.js win-x64 zip from $base" }

  Reset-Dir $NodeDir
  Reset-Dir $Temp
  $zipPath = Join-Path $Temp $zipName
  Download-File "$base/$zipName" $zipPath
  Expand-Archive -LiteralPath $zipPath -DestinationPath $Temp -Force

  $extracted = Get-ChildItem -LiteralPath $Temp -Directory | Where-Object { Test-Path (Join-Path $_.FullName "node.exe") } | Select-Object -First 1
  if (-not $extracted) { throw "Node.js archive layout not recognized." }
  Copy-DirectoryContents $extracted.FullName $NodeDir
  & (Join-Path $NodeDir "node.exe") --version
}

function Install-PortableJdk {
  Write-Step "Installing portable JDK 17"
  Reset-Dir $JdkDir
  Reset-Dir $Temp

  $zipPath = Join-Path $Temp "temurin-jdk17.zip"
  $url = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
  Download-File $url $zipPath
  Expand-Archive -LiteralPath $zipPath -DestinationPath $JdkDir -Force

  $java = Get-ChildItem -LiteralPath $JdkDir -Recurse -Filter "java.exe" | Where-Object { $_.FullName -match "\\bin\\java\.exe$" } | Select-Object -First 1
  if (-not $java) { throw "JDK archive layout not recognized." }
  & $java.FullName -version
}

function Get-JdkHome {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    return $env:JAVA_HOME
  }
  $java = Get-ChildItem -LiteralPath $JdkDir -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "\\bin\\java\.exe$" } |
    Select-Object -First 1
  if ($java) { return (Split-Path -Parent (Split-Path -Parent $java.FullName)) }
  throw "JDK 17 was not found. Run setup-portable.ps1 without -SkipJdk."
}

function Install-PortableAndroidSdk {
  Write-Step "Installing portable Android SDK"
  Reset-Dir $SdkDir
  Reset-Dir $Temp

  $repoUrl = "https://dl.google.com/android/repository/repository2-1.xml"
  $repoXml = (Invoke-WebRequest -Uri $repoUrl).Content
  $block = [regex]::Match($repoXml, '<remotePackage path="cmdline-tools;latest">[\s\S]*?</remotePackage>').Value
  if (-not $block) { throw "Could not find cmdline-tools;latest in Android repository metadata." }
  $relativeUrl = [regex]::Match($block, '<url>([^<]*commandlinetools-win[^<]*)</url>').Groups[1].Value
  if (-not $relativeUrl) { throw "Could not find Windows command line tools URL." }

  $zipPath = Join-Path $Temp "android-commandlinetools.zip"
  Download-File "https://dl.google.com/android/repository/$relativeUrl" $zipPath
  Expand-Archive -LiteralPath $zipPath -DestinationPath $Temp -Force

  $srcTools = Join-Path $Temp "cmdline-tools"
  $dstTools = Join-Path $SdkDir "cmdline-tools\latest"
  if (!(Test-Path -LiteralPath $srcTools)) { throw "Android command line tools archive layout not recognized." }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dstTools) | Out-Null
  Copy-DirectoryContents $srcTools $dstTools

  $jdkHome = Get-JdkHome
  $env:JAVA_HOME = $jdkHome
  $env:Path = (Join-Path $jdkHome "bin") + ";" + $env:Path

  $sdkmanager = Join-Path $dstTools "bin\sdkmanager.bat"
  if (!(Test-Path -LiteralPath $sdkmanager)) { throw "sdkmanager.bat not found: $sdkmanager" }

  $yes = ("y`n" * 100)
  Write-Host "[Web2Blue] Accepting Android SDK licenses..."
  $yes | & $sdkmanager --sdk_root="$SdkDir" --licenses | Write-Host

  Write-Host "[Web2Blue] Installing Android SDK packages..."
  $packages = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0"
  )
  $yes | & $sdkmanager --sdk_root="$SdkDir" @packages | Write-Host
}

try {
  if (-not $SkipNode) { Install-PortableNode }
  if (-not $SkipJdk) { Install-PortableJdk }
  if (-not $SkipAndroidSdk) { Install-PortableAndroidSdk }

  Write-Step "Installing Web2Blue CLI dependencies"
  $npm = Join-Path $NodeDir "npm.cmd"
  if (Test-Path -LiteralPath $npm) {
    & $npm ci --prefix "$CliDir"
  } else {
    npm ci --prefix "$CliDir"
  }

  Write-Step "Setup finished"
  Write-Host "Now you can run:" -ForegroundColor Green
  Write-Host "  .\web2blue.bat init demo-web"
  Write-Host "  .\web2blue.bat build --src .\demo-web --name `"Web2Blue演示`""
}
finally {
  if (Test-Path -LiteralPath $Temp) {
    Remove-Item -LiteralPath $Temp -Recurse -Force
  }
}
