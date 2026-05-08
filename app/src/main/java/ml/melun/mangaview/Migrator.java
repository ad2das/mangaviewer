package ml.melun.mangaview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.MainPage;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;

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
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
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
        Integer result = mw.doInBackground();
        if(isStopped()) {
            running = false;
            return Result.failure();
        }
        mw.onPostExecute(result);
        return result == 0 ? Result.success() : Result.failure();
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
        List<MTitle> newFavorites, newRecents;
        List<String> failed;
        Bundle bundle;

        protected void onPreExecute() {
            startNotification();
        }


        protected void onProgressUpdate(String... values) {
            String msg = current +" / " + sum+"\n앱을 종료하지 말아주세요.\n";
            if(values !=null && values.length>0) msg += values[0];
            lastProgressMessage = msg;
            sendBroadcast(MIGRATE_PROGRESS, msg);
        }

        protected Integer doInBackground(Void... voids) {
            // check domain
            MainPage mp = new MainPage(getHttpClient());
            if(mp.getRecent().size()<1)
                return 1;

            List<MTitle> recents = p.getRecent();
            sum += recents.size();
            List<MTitle> favorites = p.getFavorite();
            sum += favorites.size();
            //recent data

            removeDups(favorites);
            removeDups(recents);

            newRecents = new ArrayList<>();
            newFavorites = new ArrayList<>();
            failed = new ArrayList<>();

            for(int i=0; i<recents.size(); i++){
                try {
                    current++;
                    MTitle newTitle = findTitle(recents.get(i));
                    onProgressUpdate(newTitle == null ? "" : newTitle.getName());
                    if(newTitle !=null)
                        newRecents.add(newTitle);
                    else
                        failed.add(recents.get(i).getName());
                }catch (Exception e){
                    ml.melun.mangaview.report.CrashReporter.record(e);
                    failed.add(recents.get(i).getName());
                }
            }
            for(int i=0; i<favorites.size(); i++){
                try {
                    current++;
                    MTitle newTitle = findTitle(favorites.get(i));
                    onProgressUpdate(newTitle == null ? "" : newTitle.getName());
                    if(newTitle !=null)
                        newFavorites.add(newTitle);
                    else
                        failed.add(favorites.get(i).getName());
                }catch (Exception e){
                    ml.melun.mangaview.report.CrashReporter.record(e);
                    failed.add(favorites.get(i).getName());
                }
            }

            p.setFavorites(newFavorites);
            p.setRecents(newRecents);

            //remove bookmarks
            p.resetViewerBookmark();
            p.resetBookmark();

            return 0;
        }

        void removeDups(List<MTitle> titles){
            for(int i=0; i<titles.size(); i++){
                MTitle target = titles.get(i);
                for(int j =0 ; j<titles.size(); j++){
                    if(j!=i && titles.get(j).getId() == target.getId()){
                        titles.remove(i);
                        i--;
                        break;
                    }
                }
            }
        }

        MTitle findTitle(String title){
            return findTitle(new MTitle(title,-1,"", "",new ArrayList<>(),"", base_comic));
        }

        MTitle findTitle(MTitle title){
            String name = title.getName();
            Search s = new Search(name,0, base_comic);
            while(!s.isLast()){
                s.fetch(getHttpClient());
                for(Title t : s.getResult()){
                    if(t.getName().equals(name)){
                        return t.minimize();
                    }
                }
            }
            return null;
        }

        protected void onPostExecute(Integer resCode) {
            if(resCode == 0){
                StringBuilder builder = new StringBuilder();
                builder.append("기록 업데이트 완료.\n실패한 항목: ");
                builder.append(failed.size());
                builder.append("개\n");
                for(String t : failed){
                    builder.append("\n").append(t);
                }
                resultIntent.setAction(MIGRATE_RESULT);
                resultIntent.putExtra("msg",builder.toString());
                endNotification();
                sendBroadcast(MIGRATE_SUCCESS, builder.toString());
            }
            else if(resCode == 1) {
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
