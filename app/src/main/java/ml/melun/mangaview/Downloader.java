package ml.melun.mangaview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.CookieManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Future;

import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.mangaview.DownloadTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.repository.DownloadRepository;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.filterFolder;
import static ml.melun.mangaview.Utils.useScopedStorageHome;

public class Downloader extends Worker {
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
    public static final String KEY_QUEUE_ID = "queue_id";
    public static final String QUEUE_DIR = "download_queue";
    public static final String WORK_NAME = "offline-downloads";
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

    public static void cancelAll(Context context) {
        running = false;
        DownloadRepository.cancelAll(context);
    }

    public Downloader(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        serviceContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Result doWork() {
        setupWorker();
        String queueId = getInputData().getString(KEY_QUEUE_ID);
        if(queueId == null || queueId.length() == 0)
            return Result.failure();
        File file = new File(serviceContext.getFilesDir(), QUEUE_DIR + "/" + queueId + ".json");
        if(!file.exists())
            return Result.failure();
        try {
            String payload = readPayload(file);
            int split = payload.indexOf('\n');
            if(split <= 0)
                return Result.failure();
            DownloadTitle target = new Gson().fromJson(payload.substring(0, split), new TypeToken<DownloadTitle>() {}.getType());
            JSONArray selection = new JSONArray(payload.substring(split + 1));
            queueTitle(target, selection);
            file.delete();

            if(dt == null)
                dt = new downloadTitle();
            dt.prepare();
            Integer result = dt.run();
            if(isStopped() || result != null) {
                dt.cancelWith(result == null ? 0 : result);
                return result != null && result == 3 ? Result.retry() : Result.failure();
            }
            dt.complete();
            finishNotification();
            return Result.success();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            running = false;
            return Result.retry();
        }
    }

    private void setupWorker() {
        if(titles==null) titles = new ArrayList<>();
        if(selected==null) selected = new ArrayList<>();
        homeDir = serviceContext.getSharedPreferences("mangaView",Context.MODE_PRIVATE).getString("homeDir", "");
        baseUrl = serviceContext.getSharedPreferences("mangaView",Context.MODE_PRIVATE).getString("url", "");
        if(dt==null) dt = new downloadTitle();
        //android O bullshit
        notificationManager = (NotificationManager) serviceContext.getSystemService(Context.NOTIFICATION_SERVICE);
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
        Intent notificationIntent = new Intent(serviceContext, MainActivity.class);
        pendingIntent = PendingIntent.getActivity(serviceContext, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        stopIntent = WorkManager.getInstance(serviceContext).createCancelPendingIntent(getId());
        startNotification();
    }

    private String readPayload(File file) throws IOException {
        try(InputStream input = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while((read = input.read(buffer)) > 0)
                output.write(buffer, 0, read);
            return decodePayload(output.toByteArray());
        }
    }

    public static byte[] encodePayloadForTest(String payload) {
        return encodePayload(payload);
    }

    public static String decodePayloadForTest(byte[] payload) {
        return decodePayload(payload);
    }

    public static byte[] encodePayload(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodePayload(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public void queueTitle(DownloadTitle title, JSONArray selection){
        titles.add(title);
        selected.add(selection);
        updateNotification("");
        running = true;
    }

    private static float progressStep(int maxProgress, int itemCount) {
        if(maxProgress <= 0 || itemCount <= 0)
            return 0f;
        return ((float) maxProgress) / itemCount;
    }

    private static int imageDownloadParallelism(int itemCount) {
        if(itemCount <= 0)
            return 0;
        return Math.max(1, Math.min(PARALLEL_IMAGE_DOWNLOADS, itemCount));
    }

    static int imageDownloadParallelismForTest(int itemCount) {
        return imageDownloadParallelism(itemCount);
    }

    static float progressStepForTest(int maxProgress, int itemCount) {
        return progressStep(maxProgress, itemCount);
    }

    private static String fileExtension(String url) {
        if(url == null || url.length() == 0)
            return "jpg";
        int end = url.length();
        int query = url.indexOf('?');
        if(query >= 0)
            end = Math.min(end, query);
        int fragment = url.indexOf('#');
        if(fragment >= 0)
            end = Math.min(end, fragment);
        String path = url.substring(0, end);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if(dot <= slash || dot + 1 >= path.length())
            return "jpg";
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "jpg";
    }

    static String fileExtensionForTest(String url) {
        return fileExtension(url);
    }

    private class downloadTitle {
        void prepare() {
            cookies = new HashMap<>();
            running = true;
        }

        Integer run() {
            File home = null;
            DocumentFile homed = null;
            try{
                if(useScopedStorageHome(homeDir)){
                    homed = DocumentFile.fromTreeUri(serviceContext, Uri.parse(homeDir));
                    if(homed == null || !homed.canWrite()){
                        return 1;
                    }
                } else {
                    home = new File(homeDir);
                    if(!home.exists() && !home.mkdirs()) {
                        return 1;
                    }
                }
            }catch (Exception e){
                //home folder not set
                return 4;
            }
            try {
                while (titles.size() > 0) {
                    //reset progress
                    progress = 0;

                    //mget item from queue
                    DownloadTitle title = Utils.safeGet(titles, 0);
                    JSONArray selectedEps = Utils.safeGet(selected, 0);
                    if(title == null || selectedEps == null) {
                        failures++;
                        if(titles.size() > 0)
                            titles.remove(0);
                        if(selected.size() > 0)
                            selected.remove(0);
                        continue;
                    }

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

                    float stepSize = progressStep(maxProgress, selectedEps.length());
                    for (int queueIndex = selectedEps.length()-1; queueIndex >= 0; queueIndex--) {
                        if (Downloader.this.isStopped()) return 0;

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
                                        stream.write(encodePayload(new Gson().toJson(title)));
                                        stream.flush();
                                    }
                                } catch (Exception e) {
                                    ml.melun.mangaview.report.CrashReporter.record(e);
                                }
                            }

                            //mget index from JSONArray
                            int listIndex = 0;
                            try {
                                listIndex = selectedEps.getInt(queueIndex);
                            } catch (Exception e) {
                                ml.melun.mangaview.report.CrashReporter.record(e);
                                failures++;
                                continue;
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
                                        stream.write(encodePayload(new Gson().toJson(title)));
                                        stream.flush();
                                    }
                                } catch (Exception e) {
                                    ml.melun.mangaview.report.CrashReporter.record(e);
                                }
                            }

                            //mget index from JSONArray
                            int listIndex = 0;
                            try {
                                listIndex = selectedEps.getInt(queueIndex);
                            } catch (Exception e) {
                                ml.melun.mangaview.report.CrashReporter.record(e);
                                failures++;
                                continue;
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
                ml.melun.mangaview.report.CrashReporter.record(e);
                return 3;
            }
            return null;
        }

        void complete() {
            endNotification();
            running = false;
            serviceContext.sendBroadcast(new Intent().setAction(ACTION_STOP));
        }

        void cancelWith(Integer mode) {
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
            serviceContext.sendBroadcast(new Intent().setAction(BROADCAST_STOP));
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
            ml.melun.mangaview.report.CrashReporter.record(e);
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
            ml.melun.mangaview.report.CrashReporter.record(e);
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
        int workers = imageDownloadParallelism(urls.size());
        CompletionService<Boolean> completion = AppDispatchers.ioCompletionService();
        ArrayList<Future> running = new ArrayList<>();
        int submitted = 0;
        int nextIndex = 0;
        try {
            while(nextIndex < urls.size() && submitted < workers) {
                running.add(submitImageDownload(completion, task, nextIndex));
                submitted++;
                nextIndex++;
            }
            int downloadedImages = 0;
            for(int completed = 0; completed < urls.size(); completed++) {
                if(Thread.currentThread().isInterrupted() || isStopped())
                    break;
                Future future = completion.take();
                boolean imageSaved = false;
                try {
                    imageSaved = Boolean.TRUE.equals(future.get());
                } catch (Exception e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                }
                if(imageSaved)
                    downloadedImages++;
                progress += imgStepSize;
                updateNotification(target, currentEpisode, totalEpisodes, completed + 1, urls.size());
                if(nextIndex < urls.size()) {
                    running.add(submitImageDownload(completion, task, nextIndex));
                    submitted++;
                    nextIndex++;
                }
            }
            return downloadedImages;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            for(Future future : running)
                if(future != null && !future.isDone())
                    future.cancel(true);
        }
    }

    private Future<Boolean> submitImageDownload(CompletionService<Boolean> completion, ImageDownloadTask task, int index) {
        return completion.submit(AppDispatchers.safeCallable(() -> {
            int tries = 0;
            while(tries < 5) {
                if(Thread.currentThread().isInterrupted())
                    return false;
                if(task.download(index))
                    return true;
                tries++;
            }
            return false;
        }));
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
            String fileType = fileExtension(url.toString());
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
            ml.melun.mangaview.report.CrashReporter.record(e);
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
            String fileType = fileExtension(url.toString());
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
            ml.melun.mangaview.report.CrashReporter.record(e);
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
            urls = target.getImgs(serviceContext);
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
        notification = new NotificationCompat.Builder(serviceContext, channeld)
                .setContentIntent(pendingIntent)
                .setContentTitle("다운로드를 시작합니다")
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setSmallIcon(R.drawable.ic_logo);
        else
            notification.setSmallIcon(R.drawable.notification_logo);
        setForegroundAsync(new ForegroundInfo(nid, notification.build()));
    }
    private void updateNotification(String text) {
        notification = new NotificationCompat.Builder(serviceContext, channeld)
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
        notification = new NotificationCompat.Builder(serviceContext, channeld)
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
        notification = new NotificationCompat.Builder(serviceContext, channeld)
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
        notification = new NotificationCompat.Builder(serviceContext, channeld)
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
        notification = new NotificationCompat.Builder(serviceContext, channeld)
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
