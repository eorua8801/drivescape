# Drivescape 시연용 자동 주행 스크립트
#
# Pleos Connect Emulator(2.1.0 이상)의 Virtual Console API로 실제 차량 신호(기어·속도·와이퍼·
# 방향지시등·브레이크)를 바꾸면서, 에뮬레이터 화면과 소리를 함께 녹화한다.
#
# 준비: 에뮬레이터가 켜져 있고 Drivescape가 설치되어 있을 것.
# 실행: powershell -ExecutionPolicy Bypass -File tools\demo_drive.ps1
#       (녹화 없이 리허설만: -NoRecord)

param(
    [switch]$NoRecord,
    [string]$Out = "$PSScriptRoot\..\..\Drivescape_demo_android.webm"
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$B = 'http://127.0.0.1:8088'

# Virtual Console은 브라우저 세션이 3초 안에 신호를 보내야 "활성"으로 본다 → 1초마다 하트비트
$client = 'drivescape-demo-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$script:lastBeat = [DateTime]::MinValue
function Beat {
    if (([DateTime]::Now - $script:lastBeat).TotalMilliseconds -lt 1000) { return }
    Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$B/browser/session?client=$client" | Out-Null
    $script:lastBeat = [DateTime]::Now
}
function Post([string]$path) {
    Beat
    Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($B + $path) | Out-Null
}
function Gear([string]$g)  { Post "/vehicle/gear?gear=$g" }
function Wiper([string]$m) { Post "/vehicle/body?front_wiper=$m" }
function Turn([string]$m)  { Post "/vehicle/turn-signal?mode=$m" }
$script:speed = 0
$script:brake = 0
function Controls { Post ("/vehicle/controls?speed={0}&accelerator=0&brake={1}" -f [int]$script:speed, [int]$script:brake) }
function Brake([int]$pct) { $script:brake = $pct; Controls }

$clock = [Diagnostics.Stopwatch]::new()
function Say([string]$text) { Write-Host ("[{0,5:N1}s] {1}" -f $clock.Elapsed.TotalSeconds, $text) }
function At([double]$t) {
    while ($clock.Elapsed.TotalSeconds -lt $t) {
        Beat
        $wait = [Math]::Min(0.5, $t - $clock.Elapsed.TotalSeconds)
        if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
    }
}
# 속도를 from → to 로 sec초 동안 부드럽게 바꾼다 (프로토타입 시나리오와 같은 ease-in-out)
function Ramp([double]$from, [double]$to, [double]$sec) {
    $start = $clock.Elapsed.TotalSeconds
    while ($true) {
        $p = [Math]::Min(1.0, ($clock.Elapsed.TotalSeconds - $start) / $sec)
        $e = if ($p -lt 0.5) { 2 * $p * $p } else { 1 - [Math]::Pow(-2 * $p + 2, 2) / 2 }
        $script:speed = [Math]::Round($from + ($to - $from) * $e)
        Controls
        if ($p -ge 1.0) { break }
        Start-Sleep -Milliseconds 200
    }
}

# ---- 준비 ----
& $adb forward tcp:8088 tcp:8088 | Out-Null
Gear 'Park'; $script:speed = 0; Brake 0; Wiper 'Off'; Turn 'Off'
& $adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.drivescape/.ui.MainActivity | Out-Null
& $adb shell input keyevent KEYCODE_MEDIA_PAUSE | Out-Null
# 녹화에 앱 본래 음량이 담기도록 차량 미디어 볼륨(존 0, 그룹 0)을 최대로
& $adb shell cmd car_service set-group-volume 0 0 100 | Out-Null
for ($i = 0; $i -lt 6; $i++) { Beat; Start-Sleep -Milliseconds 500 }

if (-not $NoRecord) {
    $Out = [IO.Path]::GetFullPath($Out)
    if (Test-Path $Out) { Remove-Item $Out }
    & $adb emu screenrecord start --time-limit 110 $Out | Out-Null
    Write-Host "녹화 시작 → $Out"
}
$clock.Start()

# ---- 주행 대본 (약 90초) ----
At 2;    Say '재생 (스티어링 휠 미디어 키와 같은 신호)'; & $adb shell input keyevent KEYCODE_MEDIA_PLAY | Out-Null
At 3;    Say '주차 중 · 정차 앰비언트 (66 BPM, 리듬 없음)'
At 11;   Say '기어 D · 출발'; Gear 'Drive'
At 12;   Ramp 0 35 7
At 20;   Say '시내 주행'; Ramp 35 70 6
At 27;   Say '비가 오기 시작 · 와이퍼 저속'; Wiper 'Low'
At 34;   Say '폭우 속 고속도로 · 와이퍼 고속'; Wiper 'High'; Ramp 70 95 5
At 41;   Say '오른쪽으로 차선 변경 · 방향지시등'; Turn 'BlinkRight'
At 47;   Turn 'Off'
At 50;   Say '앞차 감속 · 브레이크'; Brake 70; Ramp 95 45 3
At 54;   Brake 0
At 56;   Say '비가 잦아듦'; Wiper 'Low'
At 62;   Say '비 그침'; Wiper 'Off'
At 65;   Say '좌회전 준비'; Turn 'BlinkLeft'; Ramp 45 25 4
At 72;   Turn 'Off'
At 74;   Say '감속 · 정차'; Ramp 25 0 5; Brake 40
At 81;   Brake 0; Say '기어 P · 주차'; Gear 'Park'
At 90;   Say '정지'; & $adb shell input keyevent KEYCODE_MEDIA_PAUSE | Out-Null
At 92

if (-not $NoRecord) {
    & $adb emu screenrecord stop | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "녹화 완료 → $Out"
}
Say '끝'
