package ml.melun.mangaview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.CookieManager;
import ml.melun.mangaview.task.LifecycleTask;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.mangaview.DownloadTitle;
import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.filterFolder;
import static ml.melun.mangaview.Utils.useScopedStorageHome;

public class Downloader extends Service {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 8192;
    private static final int PARALLEL_IMAGE_DOWNLOADS = 4;
    String homeDir;
    String baseUrl;
    ArrayList<DownloadTitle> titles;
    ArrayList<JSONArray> selected;
    float progress = 0;
    int maxProgress=1000;
    String notiTitle="";
    public static boolean running = false;
    NotificationCompat.Builder notification;
    public static final String ACTION_START = "ml.melun.mangaview.action.START";
    public static final String ACTION_STOP = "ml.melun.mangaview.action.STOP";
    public static final String ACTION_QUEUE = "ml.melun.mangaview.action.QUEUE";
    public static final String ACTION_FORCE_STOP = "ml.melun.mangaview.action.FORCE_STOP";
    public static final String BROADCAST_STOP = "ml.melun.mangaview.broadcast.STOP";
    downloadTitle dt;
    NotificationManager notificationManager;
    public static final int nid = 16848323;
    public static final String channeld = "MangaViewDL";
    PendingIntent pendingIntent;
    PendingIntent stopIntent;
    Context serviceContext;
    Map<String, String> cookies;
    int failures = 0;

    public static boolean isRunning(){
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceContext = this;
        if(titles==null) titles = new ArrayList<>();
        if(selected==null) selected = new ArrayList<>();
        homeDir = serviceContext.getSharedPreferences("mangaView",Context.MODE_PRIVATE).getString("homeDir", "");
        baseUrl = serviceContext.getSharedPreferences("mangaView",Context.MODE_PRIVATE).getString("url", "");
        if(dt==null) dt = new downloadTitle();
        //android O bullshit
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            //notificationManager.deleteNotificationChannel("mangaView");
            NotificationChannel mchannel = new NotificationChannel(channeld, "MangaView", NotificationManager.IMPORTANCE_LOW);
            mchannel.setDescription("다운로드 상태");
            mchannel.enableLights(true);
            mchannel.setLightColor(Color.MAGENTA);
            mchannel.enableVibration(false);
            mchannel.setSound(null, null);
            mchannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            notificationManager.createNotificationChannel(mchannel);
        }
        Intent notificationIntent = new Intent(this, MainActivity.class);
        pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent previousIntent = new Intent(this, Downloader.class);
        previousIntent.setAction(ACTION_STOP);
        stopIntent = PendingIntent.getService(this, 0, previousIntent, PendingIntent.FLAG_IMMUTABLE);
        startNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent!=null) {
            switch (intent.getAction()) {
                case ACTION_START:
                    break;
                case ACTION_QUEUE:
                    startNotification();
                    if (dt == null) dt = new downloadTitle();
                    try {
                        DownloadTitle target = new Gson().fromJson(intent.getStringExtra("title"), new TypeToken<DownloadTitle>() {}.getType());
                        JSONArray selection = new JSONArray(intent.getStringExtra("selected"));
                        queueTitle(target, selection);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                case ACTION_STOP:
                case ACTION_FORCE_STOP:
                    dt.cancel(true);
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        finishNotification();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void queueTitle(DownloadTitle title, JSONArray selection){
        titles.add(title);
        selected.add(selection);
        updateNotification("");
        if(dt.getStatus() == LifecycleTask.Status.PENDING || dt.getStatus() == LifecycleTask.Status.FINISHED) {
            dt = new downloadTitle();
            dt.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
        }else{
            running = true;
        }
    }

    private class downloadTitle extends LifecycleTask<Void,Void,Integer> {
        protected void onPreExecute() {
            super.onPreExecute();
            cookies = new HashMap<>();
            running = true;
        }
        protected Integer doInBackground(Void... params) {
            File home = null;
            DocumentFile homed = null;
            try{
                if(useScopedStorageHome(homeDir)){
                    homed = DocumentFile.fromTreeUri(serviceContext, Uri.parse(homeDir));
                    if(homed == null || !homed.canWrite()){
                        this.cancel(true);
                        return 1;
                    }
                } else {
                    home = new File(homeDir);
                    if(!home.exists() && !home.mkdirs()) {
                        this.cancel(true);
                        return 1;
                    }
                }
            }catch (Exception e){
                //home folder not set
                this.cancel(true);
                return 4;
            }
            try {
                while (titles.size() > 0) {
                    //reset progress
                    progress = 0;

                    //mget item from queue
                    DownloadTitle title = titles.get(0);
                    JSONArray selectedEps = selected.get(0);

                    notiTitle = title.getName();
                    updateNotification("준비중");

                    //if (title.getEps() == null) title.fetchEps(getHttpClient());
                    List<Manga> mangas = title.getEps();
                    if(mangas == null || mangas.size() == 0 || selectedEps.length() == 0) {
                        failures++;
                        titles.remove(0);
                        selected.remove(0);
                        continue;
                    }
                    //todo: minimize eps object(remove 'mode')

                    float stepSize = maxProgress / selectedEps.length();
                    for (int queueIndex = selectedEps.length()-1; queueIndex >= 0; queueIndex--) {
                        if (isCancelled()) return 0;

                        if (homed != null) {
                            //scoped storage
                            DocumentFile titleDir = homed.findFile(filterFolder(title.getName()));
                            if(titleDir == null) titleDir = homed.createDirectory(filterFolder(title.getName()));
                            if(titleDir == null) {
                                failures++;
                                continue;
                            }

                            //if first manga, save title data
                            if (queueIndex == selectedEps.length() - 1) {
                                try {
                                    //save thumbnail
                                    DocumentFile thumb = downloadFile(title.getThumb(), titleDir, "thumb", null);
                                    if(thumb != null)
                                        title.setThumb(thumb.getName());

                                    //save the whole title as gson
                                    DocumentFile dataf = titleDir.findFile("title.gson");
                                    if(dataf != null)
                                        dataf.delete();
                                    DocumentFile summary = titleDir.createFile("application/json", "title.gson");
                                    if(summary == null)
                                        throw new IOException("Failed to create title.gson");
                                    Uri data = summary.getUri();

                                    try (OutputStream stream = serviceContext.getContentResolver().openOutputStream(data)) {
                                        if(stream == null)
                                            throw new IOException("Failed to open title.gson");
                                        stream.write(new Gson().toJson(title).getBytes());
                                        stream.flush();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                            //mget index from JSONArray
                            int listIndex = 0;
                            try {
                                listIndex = selectedEps.getInt(queueIndex);
                            } catch (Exception e) {
                                this.cancel(true);
                                return 2;
                            }
                            if(listIndex < 0 || listIndex >= mangas.size()) {
                                failures++;
                                continue;
                            }

                            Manga target = mangas.get(listIndex);
                            int currentEpisode = selectedEps.length() - queueIndex;
                            updateNotification(target, currentEpisode, selectedEps.length(), 0, 0);
                            List<String> urls = fetchDownloadImages(target);
                            if(urls == null || urls.size() == 0) {
                                failures++;
                                continue;
                            }
                            Decoder d = new Decoder(target.getSeed(), target.getId());

                            //set stepsize
                            float imgStepSize = stepSize / urls.size();

                            //create dir for manga
                            int realIndex = mangas.size() - mangas.indexOf(target);
                            String name = filterFolder(new DecimalFormat("0000").format(realIndex) + "." + target.getName()) + "." + target.getId();
                            DocumentFile dir = titleDir.findFile(name);
                            if(dir != null)
                                dir.delete();
                            dir = titleDir.createDirectory(name);
                            if(dir == null) {
                                failures++;
                                continue;
                            }

                            //create download flag
                            DocumentFile downloadFlag = dir.findFile("downloading");
                            if(downloadFlag != null)
                                downloadFlag.delete();
                            downloadFlag = dir.createFile("application", "downloading");

                            int downloadedImages = downloadImages(urls, dir, d, imgStepSize, target, currentEpisode, selectedEps.length());

                            if(downloadFlag != null)
                                downloadFlag.delete();
                            if (downloadedImages < urls.size()) {
                                dir.delete();
                                failures++;
                            }

                        }else {

                            //create dir for title
                            File titleDir = new File(homeDir, filterFolder(title.getName()));
                            if (!titleDir.exists()) titleDir.mkdirs();

                            //if first manga, save title data
                            if (queueIndex == selectedEps.length() - 1) {
                                try {
                                    //save thumbnail
                                    File thumb = downloadFile(title.getThumb(), new File(titleDir, "thumb"));
                                    if(thumb != null)
                                        title.setThumb(thumb.getName());

                                    //if old title.data exist, remove file
                                    File old = new File(titleDir, "title.data");
                                    if (old.exists()) old.delete();

                                    //save the whole title as gson
                                    File summary = new File(titleDir, "title.gson");
                                    summary.createNewFile();

                                    try (FileOutputStream stream = new FileOutputStream(summary)) {
                                        stream.write(new Gson().toJson(title).getBytes());
                                        stream.flush();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                            //mget index from JSONArray
                            int listIndex = 0;
                            try {
                                listIndex = selectedEps.getInt(queueIndex);
                            } catch (Exception e) {
                                this.cancel(true);
                                return 2;
                            }
                            if(listIndex < 0 || listIndex >= mangas.size()) {
                                failures++;
                                continue;
                            }

                            Manga target = mangas.get(listIndex);
                            int currentEpisode = selectedEps.length() - queueIndex;
                            updateNotification(target, currentEpisode, selectedEps.length(), 0, 0);
                            List<String> urls = fetchDownloadImages(target);
                            if(urls == null || urls.size() == 0) {
                                failures++;
                                continue;
                            }
                            Decoder d = new Decoder(target.getSeed(), target.getId());

                            //set stepsize
                            float imgStepSize = stepSize / urls.size();

                            //create dir for manga
                            int realIndex = mangas.size() - mangas.indexOf(target);
                            File dir = new File(titleDir, filterFolder(new DecimalFormat("0000").format(realIndex) + "." + target.getName()) + "." + target.getId());
                            if(dir.exists())
                                deleteRecursively(dir);
                            if(!dir.mkdirs()) {
                                failures++;
                                continue;
                            }

                            //create download flag
                            File downloadFlag = new File(dir, "downloading");
                            downloadFlag.createNewFile();
                            int downloadedImages = downloadImages(urls, dir, d, imgStepSize, target, currentEpisode, selectedEps.length());

                            downloadFlag.delete();
                            if (downloadedImages < urls.size()) {
                                deleteRecursively(dir);
                                failures++;
                            }
                        }
                    }
                    titles.remove(0);
                    selected.remove(0);
                }
            }catch (Exception e){
                //unexpected exception
                e.printStackTrace();
                this.cancel(true);
                return 3;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Integer res) {
            super.onPostExecute(res);
            endNotification();
            running = false;
            stopSelf();
            sendBroadcast(new Intent().setAction(ACTION_STOP));
        }

        @Override
        protected void onCancelled(Integer mode) {
            super.onCancelled();
            running = false;
            String why = "";
            switch(mode){
                case 0:
                    why = "유저 취소";
                    break;
                case 1:
                    why = "쓰기 실패";
                    break;
                case 2:
                    why = "만화 정보 파싱 실패";
                    break;
                case 3:
                    why = "예상치 못한 오류";
                    break;
                case 4:
                    why = "다운로드 위치를 설정해 주세요";
                    break;
            }
            notificationManager.cancel(nid);
            stopNotification(why);
            stopSelf();
            sendBroadcast(new Intent().setAction(BROADCAST_STOP));
        }
    }

    boolean downloadImage(String urlStr, File outputFile, Decoder d) {
        try {
            URL url = resolveUrl(urlStr);
            if (url == null) return false;
            URLConnection connection = openDownloadConnection(url);
            Bitmap bitmap;
            try (InputStream in = connection.getInputStream()) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            if(bitmap == null) return false;
            bitmap = d.decode(bitmap);
            try (OutputStream outputStream = new FileOutputStream(outputFile.getAbsolutePath() + ".jpg")) {
                if(!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)) return false;
                outputStream.flush();
            } finally {
                bitmap.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
            //retry if old image server
            return false;
        }
        return true;
    }

    boolean downloadImage(String urlStr, DocumentFile parent, String name, Decoder d) {
        try {
            URL url = resolveUrl(urlStr);
            if (url == null) return false;
            URLConnection connection = openDownloadConnection(url);
            Bitmap bitmap;
            try (InputStream in = connection.getInputStream()) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            if(bitmap == null) return false;
            bitmap = d.decode(bitmap);
            //save image
            String fname = name +".jpg";
            DocumentFile outputFile = parent.findFile(fname);
            if(outputFile != null) outputFile.delete();
            outputFile = parent.createFile("image/jpeg",fname);
            if(outputFile == null) return false;

            try (OutputStream outputStream = serviceContext.getContentResolver().openOutputStream(outputFile.getUri())) {
                if(outputStream == null) return false;
                if(!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)) return false;
                outputStream.flush();
            } finally {
                bitmap.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
            //retry if old image server
            return false;
        }
        return true;
    }

    private int downloadImages(List<String> urls, File dir, Decoder decoder, float imgStepSize,
                               Manga target, int currentEpisode, int totalEpisodes) {
        return downloadImagesInParallel(urls, i -> downloadImage(urls.get(i), new File(dir, new DecimalFormat("0000").format(i)), decoder),
                imgStepSize, target, currentEpisode, totalEpisodes);
    }

    private int downloadImages(List<String> urls, DocumentFile dir, Decoder decoder, float imgStepSize,
                               Manga target, int currentEpisode, int totalEpisodes) {
        return downloadImagesInParallel(urls, i -> downloadImage(urls.get(i), dir, new DecimalFormat("0000").format(i), decoder),
                imgStepSize, target, currentEpisode, totalEpisodes);
    }

    private int downloadImagesInParallel(List<String> urls, ImageDownloadTask task, float imgStepSize,
                                         Manga target, int currentEpisode, int totalEpisodes) {
        if(urls == null || urls.size() == 0)
            return 0;
        int workers = Math.max(1, Math.min(PARALLEL_IMAGE_DOWNLOADS, urls.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CompletionService<Boolean> completion = new ExecutorCompletionService<>(executor);
        int submitted = 0;
        try {
            for(int i = 0; i < urls.size(); i++) {
                final int index = i;
                completion.submit((Callable<Boolean>) () -> {
                    int tries = 0;
                    while(tries < 5) {
                        if(Thread.currentThread().isInterrupted())
                            return false;
                        if(task.download(index))
                            return true;
                        tries++;
                    }
                    return false;
                });
                submitted++;
            }
            int downloadedImages = 0;
            for(int completed = 0; completed < submitted; completed++) {
                if(Thread.currentThread().isInterrupted())
                    break;
                Future<Boolean> future = completion.take();
                boolean imageSaved = false;
                try {
                    imageSaved = future.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if(imageSaved)
                    downloadedImages++;
                progress += imgStepSize;
                updateNotification(target, currentEpisode, totalEpisodes, completed + 1, urls.size());
            }
            return downloadedImages;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            executor.shutdownNow();
        }
    }

    private interface ImageDownloadTask {
        boolean download(int index);
    }

    File downloadFile(String urlStr, File outputFile) {
        return downloadFile(urlStr, outputFile, null);
    }
    File downloadFile(String urlStr, File outputFile, ProgressInterface publisher){
        //returns file name with extension
        String name = "";
        int filesize;
        try {
            URL url = resolveUrl(urlStr);
            if(url == null) return outputFile;
            String fileType = url.toString().substring(url.toString().lastIndexOf('.') + 1);
            URLConnection connection = openDownloadConnection(url);
            filesize = connection.getContentLength();

            //load file
            outputFile = new File(outputFile.getAbsolutePath()+'.'+fileType);
            name = outputFile.getName();
            try (InputStream in = connection.getInputStream();
                 OutputStream outputStream = new FileOutputStream(outputFile)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                int cursize = 0;
                while ((len = in.read(buf)) > 0){
                    outputStream.write(buf, 0, len);
                    cursize += len;
                    publishDownloadProgress(publisher, cursize, filesize);
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            //
            e.printStackTrace();
        }
        return outputFile;
    }

    DocumentFile downloadFile(String urlStr, DocumentFile parent, String name, ProgressInterface publisher){
        //returns file name with extension
        DocumentFile outputFile = null;
        int filesize;
        try {
            URL url = resolveUrl(urlStr);
            if(url == null) return null;
            String fileType = url.toString().substring(url.toString().lastIndexOf('.') + 1);
            URLConnection connection = openDownloadConnection(url);
            filesize = connection.getContentLength();

            //load file
            //create file
            DocumentFile pfile = parent.findFile(name+'.'+fileType);
            if(pfile != null)
                pfile.delete();
            outputFile = parent.createFile("image", name+"."+fileType);
            if(outputFile == null) return null;
            //open stream
            try (InputStream in = connection.getInputStream();
                 OutputStream outputStream = serviceContext.getContentResolver().openOutputStream(outputFile.getUri())) {
                if(outputStream == null) return null;
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                int cursize = 0;
                while ((len = in.read(buf)) > 0){
                    outputStream.write(buf, 0, len);
                    cursize += len;
                    publishDownloadProgress(publisher, cursize, filesize);
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            //
            e.printStackTrace();
        }
        return outputFile;
    }

    private URL resolveUrl(String urlStr) throws IOException {
        if(urlStr == null)
            return null;
        urlStr = normalizeDownloadUrl(urlStr);
        if(urlStr.length() == 0)
            return null;
        return new URL(urlStr);
    }

    private URLConnection openDownloadConnection(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", getHttpClient().agent);
        connection.setRequestProperty("Referer", p.getUrl());
        String cookieHeader = buildDownloadCookieHeader(url);
        if(cookieHeader.length() > 0)
            connection.setRequestProperty("Cookie", cookieHeader);
        return connection;
    }

    private String buildDownloadCookieHeader(URL url) {
        StringBuilder builder = new StringBuilder();
        appendCookieString(builder, CookieManager.getInstance().getCookie(url.toString()));
        appendCookieString(builder, CookieManager.getInstance().getCookie(p.getUrl()));
        String session = getHttpClient().getCookie("PHPSESSID");
        if(session != null && session.length() > 0 && builder.indexOf("PHPSESSID=") < 0)
            appendCookieString(builder, "PHPSESSID=" + session);
        if(cookies != null)
            for(String key : cookies.keySet()) {
                String value = cookies.get(key);
                if(key != null && value != null && builder.indexOf(key + "=") < 0)
                    appendCookieString(builder, key + "=" + value);
            }
        return builder.toString();
    }

    private void appendCookieString(StringBuilder builder, String cookie) {
        if(cookie == null || cookie.trim().length() == 0)
            return;
        if(builder.length() > 0 && builder.charAt(builder.length() - 1) != ' ')
            builder.append("; ");
        builder.append(cookie.trim());
    }

    private String normalizeDownloadUrl(String urlStr) {
        String url = urlStr.trim();
        if(url.startsWith("//"))
            return "https:" + url;
        if(url.startsWith("http://") || url.startsWith("https://"))
            return url;
        String root = p.getUrl();
        while(root.endsWith("/"))
            root = root.substring(0, root.length() - 1);
        if(root.endsWith("/cm"))
            root = root.substring(0, root.length() - 3);
        if(url.startsWith("/"))
            return root + url;
        return root + "/" + url;
    }

    private void deleteRecursively(File file) {
        if(file == null || !file.exists())
            return;
        if(file.isDirectory()) {
            File[] children = file.listFiles();
            if(children != null)
                for(File child : children)
                    deleteRecursively(child);
        }
        file.delete();
    }

    private List<String> fetchDownloadImages(Manga target) {
        List<String> urls = null;
        for(int tries = 0; tries < 3; tries++) {
            target.fetch(getHttpClient(), cookies);
            urls = target.getImgs(getApplicationContext());
            if(urls != null && urls.size() > 0)
                return urls;
        }
        return urls;
    }

    private void publishDownloadProgress(ProgressInterface publisher, int currentSize, int fileSize) {
        if(publisher != null && fileSize > 0)
            publisher.publish((int)(((double)currentSize/(double)fileSize)*100d));
    }

    public int getIndex(List<Manga> eps, int id){
        for(int i=0; i<eps.size(); i++){
            if(eps.get(i).getId()==id){
                return eps.size()-i;
            }
        }
        return 0;
    }
    private void startNotification() {
        notification = new NotificationCompat.Builder(this, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle("다운로드를 시작합니다")
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        startForeground(nid, notification.build());
    }
    private void updateNotification(String text) {
        notification = new NotificationCompat.Builder(this, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle(notiTitle)
                .setSubText("대기열: " + titles.size())
                .setContentText(text)
                .addAction(R.drawable.blank, "중지", stopIntent)
                .setProgress(maxProgress, (int) progress, !(progress > 0))
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        notificationManager.notify(nid, notification.build());
    }

    private void updateNotification(Manga target, int currentEpisode, int totalEpisodes, int currentImage, int totalImages) {
        int percent = maxProgress > 0 ? Math.min(100, Math.round((progress / maxProgress) * 100)) : 0;
        String episodeProgress = currentEpisode + "/" + totalEpisodes;
        String imageProgress = totalImages > 0 ? currentImage + "/" + totalImages : "이미지 준비중";
        String episodeName = target != null && target.getName() != null ? target.getName() : "";
        String text = "회차 " + episodeProgress + " · " + imageProgress + " · " + percent + "%";
        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle()
                .bigText((episodeName.length() > 0 ? episodeName + "\n" : "") + text);
        notification = new NotificationCompat.Builder(this, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle(notiTitle)
                .setSubText("저장중")
                .setContentText(text)
                .setContentInfo(percent + "%")
                .setStyle(style)
                .addAction(R.drawable.blank, "중지", stopIntent)
                .setProgress(maxProgress, (int) progress, totalImages <= 0)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        notificationManager.notify(nid, notification.build());
    }

    private void endNotification(){
        notification = new NotificationCompat.Builder(this, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle("다운로드 완료")
                .setOngoing(false);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        notificationManager.notify(nid, notification.build());
}

    private void finishNotification(){
        notification = new NotificationCompat.Builder(this, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle("모든 다운로드가 완료되었습니다.")
                .setOngoing(false);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        if(failures>0) {
            notification.setContentText("누락: " + failures);
            failures = 0;
        }
        notificationManager.notify(nid+1, notification.build());
    }
    private void stopNotification(String why){
        notification = new NotificationCompat.Builder(this, channeld)
                .setContentIntent(pendingIntent)
                .setContentText(why)
                .setContentTitle("다운로드가 취소되었습니다.")
                .setOngoing(false);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        notificationManager.notify(nid + 2, notification.build());
    }

    private interface ProgressInterface{
        void publish(int progress);
    }


}
