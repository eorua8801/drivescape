# Drivescape 시연용 자동 주행 스크립트 (약 1분 45초)
#
# Pleos Connect Emulator(2.1.0 이상)의 Virtual Console API로 실제 차량 신호(기어·속도·와이퍼·
# 방향지시등·브레이크)를 바꾸고, 내비 경로를 걸어 도착 연출까지 보여 주면서 에뮬레이터 화면과 소리를 녹화한다.
#
# 준비: 에뮬레이터가 켜져 있고 Drivescape **debug 빌드**가 설치되어 있을 것
#       (시연 자막·세트 고르기·내비 경로는 debug 빌드의 테스트 훅을 쓴다)
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

# debug 빌드 테스트 훅 (DebugNaviReceiver)
function App([string[]]$extra) {
    & $adb shell am broadcast -a app.drivescape.DEBUG_NAVI -n app.drivescape/.debug.DebugNaviReceiver @extra | Out-Null
}
# am start는 이미 떠 있으면 stderr에 경고를 쓰는데, PowerShell 5.1은 이를 오류로 보므로 cmd로 감싼다
function Front { cmd /c "`"$adb`" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.drivescape/.ui.MainActivity >nul 2>&1" }

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
Front
& $adb shell input keyevent KEYCODE_MEDIA_PAUSE | Out-Null
# 녹화에 앱 본래 음량이 담기도록 차량 미디어 볼륨(존 0, 그룹 0)을 최대로
& $adb shell cmd car_service set-group-volume 0 0 100 | Out-Null
App @('--es', 'cmd', 'set', '--es', 'id', 'ambient_pop')
App @('--es', 'cmd', 'day')
App @('--es', 'cmd', 'demo_on')
for ($i = 0; $i -lt 6; $i++) { Beat; Start-Sleep -Milliseconds 500 }

if (-not $NoRecord) {
    $Out = [IO.Path]::GetFullPath($Out)
    if (Test-Path $Out) { Remove-Item $Out }
    & $adb emu screenrecord start --time-limit 150 $Out | Out-Null
    Write-Host "녹화 시작 → $Out"
}
$clock.Start()

# ---- 주행 대본 ----
At 2;    Say '재생 (스티어링 휠 미디어 키와 같은 신호)'; & $adb shell input keyevent KEYCODE_MEDIA_PLAY | Out-Null
At 9;    Say '기어 D · 출발'; Gear 'Drive'
At 10;   Ramp 0 40 6
At 17;   Say '시내 → 고속'; Ramp 40 80 6
At 23;   Say '비 · 와이퍼 저속'; Wiper 'Low'
At 29;   Say '폭우 · 와이퍼 고속'; Wiper 'High'; Ramp 80 95 4
At 35;   Say '오른쪽 차선 변경'; Turn 'BlinkRight'
At 41;   Turn 'Off'
At 44;   Say '앞차 감속 · 브레이크'; Brake 70; Ramp 95 50 3
At 48;   Brake 0
At 51;   Say '세트 → 로파이'; App @('--es', 'cmd', 'set', '--es', 'id', 'lofi'); Wiper 'Low'
At 59;   Say '비 그침'; Wiper 'Off'
At 64;   Say '세트 → 시티팝'; App @('--es', 'cmd', 'set', '--es', 'id', 'citypop'); Ramp 50 90 5
At 75;   Say '내비 목적지 1분 전 → 고조'; App @('--es', 'cmd', 'route', '--ef', 'lat', '37.4131', '--ef', 'lon', '127.0950', '--es', 'name', 'Home')
At 76.5; Front
At 82;   Say '감속'; Ramp 90 35 5
At 89;   Say '도착 → 차임'; App @('--es', 'cmd', 'arrive')
At 92;   Ramp 35 0 4; Brake 40
At 97;   Brake 0; Say '기어 P · 주차'; Gear 'Park'
At 104;  Say '정지'; & $adb shell input keyevent KEYCODE_MEDIA_PAUSE | Out-Null
At 106

if (-not $NoRecord) {
    & $adb emu screenrecord stop | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "녹화 완료 → $Out"
}
App @('--es', 'cmd', 'demo_off')
App @('--es', 'cmd', 'auto')
# 에뮬레이터 차는 움직이지 않아 경로가 끝나지 않고, "안내 종료"·cancelRoute도 멈춰 있는 경우가 있어 내비 앱을 다시 시작해 경로를 지운다.
# (다시 시작한 직후 한동안은 화면 녹화에 깜빡임이 생길 수 있으니, 다음 녹화는 30초쯤 뒤에)
& $adb shell am force-stop ai.umos.maps.android.navigation.app | Out-Null
Say '끝'
