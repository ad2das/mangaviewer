package ml.melun.mangaview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.repository.MigrationRepository;

public class Migrator extends Worker {
    NotificationCompat.Builder notification;
    NotificationManager notificationManager;
    public static final int nid = 16848412;
    public static final String channeld = "MangaViewMG";
    Context serviceContext;
    PendingIntent pendingIntent;
    MigrationWorker mw;
    public static boolean running = false;
    public static final String WORK_NAME = "preference-migration";
    String url = "";
    Intent resultIntent;
    private static String lastProgressMessage = "";

    public static final String MIGRATE_STOP = "ml.melun.mangaview.migrator.STOP";
    public static final String MIGRATE_START = "ml.melun.mangaview.migrator.START";
    public static final String MIGRATE_SUCCESS = "ml.melun.mangaview.migrator.SUCCESS";
    public static final String MIGRATE_FAIL = "ml.melun.mangaview.migrator.FAIL";
    public static final String MIGRATE_PROGRESS = "ml.melun.mangaview.migrator.PROGRESS";
    public static final String MIGRATE_RESULT = "ml.melun.mangaview.migrator.RESULT";

    public static void start(Context context) {
        running = true;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Migrator.class).build();
        MainApplication.getWorkManager(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    public static void requestProgress(Context context) {
        Intent intent = new Intent();
        intent.setAction(MIGRATE_PROGRESS);
        intent.putExtra("msg", lastProgressMessage.length() > 0 ? lastProgressMessage : "...");
        context.sendBroadcast(intent);
    }

    public Migrator(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        serviceContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Result doWork() {
        setupWorker();
        mw = new MigrationWorker();
        mw.onPreExecute();
        MigrationRepository.MigrationResult result = mw.run();
        if(isStopped()) {
            running = false;
            return Result.failure();
        }
        mw.onPostExecute(result);
        return result.success ? Result.success() : Result.failure();
    }

    private void setupWorker() {
        running = true;
        notificationManager = (NotificationManager) serviceContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            //notificationManager.deleteNotificationChannel("mangaView");
            NotificationChannel mchannel = new NotificationChannel(channeld, "MangaView", NotificationManager.IMPORTANCE_LOW);
            mchannel.setDescription("데이터 업데이트");
            mchannel.enableLights(true);
            mchannel.setLightColor(Color.MAGENTA);
            mchannel.enableVibration(false);
            mchannel.setSound(null, null);
            mchannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            notificationManager.createNotificationChannel(mchannel);
        }
        Intent notificationIntent = new Intent(serviceContext, MainActivity.class);
        pendingIntent = PendingIntent.getActivity(serviceContext, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        resultIntent = new Intent(serviceContext, MainActivity.class);
        startNotification();
    }

    private void startNotification() {
        notification = new NotificationCompat.Builder(serviceContext, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle("기록 업데이트중..")
                .setContentText("진행률을 확인하려면 터치")
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        setForegroundAsync(new ForegroundInfo(nid, notification.build()));
    }


    private void endNotification(){
        PendingIntent resultPendingIntent = PendingIntent.getActivity(serviceContext, 0, resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notification = new NotificationCompat.Builder(serviceContext, channeld)
                .setContentIntent(resultPendingIntent)
                .setContentTitle("기록 업데이트 완료")
                .setContentText("결과를 확인하려면 터치")
                .setOngoing(false);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        notificationManager.notify(nid, notification.build());
    }

    private void sendBroadcast(String action){
        sendBroadcast(action, null);
    }

    private void sendBroadcast(String action, String msg){
        Intent intent = new Intent();
        intent.setAction(action);
        if(msg!=null && msg.length()>0)
            intent.putExtra("msg", msg);
        serviceContext.sendBroadcast(intent);
    }

    private class MigrationWorker {

        int sum = 0;
        int current = 0;

        protected void onPreExecute() {
            startNotification();
        }

        protected void onProgressUpdate(String value) {
            String msg = current +" / " + sum+"\n앱을 종료하지 말아주세요.\n";
            if(value != null && value.length() > 0) msg += value;
            lastProgressMessage = msg;
            sendBroadcast(MIGRATE_PROGRESS, msg);
        }

        protected MigrationRepository.MigrationResult run() {
            return MigrationRepository.migrate((current, total, name) -> {
                this.current = current;
                this.sum = total;
                onProgressUpdate(name);
            });
        }

        protected void onPostExecute(MigrationRepository.MigrationResult result) {
            if(result.success){
                StringBuilder builder = new StringBuilder();
                builder.append("기록 업데이트 완료.\n실패한 항목: ");
                builder.append(result.failed.size());
                builder.append("개\n");
                for(String t : result.failed){
                    builder.append("\n").append(t);
                }
                resultIntent.setAction(MIGRATE_RESULT);
                resultIntent.putExtra("msg",builder.toString());
                endNotification();
                sendBroadcast(MIGRATE_SUCCESS, builder.toString());
            }
            else if(result.connectionError) {
                endNotification();
                sendBroadcast(MIGRATE_FAIL, "연결 오류 : 연결을 확인하고 다시 시도해 주세요.");
            }
            running = false;
        }

        protected void onCancelled() {
            //todo?
        }
    }


}
