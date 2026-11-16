package app.recordkit.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant

// 운동 알림(2026-11-16 룡): 갤럭시 핏 → 삼성 헬스 → Health Connect에 들어온 운동을 읽는다. 읽기만 한다(쓰지 않음).
// 저절로 저장하지 않고, 새 운동이 보이면 「걷기 20분, 운동으로 적을까요?」 알림만 띄운다(Moments).
// Health Connect 라이브러리가 Kotlin 전용이라 이 파일만 Kotlin. 자바에서는 멈춰 기다리는(runBlocking) 함수로 부른다 — 화면 줄(메인)에서 부르지 않는다.
object HcReader {
    @JvmField
    val PERMS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    /** 이 폰에 Health Connect가 있나 */
    @JvmStatic
    fun available(c: Context): Boolean =
        HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE

    /** 허락 창을 여는 화면 이동 */
    @JvmStatic
    fun permissionIntent(c: Context): Intent =
        PermissionController.createRequestPermissionResultContract().createIntent(c, PERMS)

    /** 운동 읽기 + 앱이 꺼져 있을 때 읽기, 둘 다 허락됐나 */
    @JvmStatic
    fun granted(c: Context): Boolean = try {
        runBlocking { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions().containsAll(PERMS) }
    } catch (e: Exception) { false }

    /** 앱이 꺼져 있을 때 읽기를 이 폰의 Health Connect가 지원하나 */
    @JvmStatic
    fun backgroundSupported(c: Context): Boolean = try {
        runBlocking {
            HealthConnectClient.getOrCreate(c).features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }
    } catch (e: Exception) { false }

    /** sinceMs 뒤에 끝난 운동: [끝난 시각(ms), 운동 이름, 분] */
    @JvmStatic
    fun sessionsSince(c: Context, sinceMs: Long): List<Array<String>> = runBlocking {
        val res = HealthConnectClient.getOrCreate(c).readRecords(
            ReadRecordsRequest(
                ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.ofEpochMilli(sinceMs)),
            )
        )
        res.records
            .filter { it.endTime.toEpochMilli() > sinceMs }
            .sortedBy { it.endTime }
            .map {
                val min = (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60000
                arrayOf(it.endTime.toEpochMilli().toString(), name(it.exerciseType), min.toString())
            }
    }

    private fun name(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "걷기"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "달리기"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "자전거"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "등산"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "수영"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "근력 운동"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "맨몸 운동"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "요가"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "필라테스"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "스트레칭"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "계단 오르기"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "인터벌 운동"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "일립티컬"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "로잉"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "배드민턴"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "축구"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "농구"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "탁구"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "테니스"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "춤"
        else -> "운동"
    }
}
