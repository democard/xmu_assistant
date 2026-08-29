$ErrorActionPreference = "Stop"

$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
# SDK 按优先级探测：环境变量 > 标准 Android Studio 路径
$CandidateSdk = @(
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk")
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
$Sdk = $CandidateSdk
$Emulator = Join-Path $Sdk "emulator\emulator.exe"
$Adb = Join-Path $Sdk "platform-tools\adb.exe"
$AvdName = "xmu_assistant_api30"
# 指向真实构建产物：AGP 输出 app-release.apk（与发布的 release 变体一致）
$Apk = Join-Path $Root "android\app\build\outputs\apk\release\app-release.apk"
$Log = Join-Path ([Environment]::GetFolderPath("Desktop")) "xmu-android-test.log"

function Write-Step([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $Message
    Add-Content -LiteralPath $Log -Value $line -Encoding UTF8
}

function Fail([string]$Message) {
    Write-Step "ERROR: $Message"
    Write-Host ""
    Write-Host "A log was written to: $Log"
    exit 1
}

function Get-EmulatorDevice {
    $devices = & $Adb devices 2>$null
    foreach ($line in $devices) {
        if ($line -match "^(emulator-\d+)\s+device$") {
            return $Matches[1]
        }
    }
    return $null
}

Write-Step "Starting Android test launcher"
Write-Step "APK=$Apk"

if (-not (Test-Path -LiteralPath $Emulator)) { Fail "Android emulator not found: $Emulator" }
if (-not (Test-Path -LiteralPath $Adb)) { Fail "adb not found: $Adb" }
if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK not found: $Apk" }

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk

$device = Get-EmulatorDevice
if (-not $device) {
    Write-Step "Starting emulator: $AvdName"
    $process = Start-Process -FilePath $Emulator -ArgumentList @(
        "-avd", $AvdName,
        "-no-snapshot-load",
        "-gpu", "swiftshader_indirect"
    ) -PassThru
} else {
    Write-Step "Using running emulator: $device"
}

Write-Step "Waiting for emulator device"
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    $device = Get-EmulatorDevice
    if ($process -and $process.HasExited) {
        Fail "Emulator exited early with code $($process.ExitCode)"
    }
} until ($device -or (Get-Date) -gt $deadline)
if (-not $device) { Fail "Timed out waiting for emulator device" }

Write-Step "Waiting for Android boot on $device"
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    $boot = (& $Adb -s $device shell getprop sys.boot_completed 2>$null).Trim()
} until ($boot -eq "1" -or (Get-Date) -gt $deadline)
if ($boot -ne "1") { Fail "Timed out waiting for Android boot" }

Write-Step "Installing APK"
& $Adb -s $device install -r $Apk 2>&1 | Tee-Object -FilePath $Log -Append
if ($LASTEXITCODE -ne 0) { Fail "APK install failed" }

Write-Step "Launching xmu assistant"
& $Adb -s $device shell am start -n "com.xmu.assistant/.MainActivity" 2>&1 | Tee-Object -FilePath $Log -Append
if ($LASTEXITCODE -ne 0) { Fail "App launch failed" }

Write-Step "Done"
Write-Host ""
Write-Host "Done. The phone emulator should now show xmu assistant."
