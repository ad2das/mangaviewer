package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import ml.melun.mangaview.task.LifecycleTask;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;

import java.util.ArrayList;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.adapter.UpdatedAdapter;
import ml.melun.mangaview.mangaview.Bookmark;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedList;
import ml.melun.mangaview.mangaview.UpdatedManga;

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.viewerIntent;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;

public class TagSearchActivity extends AppCompatActivity {
    RecyclerView searchResult;
    int mode;
    String query;
    TitleAdapter adapter;
    UpdatedAdapter uadapter;
    Context context;
    Search search;
    UpdatedList updated;
    TextView noresult;
    SwipyRefreshLayout swipe;
    Bookmark bookmark;
    int baseMode;
    LifecycleTask<?, ?, ?> loadTask;
    boolean destroyed = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if(p.getDarkTheme()) setTheme(R.style.AppThemeDark);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tag_search);
        context = this;
        searchResult = this.findViewById(R.id.tagSearchResult);
        noresult = this.findViewById(R.id.tagSearchNoResult);
        LinearLayoutManager lm = new NpaLinearLayoutManager(context);
        searchResult.setLayoutManager(lm);
        searchResult.setHasFixedSize(true);
        searchResult.setItemViewCacheSize(12);
        Intent i = getIntent();
        query = i.getStringExtra("query");
        mode = i.getIntExtra("mode",0);
        String title = i.getStringExtra("title");
        swipe = this.findViewById(R.id.tagSearchSwipe);
        baseMode = i.getIntExtra("baseMode", base_comic);

        ActionBar ab = getSupportActionBar();
        switch(mode){
            case 0:
                break;
            case 1:
                ab.setTitle("작가: "+query);
                break;
            case 2:
                ab.setTitle("태그: "+query);
                break;
            case 3:
            case 4:
                ab.setTitle("검색 결과");
                break;
            case 5:
                ab.setTitle("최근 추가됨");
                break;
            case 6:
                ab.setTitle("검색결과");
                break;
            case 7:
                ab.setTitle("북마크");
                break;
        }

        if(mode == 8)
            ab.setTitle(title == null ? "분류" : title);
        ab.setDisplayHomeAsUpEnabled(true);
        swipe.setRefreshing(true);

        if(mode == 5) {
            uadapter = new UpdatedAdapter(context);
            updated = new UpdatedList(p.getBaseMode());
            startLoad(new getUpdated());
            swipe.setOnRefreshListener(direction -> {
                 if (!updated.isLast()) {
                    startLoad(new getUpdated());
                } else swipe.setRefreshing(false);
            });

        }else if(mode == 7){
            adapter = new TitleAdapter(context);
            bookmark = new Bookmark();
            startLoad(new getBookmarks());
            swipe.setOnRefreshListener(direction -> {
                if (bookmark.isLast()) {
                    startLoad(new getBookmarks());
                } else swipe.setRefreshing(false);
            });

        }else {
            adapter = new TitleAdapter(context);
            search = new Search(query,mode,baseMode);
            startLoad(new searchManga());
            swipe.setOnRefreshListener(direction -> {
                if (!search.isLast()) {
                    startLoad(new searchManga());
                } else swipe.setRefreshing(false);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void startLoad(LifecycleTask<?, ?, ?> task) {
        if(loadTask != null) {
            swipe.setRefreshing(true);
            return;
        }
        loadTask = task;
        swipe.setRefreshing(true);
        task.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
    }

    private boolean prepareLoadResult(LifecycleTask<?, ?, ?> task) {
        if(loadTask != task || destroyed || isFinishing())
            return false;
        loadTask = null;
        return true;
    }

    private void clearLoad(LifecycleTask<?, ?, ?> task) {
        if(loadTask == task)
            loadTask = null;
        if(swipe != null)
            swipe.setRefreshing(false);
    }

    public boolean onOptionsItemSelected(MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private class getBookmarks extends LifecycleTask<Void, Void, Integer>{
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);
            if(!prepareLoadResult(this))
                return;
            if(integer != 0){
                showCaptchaPopup(context, p);
            }
            if(adapter.getItemCount()==0) {
                adapter.addData(bookmark.getResult());
                searchResult.setAdapter(adapter);
                adapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onResumeClick(int position, int id) {
                        Intent viewer = viewerIntent(context, new Manga(id,"","",adapter.getItem(position).getBaseMode()));
                        viewer.putExtra("online",true);
                        startActivity(viewer);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = adapter.getItem(position);
                        Intent episodeView = episodeIntent(context, selected);
                        episodeView.putExtra("online", true);
                        startActivity(episodeView);
                    }

                    @Override
                    public void onLongClick(View view, int position) {
                        popup(view, position, adapter.getItem(position), 0);
                    }
                });
            }else{
                adapter.addData(bookmark.getResult());
            }

            if(adapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            swipe.setRefreshing(false);
        }

        @Override
        protected void onCancelled(Integer integer) {
            super.onCancelled(integer);
            clearLoad(this);
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            return bookmark.fetch(httpClient);
        }
    }


    private class searchManga extends LifecycleTask<String,String,Integer> {
        protected void onPreExecute(){
            super.onPreExecute();
        }
        protected Integer doInBackground(String... params){
            return search.fetch(httpClient);
        }
        @Override
        protected void onPostExecute(Integer res){
            super.onPostExecute(res);
            if(!prepareLoadResult(this))
                return;
            if(res != 0){
                showCaptchaPopup(context, p);
            }
            if(adapter.getItemCount()==0) {
                adapter.addData(search.getResult());
                searchResult.setAdapter(adapter);
                adapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onResumeClick(int position, int id) {
                        Intent viewer = viewerIntent(context, new Manga(id,"","", search.getBaseMode()));
                        viewer.putExtra("online",true);
                        startActivity(viewer);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = adapter.getItem(position);
                        Intent episodeView = episodeIntent(context, selected);
                        episodeView.putExtra("online", true);
                        startActivity(episodeView);
                    }

                    @Override
                    public void onLongClick(View view, int position) {
                        popup(view, position, adapter.getItem(position), 0);
                    }
                });
            }else{
                adapter.addData(search.getResult());
            }

            if(adapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            swipe.setRefreshing(false);
        }

        @Override
        protected void onCancelled(Integer res) {
            super.onCancelled(res);
            clearLoad(this);
        }
    }

    private class getUpdated extends LifecycleTask<String,String,String> {
        protected void onPreExecute(){
            super.onPreExecute();
        }
        protected String doInBackground(String... params){
            updated.fetch(httpClient);
            return null;
        }
        @Override
        protected void onPostExecute(String res){
            super.onPostExecute(res);
            if(!prepareLoadResult(this))
                return;
            ArrayList<UpdatedManga> result = updated.getResult();
            if(result == null)
                result = new ArrayList<>();
            if(result.size() == 0 && uadapter.getItemCount() == 0){
                //error
                showCaptchaPopup(context, p);
            }
            if(uadapter.getItemCount()==0) {
                uadapter.addData(result);
                searchResult.setAdapter(uadapter);
                uadapter.setOnClickListener(new UpdatedAdapter.onclickListener() {
                    @Override
                    public void onEpsClick(Title t) {
                        Intent eps = episodeIntent(context, t);
                        eps.putExtra("online", true);
                        startActivity(eps);
                    }

                    @Override
                    public void onClick(Manga m) {
                        //open viewer
                        Intent viewer = viewerIntent(context, m);
                        viewer.putExtra("online", true);
                        startActivityForResult(viewer,0);
                    }
                });
            }else{
                uadapter.addData(result);
            }

            if(uadapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            swipe.setRefreshing(false);
        }

        @Override
        protected void onCancelled(String res) {
            super.onCancelled(res);
            clearLoad(this);
        }
    }
    void popup(View view, final int position, final Title title, final int m){
        PopupMenu popup = new PopupMenu(TagSearchActivity.this, view);
        //Inflating the Popup using xml file
        popup.getMenuInflater()
                .inflate(R.menu.title_options, popup.getMenu());
        popup.getMenu().removeItem(R.id.del);
        popup.getMenu().findItem(R.id.favAdd).setVisible(true);
        popup.getMenu().findItem(R.id.favDel).setVisible(true);
        if(p.findFavorite(title)>-1) popup.getMenu().removeItem(R.id.favAdd);
        else popup.getMenu().removeItem(R.id.favDel);


        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()){
                case R.id.del:
                    break;
                case R.id.favAdd:
                case R.id.favDel:
                    //toggle favorite
                    p.toggleFavorite(title,0);
                    break;
            }
            return true;
        });
        popup.show(); //showing popup menu
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA){
            //captcha
            finish();
            startActivity(getIntent());
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if(loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }
        super.onDestroy();
    }
}
