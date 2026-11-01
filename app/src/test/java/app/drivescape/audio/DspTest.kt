package app.drivescape.audio

import org.junit.Test

/** DSP 부품의 경계 조건 */
class DspTest {
    /**
     * 읽기 위치가 0보다 아주 조금 작을 때(쓰기 위치 바로 뒤를 거의 정확히 가리킬 때)
     * 배열 밖을 읽으면 렌더 루프가 멈춘다 (11/01 시연 녹화 중 실제로 발생).
     */
    @Test
    fun delayLineReadJustBehindWriteHead() {
        for (writes in 1..64) {
            val dl = DelayLine(0.05)
            repeat(writes) { dl.write(0.1f, -0.1f) }
            var sec = writes.toDouble() / SR
            repeat(200) {
                dl.read(sec)          // 예외가 나면 테스트 실패
                sec = Math.nextUp(sec)
            }
        }
    }
}
