package app.drivescape.drive

/**
 * 음악 엔진이 보는 "주행 상태" 한 장면. 신호 수집 계층(차량 SDK, 시나리오)이 만들고
 * 렌더 스레드가 블록마다 읽는다. 불변 객체로 주고받는다.
 */
data class DriveState(
    val gear: Gear = Gear.D,
    /** km/h */
    val speed: Float = 48f,
    /** 0 끔, 1 저속, 2 고속 */
    val wiper: Int = 0,
    /** -1 왼쪽, 0 끔, 1 오른쪽 */
    val turn: Int = 0,
    val brake: Boolean = false,
    /** 도착까지 남은 분. 내비 정보가 없으면 null → 빌드업·도착 피날레 없이 동작 */
    val remainingMin: Float? = null,
    val night: Boolean = false
)

enum class Gear { P, D }
