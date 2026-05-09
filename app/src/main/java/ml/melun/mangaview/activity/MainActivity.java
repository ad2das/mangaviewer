package ml.melun.mangaview.activity;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseUser;

import java.io.File;

import ml.melun.mangaview.Downloader;
import ml.melun.mangaview.FirebaseAccountManager;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Migrator;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.fragment.MainMain;

import ml.melun.mangaview.fragment.MainSearch;
import ml.melun.mangaview.interfaces.MainActivityCallback;
import ml.melun.mangaview.interfaces.UrlUpdateCallback;
import ml.melun.mangaview.model.UrlUpdateResult;
import ml.melun.mangaview.state.UiState;
import ml.melun.mangaview.viewmodel.StartupViewModel;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static ml.melun.mangaview.Downloader.BROADCAST_STOP;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Migrator.MIGRATE_FAIL;
import static ml.melun.mangaview.Migrator.MIGRATE_PROGRESS;
import static ml.melun.mangaview.Migrator.MIGRATE_RESULT;
import static ml.melun.mangaview.Migrator.MIGRATE_START;
import static ml.melun.mangaview.Migrator.MIGRATE_STOP;
import static ml.melun.mangaview.Migrator.MIGRATE_SUCCESS;
import static ml.melun.mangaview.Utils.CODE_SCOPED_STORAGE;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.Utils.showYesNoNeutralPopup;
import static ml.melun.mangaview.Utils.writePreferenceToFile;
import static ml.melun.mangaview.activity.FirstTimeActivity.RESULT_EULA_AGREE;
import static ml.melun.mangaview.activity.FolderSelectActivity.MODE_FILE_SAVE;
import static ml.melun.mangaview.activity.SettingsActivity.RESULT_NEED_RESTART;
import static ml.melun.mangaview.mangaview.CustomHttpClient.DEFAULT_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;




public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, MainActivityCallback {

    public static int PERMISSION_CODE = 132322;
    int startTab;
    int currentTab = -1;
    private Context context;
    String homeDirStr;
    Boolean dark;
    NavigationView navigationView;
    BottomNavigationView bottomNavigationView;
    Toolbar toolbar;
    View progressView;
    boolean accountInitialSyncStarted = false;
    BottomSheetDialog accountSheet;
    TextView accountSheetName;
    TextView accountSheetEmail;
    TextView accountSheetStatus;
    TextView accountSheetPrimary;
    TextView accountSheetSecondary;
    TextView accountSheetHint;
    StartupViewModel startupViewModel;
    UrlUpdateCallback pendingUrlUpdateCallback;
    private static final int FIRST_TIME_ACTIVITY = 9;


    Fragment[] fragments = new Fragment[3];

    FrameLayout content;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("currentTab", currentTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void search(String query) {
        MainSearch searchFragment = (MainSearch) fragments[1];
        changeFragment(1);
        getSupportFragmentManager().executePendingTransactions();
        searchFragment.setBaseMode(p.getBaseMode());
        searchFragment.setSearch(query);
        if(getSupportActionBar() != null)
            getSupportActionBar().setTitle("검색");
    }

    @Override
    public void navigateToTab(int index) {
        if(changeFragment(index) && toolbar != null)
            toolbar.setTitle(getTabTitle(currentTab));
    }

    private void openSearchTab() {
        changeFragment(1);
        ((MainSearch) fragments[1]).enterSearchMode();
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        fragments[0] = MainMain.newInstance();
        fragments[1] = MainSearch.newSearchTab();
        fragments[2] = MainSearch.newLibraryTab();
        dark = p.getDarkTheme();
        if (dark) setTheme(R.style.AppThemeDarkNoTitle);
        else setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
        context = this;
        Intent intent = getIntent();
        String action = intent.getAction();


        //check prefs
        if (p.getSharedPref().getLong("eula2", -1)<0) {
            p.setDefUrl(DEFAULT_COMIC_URL);
            p.setUrl(DEFAULT_COMIC_URL);
            p.setWebtoonUrl(WEBTOON_URL);
            p.setAutoUrl(false);
            p.getSharedPref().edit()
                    .putLong("eula2", System.currentTimeMillis())
                    .putBoolean("manamoa", false)
                    .apply();
        }

        if (Migrator.running) {
            ProgressDialog mpd;
            if (p.getDarkTheme()) mpd = new ProgressDialog(context, R.style.darkDialog);
            else mpd = new ProgressDialog(context);
            mpd.setMessage("기록 업데이트중..");
            mpd.setCancelable(false);
            mpd.show();

            BroadcastReceiver migratorStatusReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    switch (intent.getAction()) {
                        case MIGRATE_PROGRESS:
                            mpd.setMessage(intent.getStringExtra("msg"));
                            break;
                        case MIGRATE_START:
                            break;
                        case MIGRATE_STOP:
                            mpd.dismiss();
                            break;
                        case MIGRATE_FAIL:
                            mpd.dismiss();
                            migratorEndPopup(savedInstanceState, 1, intent.getStringExtra("msg"));
                            break;
                        case MIGRATE_SUCCESS:
                            mpd.dismiss();
                            migratorEndPopup(savedInstanceState, 0, intent.getStringExtra("msg"));
                            break;
                    }
                }
            };
            IntentFilter infil = new IntentFilter();
            infil.addAction(MIGRATE_PROGRESS);
            infil.addAction(MIGRATE_START);
            infil.addAction(MIGRATE_STOP);
            infil.addAction(MIGRATE_FAIL);
            infil.addAction(MIGRATE_SUCCESS);
            registerReceiver(migratorStatusReceiver, infil);

            Migrator.requestProgress(getApplicationContext());

        } else if (!p.check()) {
            //popup to fix preferences
            showYesNoNeutralPopup(context, "기록 업데이트 필요",
                    "저장된 데이터에서 더이상 지원되지 않는 이전 형식이 발견되었습니다. 정상적인 사용을 위해 업데이트가 필요합니다. 데이터를 업데이트 하시겠습니까?" +
                            "\n(데이터 일부가 유실될 수 있습니다. 꼭 백업을 하고 진행해 주세요)",
                    "데이터 백업",
                    (dialogInterface, i) -> {
                        //proceed
                        final EditText editText = new EditText(context);
                        editText.setHint(p.getDefUrl());

                        AlertDialog.Builder builder;
                        if (new Preference(context).getDarkTheme())
                            builder = new AlertDialog.Builder(context, R.style.darkDialog);
                        else builder = new AlertDialog.Builder(context);
                        builder.setTitle("기록 업데이트")
                                .setView(editText)
                                .setMessage("이 작업은 되돌릴수 없습니다. 계속 하려면 유효한 주소를 입력해 주세요.")
                                .setPositiveButton("계속", (dialogInterface15, i13) -> {
                                    String url = editText.getText().toString();
                                    if (url == null || url.length() < 1)
                                        url = p.getDefUrl();

                                    p.setUrl(url);

                                    Migrator.start(getApplicationContext());
                                    //queue title to service
                                    Toast.makeText(getApplication(), "작업을 시작합니다.", Toast.LENGTH_LONG).show();
                                    //restart activity
                                    finish();
                                    startActivity(getIntent());
                                })
                                .setNegativeButton("취소", (dialogInterface14, i12) -> finish())
                                .setOnCancelListener(dialogInterface13 -> finish())
                                .show();
                    }, (dialogInterface, i) -> showPopup(context, "알림", "앱의 데이터를 초기화 하거나 데이터 업데이트를 진행하지 않으면 사용이 불가합니다.", (dialogInterface12, i1) -> finish(), dialogInterface1 -> finish()), (dialogInterface, i) -> {
                        //backup
                        Intent intent1 = new Intent(context, FolderSelectActivity.class);
                        intent1.putExtra("mode", MODE_FILE_SAVE);
                        intent1.putExtra("title", "백업");
                        startActivityForResult(intent1, MODE_FILE_SAVE);
                    }, dialogInterface -> finish());
        }else if(action != null && action.equals(MIGRATE_RESULT)){
            migratorEndPopup(savedInstanceState, 0, intent.getStringExtra("msg"));
        }else{
            activityInit(savedInstanceState);
       }
    }

    private void activityInit(Bundle savedInstanceState){
        p.check2();
        setContentView(R.layout.activity_main);
        progressView = this.findViewById(R.id.progress_panel);
        applyMainWindowChrome();

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();
        toolbar.setNavigationIcon(null);

        //nav_drawer color scheme
        navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
        if(dark) {
            int[][] states = new int[][]{
                    new int[]{-android.R.attr.state_enabled}, // disabled
                    new int[]{android.R.attr.state_enabled}, // enabled
                    new int[]{-android.R.attr.state_checked}, // unchecked
                    new int[]{android.R.attr.state_pressed}  // pressed
            };

            int[] colors = new int[]{
                    Color.parseColor("#565656"),
                    Color.parseColor("#a2a2a2"),
                    Color.WHITE,
                    Color.WHITE
            };
            ColorStateList colorStateList = new ColorStateList(states, colors);
            navigationView.setItemTextColor(colorStateList);
        }
        bottomNavigationView = findViewById(R.id.bottom_nav);
        if(bottomNavigationView != null) {
            bottomNavigationView.setOnItemSelectedListener(item -> {
                int index = getFragmentIndex(item.getItemId());
                if(index < 0)
                    return false;
                changeFragment(index);
                toolbar.setTitle(getTabTitle(currentTab));
                return true;
            });
            bottomNavigationView.setOnItemReselectedListener(item -> {
                int index = getFragmentIndex(item.getItemId());
                if(index == 0 && fragments[0] instanceof MainMain)
                    ((MainMain) fragments[0]).scrollToSelectedTab();
                else if(index == 1 && fragments[1] instanceof MainSearch) {
                    ((MainSearch) fragments[1]).enterSearchMode();
                    toolbar.setTitle(getTabTitle(1));
                } else if(index == 2 && fragments[2] instanceof MainSearch) {
                    ((MainSearch) fragments[2]).enterLibraryMode();
                    toolbar.setTitle(getTabTitle(2));
                }
            });
        }

        homeDirStr = p.getHomeDir();

        content = findViewById(R.id.contentHolder);

        // Always start a fresh app session from Home. The older startTab preference can
        // point to Library after sync/settings restore, which makes cold starts feel wrong.
        startTab = 0;
        if(savedInstanceState != null) {
            int t = savedInstanceState.getInt("currentTab", 0);
            changeFragment(t>-1 ? t : 0);
        }else
            changeFragment(0);

        content.postDelayed(this::runDeferredStartupTasks, 500);

        // savedInstanceState


        // First launch should go straight into the app without notice/update popups.

    }

    private void runDeferredStartupTasks() {
        if(isFinishing() || isDestroyed())
            return;
        MainApplication.initDeferredServices();
        setupAccountHeader();
        startDeferredUrlUpdate();
        requestStartupPermissions();
    }

    private void startDeferredUrlUpdate() {
        if(!p.getAutoUrl())
            return;
        pendingUrlUpdateCallback = ((MainMain)fragments[0]).getCallback();
        if(startupViewModel == null) {
            startupViewModel = new ViewModelProvider(this).get(StartupViewModel.class);
            startupViewModel.state().observe(this, this::renderStartupUrlState);
        }
        startupViewModel.updateUrl(p.getDefUrl());
    }

    @SuppressWarnings("rawtypes")
    private void renderStartupUrlState(UiState state) {
        if(state instanceof UiState.Content) {
            UrlUpdateResult result = (UrlUpdateResult) ((UiState.Content) state).getValue();
            if(pendingUrlUpdateCallback != null)
                pendingUrlUpdateCallback.callback(result != null && result.getSuccess());
            pendingUrlUpdateCallback = null;
        } else if(state instanceof UiState.Error) {
            if(pendingUrlUpdateCallback != null)
                pendingUrlUpdateCallback.callback(false);
            pendingUrlUpdateCallback = null;
        }
    }

    private void requestStartupPermissions() {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if(permissionCheck == PackageManager.PERMISSION_DENIED) {
            if(Build.VERSION.SDK_INT >= CODE_SCOPED_STORAGE) {
                // Scoped storage does not need the legacy storage permission.
            } else {
                requestPermissions(new String[]{READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE}, PERMISSION_CODE);
            }
        }
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_CODE + 1);
        }
    }

    private void setupAccountHeader() {
        if(navigationView == null || navigationView.getHeaderCount() == 0)
            return;
        View header = navigationView.getHeaderView(0);
        View panel = header.findViewById(R.id.nav_account_panel);
        TextView name = header.findViewById(R.id.nav_account_name);
        TextView email = header.findViewById(R.id.nav_account_email);
        TextView button = header.findViewById(R.id.nav_account_button);
        TextView status = header.findViewById(R.id.nav_account_status);
        if(panel == null || button == null || name == null || email == null || status == null)
            return;
        FirebaseAccountManager accountManager = MainApplication.getFirebaseAccountManager();
        FirebaseUser user = accountManager == null ? null : accountManager.getUser();
        if(user != null) {
            name.setText(user.getDisplayName() == null || user.getDisplayName().length() == 0 ? "MangaView" : user.getDisplayName());
            email.setText(user.getEmail() == null ? "" : user.getEmail());
            button.setText(R.string.account_manage);
            button.setContentDescription(getString(R.string.account_manage));
            status.setText(R.string.account_status_signed_in);
            panel.setOnClickListener(v -> showAccountDialog());
            button.setOnClickListener(v -> showAccountDialog());
            if(!accountInitialSyncStarted && MainApplication.firebaseSyncManager != null) {
                accountInitialSyncStarted = true;
                panel.post(() -> {
                    if(!isFinishing() && !isDestroyed())
                        syncAccount(false);
                });
            }
        } else {
            name.setText("MangaView");
            email.setText(R.string.account_signed_out);
            button.setText(R.string.account_sign_in_short);
            button.setContentDescription(getString(R.string.account_sign_in));
            status.setText(R.string.account_status_signed_out);
            panel.setOnClickListener(v -> showAccountDialog());
            button.setOnClickListener(v -> showAccountDialog());
            accountInitialSyncStarted = false;
        }
    }

    private void startGoogleSignIn() {
        FirebaseAccountManager accountManager = MainApplication.getFirebaseAccountManager();
        if(accountManager == null || !accountManager.hasFirebaseConfig()) {
            Toast.makeText(context, R.string.account_firebase_missing, Toast.LENGTH_LONG).show();
            return;
        }
        if(!accountManager.isAvailable()) {
            Toast.makeText(context, R.string.account_google_oauth_missing, Toast.LENGTH_LONG).show();
            return;
        }
        accountManager.signIn(this);
    }

    private void toggleAccountSignIn() {
        showAccountDialog();
    }

    private void showAccountDialog() {
        if(accountSheet != null && accountSheet.isShowing()) {
            updateAccountSheet(false);
            return;
        }
        accountSheet = new BottomSheetDialog(this);
        ViewGroup content = findViewById(android.R.id.content);
        View view = getLayoutInflater().inflate(R.layout.sheet_account, content, false);
        accountSheetName = view.findViewById(R.id.account_sheet_name);
        accountSheetEmail = view.findViewById(R.id.account_sheet_email);
        accountSheetStatus = view.findViewById(R.id.account_sheet_status);
        accountSheetPrimary = view.findViewById(R.id.account_sheet_primary);
        accountSheetSecondary = view.findViewById(R.id.account_sheet_secondary);
        accountSheetHint = view.findViewById(R.id.account_sheet_hint);
        accountSheet.setContentView(view);
        accountSheet.setOnDismissListener(dialog -> clearAccountSheetRefs());
        updateAccountSheet(false);
        accountSheet.show();
    }

    private void confirmSignOut() {
        AlertDialog.Builder builder = dark ? new AlertDialog.Builder(context, R.style.darkDialog) : new AlertDialog.Builder(context);
        builder.setMessage(R.string.account_sign_out_confirm)
                .setPositiveButton(R.string.account_sign_out, (dialog, which) -> {
                    FirebaseAccountManager accountManager = MainApplication.getFirebaseAccountManager();
                    if(accountManager != null)
                        accountManager.signOut(() -> runOnUiThread(() -> {
                            setupAccountHeader();
                            updateAccountSheet(false);
                        }));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void syncAccount(boolean showResult) {
        if(MainApplication.getFirebaseSyncManager() == null)
            return;
        setAccountSyncingStatus(true);
        if(showResult)
            Toast.makeText(context, R.string.account_syncing, Toast.LENGTH_SHORT).show();
        MainApplication.getFirebaseSyncManager().syncAfterSignIn((syncSuccess, syncMessage) -> runOnUiThread(() -> {
            setAccountSyncingStatus(false);
            refreshSyncedListIfVisible();
            if(showResult || !syncSuccess)
                Toast.makeText(context, syncSuccess ? getString(R.string.account_sync_complete) : (syncMessage == null ? getString(R.string.account_sync_failed) : syncMessage), syncSuccess ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        }));
    }

    private void setAccountSyncingStatus(boolean syncing) {
        if(navigationView != null && navigationView.getHeaderCount() > 0) {
            View header = navigationView.getHeaderView(0);
            TextView status = header.findViewById(R.id.nav_account_status);
            if(status != null)
                status.setText(syncing ? R.string.account_status_syncing : R.string.account_status_signed_in);
        }
        updateAccountSheet(syncing);
    }

    private void updateAccountSheet(boolean syncing) {
        if(accountSheetName == null || accountSheetEmail == null || accountSheetStatus == null
                || accountSheetPrimary == null || accountSheetSecondary == null || accountSheetHint == null)
            return;
        FirebaseAccountManager accountManager = MainApplication.getFirebaseAccountManager();
        FirebaseUser user = accountManager == null ? null : accountManager.getUser();
        if(user == null) {
            accountSheetName.setText(R.string.account_sheet_signed_out_title);
            accountSheetEmail.setText(R.string.account_sheet_signed_out_body);
            accountSheetStatus.setText(R.string.account_status_signed_out);
            accountSheetHint.setText(R.string.account_sync_scope);
            accountSheetPrimary.setText(R.string.account_sign_in);
            accountSheetPrimary.setEnabled(true);
            accountSheetPrimary.setAlpha(1f);
            accountSheetPrimary.setOnClickListener(v -> startGoogleSignIn());
            accountSheetSecondary.setVisibility(View.GONE);
            return;
        }
        accountSheetName.setText(user.getDisplayName() == null || user.getDisplayName().length() == 0 ? getString(R.string.account_manage) : user.getDisplayName());
        accountSheetEmail.setText(user.getEmail() == null || user.getEmail().length() == 0 ? getString(R.string.account_status_signed_in) : user.getEmail());
        accountSheetStatus.setText(syncing ? R.string.account_status_syncing : R.string.account_status_signed_in);
        accountSheetHint.setText(R.string.account_sync_scope);
        accountSheetPrimary.setText(syncing ? R.string.account_status_syncing : R.string.account_sync_now);
        accountSheetPrimary.setEnabled(!syncing);
        accountSheetPrimary.setAlpha(syncing ? 0.55f : 1f);
        accountSheetPrimary.setOnClickListener(v -> syncAccount(true));
        accountSheetSecondary.setVisibility(View.VISIBLE);
        accountSheetSecondary.setText(R.string.account_sign_out);
        accountSheetSecondary.setOnClickListener(v -> confirmSignOut());
    }

    private void clearAccountSheetRefs() {
        accountSheet = null;
        accountSheetName = null;
        accountSheetEmail = null;
        accountSheetStatus = null;
        accountSheetPrimary = null;
        accountSheetSecondary = null;
        accountSheetHint = null;
    }

    private void refreshSyncedListIfVisible() {
        if(currentTab == 2 && fragments[2] instanceof MainSearch)
            ((MainSearch) fragments[2]).enterLibraryMode();
    }

    public int getTabId(int i){
        switch(i){
            case 0:
                return(R.id.nav_main);
            case 1:
                return(R.id.nav_search);
            case 2:
                return(R.id.nav_recent);
        }
        return 0;
    }

    public int getFragmentIndex(int i){
        switch(i){
            case R.id.nav_main:
                return 0;
            case R.id.nav_search:
                return 1;
            case R.id.nav_recent:
                return 2;
            case R.id.nav_favorite:
            case R.id.nav_download:
                return 2;
        }
        return -1;
    }

    private CharSequence getTabTitle(int index) {
        switch(index) {
            case 0:
                return "MangaView";
            case 1:
                return "검색";
            case 2:
                return "내 보관함";
        }
        return "";
    }

    private void applyMainWindowChrome() {
        if(dark)
            return;
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.appSurface));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.appCard));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void syncNavigationSelection() {
        Menu menu = navigationView.getMenu();
        for(int i = 0; i < menu.size(); i++)
            menu.getItem(i).setChecked(false);
        MenuItem item = menu.findItem(getTabId(currentTab));
        if(item != null)
            item.setChecked(true);
    }

    private void syncBottomNavigationSelection() {
        if(bottomNavigationView == null)
            return;
        MenuItem item = bottomNavigationView.getMenu().findItem(getTabId(currentTab));
        if(item != null)
            item.setChecked(true);
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            if(currentTab == startTab){

                DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                    switch (which){
                        case DialogInterface.BUTTON_POSITIVE:

                            //block interactivity
                            getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

                            if(Downloader.isRunning()){
                                //downloader is running
                                //show info prompt
                                findViewById(R.id.waiting_panel).setVisibility(View.VISIBLE);

                                //broadcast receiver
                                BroadcastReceiver statusReceiver = new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context context, Intent intent) {
                                        if(intent.getAction().matches(BROADCAST_STOP)){
                                            //service stopped
                                            finishAffinity();
                                            System.runFinalization();
                                            System.exit(0);
                                        }
                                    }
                                };
                                IntentFilter infil = new IntentFilter();
                                infil.addAction(BROADCAST_STOP);
                                registerReceiver(statusReceiver, infil);
                                Downloader.cancelAll(getApplicationContext());

                            }else{
                                //kill application
                                finishAffinity();
                                System.runFinalization();
                                System.exit(0);
                            }
                            break;
                        case DialogInterface.BUTTON_NEGATIVE:
                            //No button clicked
                            break;
                    }
                };
                AlertDialog.Builder builder;
                if(dark) builder = new AlertDialog.Builder(this,R.style.darkDialog);
                else builder = new AlertDialog.Builder(this);
                builder.setMessage(Downloader.isRunning()  ? "다운로드가 진행중입니다. 정말로 종료 하시겠습니까?" : "정말로 종료 하시겠습니까?")
                        .setPositiveButton("네", dialogClickListener)
                        .setNegativeButton("아니오", dialogClickListener)
                        .show();
            }else{
                changeFragment(startTab);
                syncNavigationSelection();
                toolbar.setTitle(getTabTitle(startTab));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem search = menu.findItem(R.id.action_search);
        if(search != null)
            search.setVisible(false);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_search) {
            openSearchTab();
            return true;
        }else if (id == R.id.action_settings) {
            toggleAccountSignIn();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    boolean changeFragment(int index){
        if(index < 0)
            return false;
        boolean res = false;
        if(index>-1 && index != currentTab){
            if(index == 1 && fragments[1] instanceof MainSearch)
                ((MainSearch) fragments[1]).enterSearchMode();
            currentTab = index;
            getSupportFragmentManager().beginTransaction().replace(R.id.contentHolder, (Fragment) fragments[index]).commit();
            res = true;
        } else if(index == 1 && fragments[1] instanceof MainSearch) {
            ((MainSearch) fragments[1]).enterSearchMode();
        } else if(index == 2 && fragments[2] instanceof MainSearch) {
            ((MainSearch) fragments[2]).enterLibraryMode();
        }
        getSupportActionBar().setTitle(getTabTitle(currentTab));
        syncNavigationSelection();
        syncBottomNavigationSelection();
        invalidateOptionsMenu();
        return res;
    }


    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();
        if(id == R.id.nav_favorite || id == R.id.nav_download) {
            changeFragment(2);
            getSupportFragmentManager().executePendingTransactions();
            if(fragments[2] instanceof MainSearch)
                ((MainSearch) fragments[2]).selectLibraryTab(id == R.id.nav_favorite ? 2 : 3);
            toolbar.setTitle(getTabTitle(2));
            DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);
            return true;
        }
        if (!changeFragment(getFragmentIndex(id))) {
            //don't refresh views
            if(id==R.id.nav_kakao){

                View layout = getLayoutInflater().inflate(R.layout.content_kakao_popup, null);
                layout.findViewById(R.id.kakao_notice).setOnClickListener(view -> Utils.safeStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(getResources().getString(R.string.kakao_notice)))));
                layout.findViewById(R.id.kakao_chat).setOnClickListener(view -> Utils.safeStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(getResources().getString(R.string.kakao_chat)))));
                layout.findViewById(R.id.kakao_direct).setOnClickListener(view -> Utils.safeStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(getResources().getString(R.string.kakao_direct)))));

                AlertDialog.Builder builder;
                if(dark) builder = new AlertDialog.Builder(context,R.style.darkDialog);
                else builder = new AlertDialog.Builder(context);
                Utils.safeShowDialog(builder.setTitle("오픈 카톡 참가")
                        .setView(layout));

//                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.kakao.com/o/gL4yY57"));
//                startActivity(browserIntent);
            }else if(id==R.id.nav_settings){
                Intent settingIntent = new Intent(context, SettingsActivity.class);
                startActivityForResult(settingIntent, 0);
                return true;
            }
            DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);
            return true;
        }
        toolbar.setTitle(item.getTitle());
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == FirebaseAccountManager.RC_GOOGLE_SIGN_IN) {
            FirebaseAccountManager accountManager = MainApplication.getFirebaseAccountManager();
            if(data != null && accountManager != null) {
                accountManager.handleActivityResult(data, (success, message) -> runOnUiThread(() -> {
                    if(success) {
                        accountInitialSyncStarted = true;
                        setupAccountHeader();
                        syncAccount(true);
                    } else {
                        Utils.safeToast(context, message == null ? getString(R.string.account_sign_in_failed) : message, Toast.LENGTH_LONG);
                    }
                }));
            } else {
                Utils.safeToast(context, getString(R.string.account_sign_in_failed), Toast.LENGTH_LONG);
            }
            return;
        }
        if(requestCode == FIRST_TIME_ACTIVITY){
            if(resultCode == RESULT_EULA_AGREE) {
                activityInit(null);
            }else
                finish();
            return;
        }else if(requestCode == MODE_FILE_SAVE){
            String path = null;
            if(data!=null)
                path = data.getStringExtra("path");
            if(path != null){
                if(writePreferenceToFile(context, new File(path))) {
                    Utils.safeToast(context, "백업 완료!", Toast.LENGTH_LONG);
                }else Utils.safeToast(context, "백업 실패", Toast.LENGTH_LONG);
            }else Utils.safeToast(context, "백업 실패", Toast.LENGTH_LONG);

            finish();
            Utils.safeStartActivity(context, getIntent());
        }
        if(resultCode == RESULT_NEED_RESTART){
            Intent intent = getIntent();
            finish();
            Utils.safeStartActivity(context, intent);
        }
    }

    public void hideProgressPanel(){
        progressView.setVisibility(View.GONE);
    }


    private void migratorEndPopup(Bundle bundle, int resCode, String msg){
        if(resCode==0) {
            final ScrollView scrollView = new ScrollView(context);
            final LinearLayout linearLayout = new LinearLayout(context);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            final TextView textView = new TextView(context);
            textView.setText(msg);
            final Button copyBtn = new Button(context);
            copyBtn.setText("결과 복사");
            copyBtn.setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("result", msg);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show();
            });
            final Button btn = new Button(context);
            btn.setText("닫기");
            linearLayout.addView(textView);
            linearLayout.addView(copyBtn);
            linearLayout.addView(btn);
            scrollView.addView(linearLayout);

            AlertDialog.Builder abuilder;
            if (new Preference(context).getDarkTheme())
                abuilder = new AlertDialog.Builder(context, R.style.darkDialog);
            else abuilder = new AlertDialog.Builder(context);
            AlertDialog dialog = abuilder.setTitle("결과")
                    .setView(scrollView)
                    .setOnCancelListener(dialogInterface -> activityInit(bundle))
                    .create();
            btn.setOnClickListener(view -> {
                dialog.dismiss();
                activityInit(bundle);
            });
            dialog.show();
        }
        else if(resCode == 1)
            showPopup(context, "연결 오류", "연결을 확인하고 다시 시도해 주세요.", (dialogInterface, i) -> finish(), dialogInterface -> finish());
    }
}
