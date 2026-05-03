package ml.melun.mangaview.fragment;

import android.content.Intent;
import ml.melun.mangaview.task.LifecycleTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewer;
import static ml.melun.mangaview.Utils.popup;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;

public class MainSearch extends Fragment {
    SwipyRefreshLayout swipe;
    FloatingActionButton advSearchBtn;
    TextView noresult;
    private EditText searchBox;
    RecyclerView searchResult;
    Spinner searchMode, baseMode;
    TitleAdapter searchAdapter;
    Search search;
    SearchManga searchTask;
    String activeSearchKey = null;
    Fragment fragment;
    LinearLayoutCompat optionsPanel;
    String prequery = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_search , container, false);

        //search content
        noresult = rootView.findViewById(R.id.noResult);
        searchBox = rootView.findViewById(R.id.searchBox);
        searchResult = rootView.findViewById(R.id.searchResult);
        searchResult.setLayoutManager(new NpaLinearLayoutManager(getContext()));
        searchResult.setHasFixedSize(true);
        searchResult.setItemViewCacheSize(12);
        searchMode = rootView.findViewById(R.id.searchMode);
        baseMode = rootView.findViewById(R.id.searchBaseMode);
        advSearchBtn = rootView.findViewById(R.id.advSearchBtn);
        swipe = rootView.findViewById(R.id.searchSwipe);
        optionsPanel = rootView.findViewById(R.id.searchOptionPanel);
        fragment = this;
        if(p.getDarkTheme()){
            searchMode.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
            baseMode.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
        }

        advSearchBtn.setOnClickListener(v -> {
            Toast.makeText(getContext(), "고급검색 기능 사용 불가", Toast.LENGTH_LONG).show();
//                Intent advSearch = new Intent(getContext(), AdvSearchActivity.class);
//                startActivity(advSearch);
        });

        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if(actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || event != null && event.getAction()==KeyEvent.ACTION_DOWN && keyCodeIsEnter(event)){
                searchSubmit();
                return true;
            }
            return false;
        });

        AdapterView.OnItemSelectedListener mlistener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                optionUpdate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                optionUpdate();
            }
        };
        baseMode.setOnItemSelectedListener(mlistener);
        searchMode.setOnItemSelectedListener(mlistener);

        baseMode.setSelection(p.getBaseMode()-1);



        swipe.setOnRefreshListener(direction -> {
            if(search==null) swipe.setRefreshing(false);
            else {
                if (!search.isLast()) {
                    if(searchTask == null) {
                        activeSearchKey = null;
                        searchTask = new SearchManga(search);
                        searchTask.executeOnExecutor(LifecycleTask.USER_ACTION_EXECUTOR);
                    }
                } else swipe.setRefreshing(false);
            }
        });
        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if(prequery != null){
            applyPendingSearch();
        }
    }

    void optionUpdate(){
        if(baseMode != null)
            p.setBaseMode(baseMode.getSelectedItemPosition()+1);
    }

    public void setSearch(String prequery){
        this.prequery = prequery;
        if(searchBox != null)
            applyPendingSearch();
    }

    private boolean keyCodeIsEnter(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
    }

    private void applyPendingSearch() {
        if(prequery == null || searchBox == null)
            return;
        searchBox.setText(prequery);
        searchBox.setSelection(searchBox.getText().length());
        prequery = null;
        searchSubmit();
    }

    void searchSubmit(){
        String query = searchBox.getText().toString().trim();
        if(query.length()>0) {
            swipe.setRefreshing(true);
            String key = searchKey(query);
            if(searchTask != null && key.equals(activeSearchKey))
                return;
            if(searchAdapter != null) searchAdapter.removeAll();
            else searchAdapter = new TitleAdapter(getContext());
            search = new Search(query,searchMode.getSelectedItemPosition(), baseMode.getSelectedItemPosition()+1);
            if(searchTask != null)
                searchTask.cancel(true);
            activeSearchKey = key;
            searchTask = new SearchManga(search);
            searchTask.executeOnExecutor(LifecycleTask.USER_ACTION_EXECUTOR);
        }
    }

    private String searchKey(String query) {
        return query + "\u001f" + searchMode.getSelectedItemPosition() + "\u001f" + (baseMode.getSelectedItemPosition() + 1);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA && searchAdapter!=null && search != null)
            searchSubmit();
    }

    @Override
    public void onDestroyView() {
        if(searchTask != null)
            searchTask.cancel(true);
        activeSearchKey = null;
        super.onDestroyView();
    }

    private class SearchManga extends LifecycleTask<Void, Void, Integer>{
        private final Search targetSearch;
        private CustomHttpClient.RequestGroup requestGroup;

        SearchManga(Search targetSearch) {
            this.targetSearch = targetSearch;
        }

        protected void onPreExecute(){
            super.onPreExecute();
        }
        protected Integer doInBackground(Void... params){
            requestGroup = new CustomHttpClient.RequestGroup();
            try {
                return httpClient.runWithRequestGroup(requestGroup, () -> targetSearch.fetch(httpClient));
            } catch (Exception e) {
                if(!isCancelled())
                    e.printStackTrace();
                return 1;
            }
        }
        @Override
        protected void onPostExecute(Integer res){
            super.onPostExecute(res);
            if(searchTask == this) {
                searchTask = null;
                activeSearchKey = null;
            }
            if(isCancelled() || targetSearch != search || getContext() == null)
                return;
            if(res == null)
                res = 1;
            if(res != 0){
                // error
                Utils.showCaptchaPopup(getContext(), 4, fragment, p);
            }

            if(searchAdapter.getItemCount()==0) {
                searchAdapter.addData(targetSearch.getResult());
                searchResult.setAdapter(searchAdapter);
                searchAdapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onLongClick(View view, int position) {
                        //none
                        Title title = searchAdapter.getItem(position);
                        if(title == null)
                            return;
                        popup(getContext(),view, position, title, 0, item -> {
                            int itemId = item.getItemId();
                            if(itemId == R.id.favAdd || itemId == R.id.favDel) {
                                //toggle favorite
                                p.toggleFavorite(title,0);
                            }
                            return false;
                        }, p);
                    }

                    @Override
                    public void onResumeClick(int position, int id) {
                        Title selected = searchAdapter.getItem(position);
                        if(getContext() == null || selected == null || id <= 0)
                            return;
                        Intent viewer = Utils.viewerIntent(getContext(), new Manga(id, "", "", selected.getBaseMode()));
                        if(viewer == null)
                            return;
                        viewer.putExtra("title", new Gson().toJson(selected));
                        viewer.putExtra("online", true);
                        startActivityForResult(viewer, -1);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = searchAdapter.getItem(position);
                        if(selected == null || getContext() == null)
                            return;
                        Intent episodeView = episodeIntent(getContext(), selected);
                        startActivity(episodeView);
                    }
                });
            }else{
                searchAdapter.addData(targetSearch.getResult());
            }

            if(searchAdapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }

            swipe.setRefreshing(false);
        }

        @Override
        protected void onCancelled(Integer res) {
            super.onCancelled(res);
            if(searchTask == this) {
                searchTask = null;
                activeSearchKey = null;
                if(swipe != null)
                    swipe.setRefreshing(false);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if(requestGroup != null)
                requestGroup.cancel();
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
