package app.drivescape.drive

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 내비 연동 (Pleos NaviHelper SDK): 목적지까지 남은 시간 → 도착 빌드업·피날레.
 *
 * - 경로 시작(onRouteStarted)의 예상 시간, 주행 중 갱신(onDrivingInfo), 목적지 정보 조회(백업)로 남은 시간을 받는다.
 * - 도착(onDestinationArrived) → 0분 → Composer가 도착 차임을 울린다.
 * - 경로 취소·자유 주행 → null → 빌드업 없이 평소대로.
 * - NaviHelper가 없는 차량에서는 초기화만 실패하고 앱은 그대로 동작한다.
 */
class NaviSignals(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    var helper: NaviHelper? = null; private set
    @Volatile private var routing = false
    /** 도착 후 잠시 0분을 유지해 차임이 울리게 하고, 그 뒤 경로 없음으로 돌린다 */
    @Volatile private var arrivedAt = 0L

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            routing = true; arrivedAt = 0L
            setRemaining(routeStartInfo.duration)
            schedulePoll()
        }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            routing = true
            setRemaining(drivingInfo.destination.duration)
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            if (routing) setRemaining(destinationInfo.remainTime)
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            // isRouting은 "경로 탐색 중"이라 안내 중에도 false다 → 안내 여부는 naviMode로 판단
            routing = routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING || routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING
            if (routing) { helper?.getDestinationInfo(); schedulePoll() } else clearUnlessArrived()
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            routing = false
            arrivedAt = SystemClock.elapsedRealtime()
            setRemaining(0, arrival = true)
            main.postDelayed({ clearUnlessArrived(force = true) }, ARRIVAL_HOLD_MS)
        }
        override fun onRouteCancelled() { routing = false; clearUnlessArrived() }
        override fun onRoutingCompleted() { routing = false; clearUnlessArrived() }
        override fun onNavigationStatus(status: NavigationStatus) {
            if (status == NavigationStatus.ROUTE_DRIVE) {
                routing = true; helper?.getDestinationInfo(); schedulePoll()
            } else if (status == NavigationStatus.FREE_DRIVE || status == NavigationStatus.INACTIVE) {
                routing = false; clearUnlessArrived()
            }
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "NaviHelper error: $errorCode")
        }
    }

    fun start() {
        val h = try {
            NaviHelper(context).also { it.addListener(listener) }
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper 생성 실패", e); DriveHub.naviStatus = SignalStatus.UNSUPPORTED; return
        }
        helper = h
        try {
            h.initialize(
                onConnected = {
                    DriveHub.naviStatus = SignalStatus.WAITING
                    guard { h.getRouteStateInfo() }
                },
                onFailed = { e -> Log.w(TAG, "NaviHelper 연결 실패", e); DriveHub.naviStatus = SignalStatus.UNSUPPORTED }
            )
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper 초기화 실패", e); DriveHub.naviStatus = SignalStatus.UNSUPPORTED
        }
    }

    /**
     * 남은 시간 반영. 0초는 도착 이벤트에서만 받아들인다:
     * 경로를 바꿀 때 내비가 이전 목적지를 "0초·0m"로 잠깐 알려 주는데, 이를 도착으로 오해하면 차임이 잘못 울린다.
     */
    private fun setRemaining(seconds: Int, arrival: Boolean = false) {
        if (seconds <= 0 && !arrival) return
        DriveHub.naviStatus = SignalStatus.LIVE
        val min = maxOf(0, seconds) / 60f
        DriveHub.update { it.copy(remainingMin = min) }
    }

    /** 디버그 빌드의 테스트 훅에서만 부른다: 실제 도착 이벤트와 같은 경로로 도착을 흉내 낸다 */
    fun debugArrive() = listener.onDestinationArrived(DestinationArrivedInfo(0, 0, "debug", ""))

    private fun clearUnlessArrived(force: Boolean = false) {
        val holding = arrivedAt != 0L && SystemClock.elapsedRealtime() - arrivedAt < ARRIVAL_HOLD_MS
        if (holding && !force) return
        if (routing) return
        arrivedAt = 0L
        DriveHub.naviStatus = SignalStatus.WAITING
        DriveHub.update { it.copy(remainingMin = null) }
    }

    /** onDrivingInfo가 오지 않는 내비 구현에 대비해, 경로 안내 중에는 10초마다 목적지 정보를 다시 묻는다 */
    private val poll = object : Runnable {
        override fun run() {
            if (!routing) return
            guard { helper?.getDestinationInfo() }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun schedulePoll() {
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w(TAG, "NaviHelper 호출 실패", e) }
    }

    companion object {
        private const val TAG = "NaviSignals"
        private const val POLL_MS = 10_000L
        private const val ARRIVAL_HOLD_MS = 30_000L
    }
}
