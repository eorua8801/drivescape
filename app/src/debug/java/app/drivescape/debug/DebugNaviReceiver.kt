package app.drivescape.debug

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drivescape.drive.DriveHub

/**
 * 디버그 빌드 전용 테스트 훅. PC에서 내비 경로를 걸고, NaviHelper 이벤트를 로그(태그 DebugNavi)로 본다.
 *
 *   adb shell am broadcast -a app.drivescape.DEBUG_NAVI -n app.drivescape/.debug.DebugNaviReceiver --es cmd location
 *   ... --es cmd route --ef lat 37.40 --ef lon 127.10 --es name 테스트
 *   ... --es cmd state | cancel | arrive (에뮬레이터 차는 움직이지 않으므로 도착 이벤트를 흉내 냄)
 */
class DebugNaviReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DriveHub.init(context)
        val h = DriveHub.navi?.helper ?: run { Log.w(TAG, "NaviHelper 없음"); return }
        if (!logging) { h.addListener(logger); logging = true }
        when (intent.getStringExtra("cmd")) {
            "location" -> h.getCurrentLocationInfo()
            "state" -> { h.getRouteStateInfo(); h.getDestinationInfo() }
            "cancel" -> h.cancelRoute()
            "arrive" -> DriveHub.navi?.debugArrive()
            "route" -> {
                val lat = intent.getFloatExtra("lat", Float.NaN).toDouble()
                val lon = intent.getFloatExtra("lon", Float.NaN).toDouble()
                if (lat.isNaN() || lon.isNaN()) { Log.w(TAG, "lat/lon 필요"); return }
                val name = intent.getStringExtra("name") ?: "Drivescape 테스트"
                Log.i(TAG, "requestRoute $lat,$lon $name")
                h.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiName = name, poiId = "", address = "", poiSubId = ""))
            }
            else -> Log.w(TAG, "cmd: location | route | state | cancel")
        }
    }

    companion object {
        private const val TAG = "DebugNavi"
        private var logging = false
        private val logger = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { Log.i(TAG, "location $currentLocationInfo") }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.i(TAG, "routeStarted $routeStartInfo") }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { Log.i(TAG, "routeState $routeStateInfo") }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) { Log.i(TAG, "destination remain=${destinationInfo.remainTime}s ${destinationInfo.remainDistance}m") }
            override fun onDrivingInfo(drivingInfo: DrivingInfo) { Log.i(TAG, "driving remain=${drivingInfo.destination.duration}s ${drivingInfo.destination.distance}m") }
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { Log.i(TAG, "arrived $destinationArrivedInfo") }
            override fun onRouteCancelled() { Log.i(TAG, "routeCancelled") }
            override fun onRoutingCompleted() { Log.i(TAG, "routingCompleted") }
            override fun onNavigationStatus(status: NavigationStatus) { Log.i(TAG, "status $status") }
            override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "error $errorCode") }
        }
    }
}
