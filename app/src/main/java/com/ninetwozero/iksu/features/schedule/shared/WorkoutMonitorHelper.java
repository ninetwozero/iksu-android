package com.ninetwozero.iksu.features.schedule.shared;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import com.ninetwozero.iksu.app.IksuApp;
import com.ninetwozero.iksu.models.Workout;
import com.ninetwozero.iksu.utils.Constants;
import com.ninetwozero.iksu.utils.DateUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.realm.Realm;
import io.realm.RealmQuery;
import timber.log.Timber;

public class WorkoutMonitorHelper {
    private static final long PERIODIC_INTERVAL = TimeUnit.MINUTES.toMillis(30);

    private final Context context;
    private final JobScheduler jobScheduler;

    public WorkoutMonitorHelper(final Context context) {
        this.context = context;
        this.jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    public void schedule(final Workout workout) {
        Timber.i("Scheduling job for id=%s", workout.getId());
        Timber.i("Scheduling periodic job");

        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(Constants.USERNAME, IksuApp.getActiveUsername());

        final ComponentName monitorService = new ComponentName(context, IksuMonitorService.class);
        jobScheduler.schedule(createPeriodicTask(monitorService, bundle));
        jobScheduler.schedule(createOneHourBeforeTask(monitorService, bundle, workout));
    }

    public void unschedule(final Workout workout) {
        Timber.i("Unscheduling job for id=%s", workout.getId());
        jobScheduler.cancel(Integer.parseInt(workout.getId()));

        try (Realm realm = Realm.getDefaultInstance()){
            final long monitorsLeft = realm.where(Workout.class)
                .equalTo(Constants.CONNECTED_ACCOUNT, IksuApp.getActiveUsername())
                .equalTo(Constants.MONITORING, true)
                .notEqualTo(Constants.ID, workout.getId())
                .count();

            if (monitorsLeft == 0) {
                jobScheduler.cancel(IksuMonitorService.PERIODIC_JOB_ID);
                Timber.i("Unscheduling periodic job");
            }
        }
    }

    public void unschedule(final List<Workout> workouts) {
        final String[] workoutIds = new String[workouts.size()];
        for(int i = 0, max = workoutIds.length; i < max; i++) {
            workoutIds[i] = workouts.get(i).getId();
            jobScheduler.cancel(Integer.parseInt(workouts.get(i).getId()));
            Timber.i("Canceling job for id=%s", workoutIds[i]);
        }

        try (Realm realm = Realm.getDefaultInstance()){
            RealmQuery<Workout> query = realm.where(Workout.class)
                .beginGroup()
                .equalTo(Constants.CONNECTED_ACCOUNT, IksuApp.getActiveUsername())
                .equalTo(Constants.MONITORING, true)
                .endGroup();

            if (workoutIds.length > 0) {
                query.not()
                .in(Constants.ID, workoutIds);
            }

            final long monitorsLeft = query.count();
            if (monitorsLeft == 0) {
                Timber.i("Canceling periodic job");
                jobScheduler.cancel(IksuMonitorService.PERIODIC_JOB_ID);
            }
        }
    }

    private JobInfo createPeriodicTask(ComponentName service, PersistableBundle bundle) {
        return new JobInfo.Builder(IksuMonitorService.PERIODIC_JOB_ID, service)
            .setExtras(bundle)
            .setPeriodic(PERIODIC_INTERVAL)
            .setPersisted(true)
            .build();
    }

    private JobInfo createOneHourBeforeTask(ComponentName service, PersistableBundle bundle, Workout workout) {
        final long now = System.currentTimeMillis();
        final long workoutCancellationDeadline = workout.getStartDate() - now - TimeUnit.HOURS.toMillis(1);
        return new JobInfo.Builder(Integer.parseInt(workout.getId()), service)
            .setExtras(bundle)
            .setMinimumLatency(workoutCancellationDeadline - TimeUnit.MINUTES.toMillis(2))
            .setOverrideDeadline(workoutCancellationDeadline + TimeUnit.MINUTES.toMillis(2))
            .setPersisted(true)
            .build();
    }
}
