package ml.melun.mangaview;

import android.content.Context;
import ml.melun.mangaview.task.AppTask;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

import okhttp3.Response;

import ml.melun.mangaview.mangaview.WfwfDomainResolver;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;

public class UrlUpdater extends AppTask<Void, Void, Boolean> {
    String result;
    String fetchUrl;
    boolean silent = false;
    Context c;
    UrlUpdaterCallback callback;
    public UrlUpdater(Context c){
        this.c = c;
        this.fetchUrl = p.getDefUrl();
    }
    public UrlUpdater(Context c, boolean silent, UrlUpdaterCallback callback, String defUrl){
        this.c = c;
        this.silent = silent;
        this.callback = callback;
        this.fetchUrl = defUrl;
    }
    protected void onPreExecute() {
        if(!silent) Toast.makeText(c, "자동 URL 설정중...", Toast.LENGTH_SHORT).show();
    }
    protected Boolean doInBackground(Void... params) {
        return fetch();
    }

    protected Boolean fetch(){
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", getHttpClient().agent);
            String root = WfwfDomainResolver.toRoot(fetchUrl);
            if(WfwfDomainResolver.isWfwfUrl(root)) {
                headers.put("Referer", root);
                result = WfwfDomainResolver.resolve(getHttpClient().client, root, headers);
                return result != null;
            }

            Response r = null;
            try {
                r = getHttpClient().get(fetchUrl, headers);
                if(r == null)
                    return false;
                if (r.code() != 302)
                    return false;
                result = r.header("Location");
                return true;
            } finally {
                if(r != null)
                    r.close();
            }

        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    protected void onPostExecute(Boolean r) {
        if(r && result !=null){
            if(WfwfDomainResolver.isWfwfUrl(WfwfDomainResolver.toRoot(result))) {
                String root = WfwfDomainResolver.toRoot(result);
                p.setWebtoonUrl(root);
                p.setDefUrl(root + "/cm");
                p.setUrl(root + "/cm");
                getHttpClient().resetCookie();
                getHttpClient().clearPageCache();
            } else {
                p.setUrl(result);
            }
            if(!silent)Toast.makeText(c, "자동 URL 설정 완료!", Toast.LENGTH_SHORT).show();
            if(callback!=null) callback.callback(true);
        }else{
            if(!silent)Toast.makeText(c, "자동 URL 설정 실패, 잠시후 다시 시도해 주세요", Toast.LENGTH_LONG).show();
            if(callback!=null) callback.callback(false);
        }


    }


    public interface UrlUpdaterCallback{
        void callback(boolean success);
    }
}
