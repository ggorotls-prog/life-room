package app.recordkit.health;

import android.app.job.JobParameters;
import android.app.job.JobService;

// Moments 작업: 새 사진이 들어왔을 때(끼니 사진 묻기) · 15분마다(Health Connect 새 운동 묻기). 일은 Moments가 한다.
public class MomentJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                if (params.getJobId() == Moments.JOB_PHOTO) Moments.checkPhotos(getApplicationContext());
                else if (params.getJobId() == Moments.JOB_EX) Moments.checkExercise(getApplicationContext());
            } catch (Exception ignored) {
            } finally {
                // 사진 지켜보기는 한 번 쓰면 끝나서 다시 건다
                if (params.getJobId() == Moments.JOB_PHOTO && Moments.photoOn(this) && Moments.photoAllowed(this)) Moments.schedulePhoto(getApplicationContext());
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return params.getJobId() == Moments.JOB_PHOTO; }
}
