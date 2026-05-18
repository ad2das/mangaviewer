package ml.melun.mangaview.activity;

import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

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
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.Downloader;
import ml.melun.mangaview.AppUpdateManager;
import ml.melun.mangaview.FirebaseAccountManager;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Migrator;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.fragment.MainMain;

import ml.melun.mangaview.fragment.MainSearch;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.interfaces.MainActivityCallback;
import ml.melun.mangaview.interfaces.UrlUpdateCallback;
import ml.melun.mangaview.model.UrlUpdateResult;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.BackgroundPrefetchBudget;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.PerfTrace;
import ml.melun.mangaview.state.UiState;
import ml.melun.mangaview.viewmodel.StartupViewModel;

import static ml.melun.mangaview.Downloader.BROADCAST_STOP;
import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Migrator.MIGRATE_FAIL;
import static ml.melun.mangaview.Migrator.MIGRATE_PROGRESS;
import static ml.melun.mangaview.Migrator.MIGRATE_RESULT;
import static ml.melun.mangaview.Migrator.MIGRATE_START;
import static ml.melun.mangaview.Migrator.MIGRATE_STOP;
import static ml.melun.mangaview.Migrator.MIGRATE_SUCCESS;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.Utils.showYesNoNeutralPopup;
import static ml.melun.mangaview.Utils.writePreferenceToFile;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.activity.FirstTimeActivity.RESULT_EULA_AGREE;
import static ml.melun.mangaview.activity.FolderSelectActivity.MODE_FILE_SAVE;
import static ml.melun.mangaview.activity.SettingsActivity.RESULT_NEED_RESTART;
import static ml.melun.mangaview.mangaview.CustomHttpClient.DEFAULT_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_WEBTOON_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;




public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, MainActivityCallback {

    public static int PERMISSION_CODE = 132322;
    int startTab;
    int currentTab = -1;
    private Context context;
    String homeDirStr;
    Boolean dark;
    DrawerLayout drawerLayout;
    ActionBarDrawerToggle drawerToggle;
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
    TextView accountSheetSettings;
    TextView accountSheetUpdate;
    TextView accountSheetHint;
    StartupViewModel startupViewModel;
    UrlUpdateCallback pendingUrlUpdateCallback;
    private boolean ntkCaptchaCheckScheduled = false;
    private long lastNtkCaptchaCheckAt = 0L;
    private final List<BroadcastReceiver> internalReceivers = new ArrayList<>();
    private static final int FIRST_TIME_ACTIVITY = 9;

    private void registerInternalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        internalReceivers.add(receiver);
    }

    private void restartMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
    }
    private static final String FRAGMENT_TAG_PREFIX = "main_tab_";
    private static final String EXTRA_SEARCH_QUERY = "searchQuery";
    private static final String EXTRA_SEARCH_BASE_MODE = "searchBaseMode";


    Fragment[] fragments = new Fragment[3];

    FrameLayout content;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("currentTab", currentTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openSearchQueryFromIntentWhenReady();
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
        ensureMainFragment(1);
        changeFragment(1);
        ((MainSearch) fragments[1]).enterSearchMode();
    }

    private void openSearchQueryFromIntentWhenReady() {
        if(content == null) {
            openSearchQueryFromIntent();
            return;
        }
        content.post(this::openSearchQueryFromIntent);
    }

    private void openSearchQueryFromIntent() {
        Intent intent = getIntent();
        if(intent == null)
            return;
        String query = intent.getStringExtra(EXTRA_SEARCH_QUERY);
        if(query == null)
            return;
        query = query.trim();
        if(query.length() == 0)
            return;

        ensureMainFragment(1);
        changeFragment(1);
        getSupportFragmentManager().executePendingTransactions();
        MainSearch searchFragment = (MainSearch) fragments[1];
        searchFragment.setBaseMode(intent.getIntExtra(EXTRA_SEARCH_BASE_MODE, p.getBaseMode()));
        searchFragment.setSearch(query);
        if(toolbar != null)
            toolbar.setTitle(getTabTitle(1));
    }

    private boolean forceWfwfOnStartup() {
        if(!p.forceWfwfSitePresetIfNeeded())
            return false;
        syncForcedWfwfCookiesAsync();
        if(toolbar != null)
            invalidateOptionsMenu();
        if(fragments[0] instanceof MainMain) {
            UrlUpdateCallback callback = ((MainMain) fragments[0]).getCallback();
            if(callback != null)
                callback.callback(true);
        }
        return true;
    }

    private void syncForcedWfwfCookiesAsync() {
        final String webtoonUrl = p.getWebtoonUrl();
        final String comicUrl = p.getUrl();
        AppDispatchers.runUserAction(() -> {
            long startedAt = PerfTrace.start("startup_force_wfwf_cookie_sync_ms");
            getHttpClient().syncCookiesFromWebView(webtoonUrl, true);
            getHttpClient().syncCookiesFromWebView(comicUrl, true);
            getHttpClient().clearPageCache();
            PerfTrace.end("startup_force_wfwf_cookie_sync_ms", startedAt);
        });
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        if(shouldFinishDuplicateLauncherForTest(isTaskRoot(),
                intent == null ? null : intent.getAction(),
                intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER))) {
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        long onCreateStartedAt = PerfTrace.start("main_on_create_ms");
        long beforeSuperStartedAt = PerfTrace.start("main_before_super_ms");
        long startupPrefetchSuppressMs = startupVisibleWarmupSuppressMsForTest();
        ViewerWarmupManager.suppressVisibleContinueWarmups(startupPrefetchSuppressMs);
        BackgroundPrefetchBudget.suppressNonCriticalPrefetch(startupPrefetchSuppressMs);
        if(savedInstanceState == null)
            forceWfwfOnStartup();
        fragments[0] = MainMain.newInstance();
        dark = p.getDarkTheme();
        if (dark) setTheme(R.style.AppThemeDarkNoTitle);
        else setTheme(R.style.AppTheme_NoActionBar);
        PerfTrace.end("main_before_super_ms", beforeSuperStartedAt);
        long superStartedAt = PerfTrace.start("main_super_on_create_ms");
        super.onCreate(savedInstanceState);
        PerfTrace.end("main_super_on_create_ms", superStartedAt);
        schedulePerformanceMonitorAttach();
        context = this;
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
            registerInternalReceiver(migratorStatusReceiver, infil);

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
                                    restartMainActivity();
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
            long activityInitStartedAt = PerfTrace.start("main_activity_init_call_ms");
            activityInit(savedInstanceState);
            PerfTrace.end("main_activity_init_call_ms", activityInitStartedAt);
       }
        PerfTrace.end("main_on_create_ms", onCreateStartedAt);
    }

    private void activityInit(Bundle savedInstanceState){
        long initStartedAt = PerfTrace.start("main_activity_init_ms");
        long checkStartedAt = PerfTrace.start("main_check2_ms");
        p.check2();
        PerfTrace.end("main_check2_ms", checkStartedAt);
        long setContentStartedAt = PerfTrace.start("main_set_content_view_ms");
        setContentView(R.layout.activity_main);
        PerfTrace.end("main_set_content_view_ms", setContentStartedAt);
        long chromeStartedAt = PerfTrace.start("main_chrome_setup_ms");
        progressView = this.findViewById(R.id.progress_panel);
        applyMainWindowChrome();

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        if(drawerLayout != null)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        toolbar.setNavigationIcon(null);
        bottomNavigationView = findViewById(R.id.bottom_nav);
        if(bottomNavigationView != null) {
            applyBottomNavigationChrome();
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
        PerfTrace.end("main_chrome_setup_ms", chromeStartedAt);

        homeDirStr = p.getHomeDir();

        long fragmentStartedAt = PerfTrace.start("main_initial_fragment_ms");
        content = findViewById(R.id.contentHolder);
        restoreExistingFragments();

        // Always start a fresh app session from Home. The older startTab preference can
        // point to Library after sync/settings restore, which makes cold starts feel wrong.
        startTab = 0;
        if(savedInstanceState != null) {
            int t = savedInstanceState.getInt("currentTab", 0);
            changeFragment(t>-1 ? t : 0);
        }else
            changeFragment(0);
        openSearchQueryFromIntent();
        PerfTrace.end("main_initial_fragment_ms", fragmentStartedAt);

        long postStartupStartedAt = PerfTrace.start("main_post_startup_ms");
        content.postDelayed(this::runDeferredStartupTasks, startupDeferredTasksDelayMsForTest());
        content.postDelayed(() -> {
            if(!isFinishing() && !isDestroyed())
                AppUpdateManager.checkForUpdate(this);
        }, startupUpdateCheckDelayMsForTest());
        scheduleNtkCaptchaCheck(startupNtkCaptchaCheckDelayMsForTest());
        PerfTrace.end("main_post_startup_ms", postStartupStartedAt);
        PerfTrace.end("main_activity_init_ms", initStartedAt);

        // savedInstanceState


        // First launch should go straight into the app without notice/update popups.

    }

    private void schedulePerformanceMonitorAttach() {
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if(decor == null) {
            attachPerformanceMonitorNow();
            return;
        }
        decor.postDelayed(this::attachPerformanceMonitorNow, startupPerformanceMonitorDelayMsForTest());
    }

    private void attachPerformanceMonitorNow() {
        if(isFinishing() || isDestroyed())
            return;
        long monitorStartedAt = PerfTrace.start("main_perf_monitor_attach_ms");
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen(performanceScreenName(currentTab));
        PerformanceMonitor.resume();
        PerfTrace.end("main_perf_monitor_attach_ms", monitorStartedAt);
    }

    private void runDeferredStartupTasks() {
        if(isFinishing() || isDestroyed())
            return;
        MainApplication.initDeferredServices();
        refreshNtkDomainIfNeeded();
        startDeferredUrlUpdate();
    }

    private void setupNavigationDrawer() {
        if(drawerLayout == null)
            drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        if(navigationView == null) {
            View nav = findViewById(R.id.nav_view);
            if(nav == null) {
                ViewStub stub = findViewById(R.id.nav_view_stub);
                if(stub != null)
                    nav = stub.inflate();
            }
            if(nav instanceof NavigationView) {
                navigationView = (NavigationView) nav;
                navigationView.setNavigationItemSelectedListener(this);
                applyNavigationDrawerColors();
            }
        }
        if(drawerLayout != null && navigationView != null)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        if(drawerToggle == null && drawerLayout != null && toolbar != null) {
            drawerToggle = new ActionBarDrawerToggle(
                    this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
            drawerLayout.addDrawerListener(drawerToggle);
            drawerToggle.syncState();
            toolbar.setNavigationIcon(null);
        }
        syncNavigationSelection();
    }

    private void applyNavigationDrawerColors() {
        if(!dark || navigationView == null)
            return;
        int[][] states = new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_checked},
                new int[]{android.R.attr.state_pressed}
        };
        int[] colors = new int[]{
                Color.parseColor("#565656"),
                Color.parseColor("#a2a2a2"),
                Color.WHITE,
                Color.WHITE
        };
        navigationView.setItemTextColor(new ColorStateList(states, colors));
    }

    static long startupDeferredTasksDelayMsForTest() {
        return 12_000L;
    }

    static long startupUpdateCheckDelayMsForTest() {
        return 5 * 60_000L;
    }

    static long startupVisibleWarmupSuppressMsForTest() {
        return 15_000L;
    }

    static long startupPerformanceMonitorDelayMsForTest() {
        return 16_000L;
    }

    static long startupNtkCaptchaCheckDelayMsForTest() {
        return 20_000L;
    }

    static long ntkCaptchaCheckMinIntervalMsForTest() {
        return 10_000L;
    }

    static boolean shouldFinishDuplicateLauncherForTest(boolean isTaskRoot, String action, boolean hasLauncherCategory) {
        return !isTaskRoot
                && Intent.ACTION_MAIN.equals(action)
                && hasLauncherCategory;
    }

    @Override
    protected void onResume() {
        super.onResume();
        PerformanceMonitor.resume();
        AppUpdateManager.resumePendingInstall(this);
        invalidateOptionsMenu();
        scheduleNtkCaptchaCheck(startupNtkCaptchaCheckDelayMsForTest());
    }

    @Override
    protected void onPause() {
        PerformanceMonitor.pause();
        if(fragments[0] instanceof MainMain)
            ((MainMain) fragments[0]).cancelHomeFetches();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterInternalReceivers();
        super.onDestroy();
    }

    private void unregisterInternalReceivers() {
        for(BroadcastReceiver receiver : new ArrayList<>(internalReceivers)) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        internalReceivers.clear();
    }

    private boolean maybeOpenNtkCaptcha() {
        if(isFinishing() || isDestroyed())
            return false;
        return Utils.startNtkTurnstileCaptchaIfNeeded(this, 3, null, p);
    }

    private void scheduleNtkCaptchaCheck(long delayMs) {
        if(content == null) {
            maybeOpenNtkCaptcha();
            return;
        }
        if(ntkCaptchaCheckScheduled)
            return;
        ntkCaptchaCheckScheduled = true;
        content.postDelayed(() -> {
            ntkCaptchaCheckScheduled = false;
            if(isFinishing() || isDestroyed())
                return;
            long now = SystemClock.uptimeMillis();
            if(now - lastNtkCaptchaCheckAt < ntkCaptchaCheckMinIntervalMsForTest())
                return;
            lastNtkCaptchaCheckAt = now;
            maybeOpenNtkCaptcha();
        }, delayMs);
    }

    private void refreshNtkDomainIfNeeded() {
        if(p == null || !p.isNtkSite())
            return;
        AppDispatchers.runUserAction(() -> {
            boolean changed = getHttpClient().resolveNtkDomainNow();
            if(changed)
                AppDispatchers.runOnMain(this::invalidateOptionsMenu);
        });
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

    private void setupAccountHeader() {
        if(navigationView == null)
            setupNavigationDrawer();
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
        if(accountSheet != null)
            accountSheet.dismiss();
        accountManager.signIn(this, (success, message) -> {
            if(!success)
                runOnUiThread(() -> Utils.safeToast(context,
                        message == null ? getString(R.string.account_sign_in_failed) : message,
                        Toast.LENGTH_LONG));
        });
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
        accountSheetSettings = view.findViewById(R.id.account_sheet_settings);
        accountSheetUpdate = view.findViewById(R.id.account_sheet_update);
        accountSheetHint = view.findViewById(R.id.account_sheet_hint);
        applyAccountSheetTheme(view);
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
                || accountSheetPrimary == null || accountSheetSecondary == null
                || accountSheetSettings == null || accountSheetUpdate == null || accountSheetHint == null)
            return;
        accountSheetSettings.setText(R.string.account_open_settings);
        accountSheetSettings.setOnClickListener(v -> {
            if(accountSheet != null)
                accountSheet.dismiss();
            startActivityForResult(new Intent(context, SettingsActivity.class), 0);
        });
        accountSheetUpdate.setText(R.string.account_check_update);
        accountSheetUpdate.setOnClickListener(v -> {
            if(accountSheet != null)
                accountSheet.dismiss();
            AppUpdateManager.checkForUpdateNow(this);
        });
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

    private void applyAccountSheetTheme(View root) {
        if(!dark || root == null)
            return;
        root.setBackground(makeRoundedBackground(R.color.colorDarkSurface, R.color.colorDarkDivider, 24));
        int text = ContextCompat.getColor(this, R.color.colorDarkText);
        int secondary = ContextCompat.getColor(this, R.color.colorDarkTextSecondary);
        if(accountSheetName != null)
            accountSheetName.setTextColor(text);
        if(accountSheetEmail != null)
            accountSheetEmail.setTextColor(secondary);
        if(accountSheetHint != null)
            accountSheetHint.setTextColor(secondary);
        if(accountSheetStatus != null) {
            accountSheetStatus.setTextColor(ContextCompat.getColor(this, R.color.appAccent));
            accountSheetStatus.setBackground(makeRoundedBackground(R.color.colorDarkSurfaceElevated, R.color.colorDarkDivider, 15));
        }
        styleAccountSheetButton(accountSheetPrimary, true);
        styleAccountSheetButton(accountSheetSecondary, false);
        styleAccountSheetButton(accountSheetSettings, false);
        styleAccountSheetButton(accountSheetUpdate, false);
    }

    private void styleAccountSheetButton(TextView view, boolean primary) {
        if(view == null)
            return;
        int text = ContextCompat.getColor(this, primary ? R.color.colorTextOnPrimary : R.color.colorDarkText);
        view.setTextColor(text);
        if(view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton)view;
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                    primary ? R.color.appAccent : R.color.colorDarkSurface)));
            button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this,
                    primary ? R.color.appAccent : R.color.colorDarkDivider)));
        } else if(!primary) {
            view.setBackground(makeRoundedBackground(R.color.colorDarkSurface, R.color.colorDarkDivider, 12));
        }
    }

    private void clearAccountSheetRefs() {
        accountSheet = null;
        accountSheetName = null;
        accountSheetEmail = null;
        accountSheetStatus = null;
        accountSheetPrimary = null;
        accountSheetSecondary = null;
        accountSheetSettings = null;
        accountSheetUpdate = null;
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

    private void restoreExistingFragments() {
        for(int i = 0; i < fragments.length; i++) {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(fragmentTag(i));
            if(existing != null)
                fragments[i] = existing;
        }
    }

    private Fragment ensureMainFragment(int index) {
        if(index < 0 || index >= fragments.length)
            return null;
        if(fragments[index] != null)
            return fragments[index];
        if(index == 0)
            fragments[index] = MainMain.newInstance();
        else if(index == 1)
            fragments[index] = MainSearch.newSearchTab();
        else if(index == 2)
            fragments[index] = MainSearch.newLibraryTab();
        return fragments[index];
    }

    private String fragmentTag(int index) {
        return FRAGMENT_TAG_PREFIX + index;
    }

    private void showFragment(int index) {
        Fragment target = ensureMainFragment(index);
        if(target == null)
            return;
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for(Fragment fragment : getSupportFragmentManager().getFragments()) {
            if(fragment != null
                    && fragment.getId() == R.id.contentHolder
                    && fragment != target
                    && fragment.isAdded())
                transaction.hide(fragment);
        }
        for(int i = 0; i < fragments.length; i++) {
            Fragment fragment = fragments[i];
            if(fragment == null)
                continue;
            if(i == index) {
                if(fragment.isAdded())
                    transaction.show(fragment);
                else
                    transaction.add(R.id.contentHolder, fragment, fragmentTag(i));
            } else if(fragment.isAdded()) {
                transaction.hide(fragment);
            }
        }
        transaction.commitNowAllowingStateLoss();
    }

    private void applyMainWindowChrome() {
        if(dark) {
            int background = ContextCompat.getColor(this, R.color.colorDarkWindowBackground);
            getWindow().setStatusBarColor(background);
            getWindow().setNavigationBarColor(background);
            getWindow().getDecorView().setSystemUiVisibility(0);
            View contentHolder = findViewById(R.id.contentHolder);
            if(contentHolder != null) {
                contentHolder.setBackgroundColor(background);
                if(contentHolder.getParent() instanceof View)
                    ((View) contentHolder.getParent()).setBackgroundColor(background);
            }
            Toolbar toolbar = findViewById(R.id.toolbar);
            if(toolbar != null) {
                toolbar.setBackgroundColor(background);
                toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.colorDarkText));
            }
            View root = findViewById(R.id.drawer_layout);
            if(root != null)
                root.setBackgroundColor(background);
            return;
        }
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.appSurface));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.appCard));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void applyBottomNavigationChrome() {
        if(bottomNavigationView == null)
            return;
        if(!dark)
            return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, R.color.colorDarkSurface));
        background.setStroke(dp(1), ContextCompat.getColor(this, R.color.colorDarkDivider));
        background.setCornerRadius(dp(24));
        bottomNavigationView.setBackground(background);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] colors = new int[]{
                ContextCompat.getColor(this, R.color.appAccent),
                ContextCompat.getColor(this, R.color.colorDarkTextSecondary)
        };
        ColorStateList tint = new ColorStateList(states, colors);
        bottomNavigationView.setItemIconTintList(tint);
        bottomNavigationView.setItemTextColor(tint);
    }

    private GradientDrawable makeRoundedBackground(int fillColorRes, int strokeColorRes, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, fillColorRes));
        background.setStroke(dp(1), ContextCompat.getColor(this, strokeColorRes));
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void syncNavigationSelection() {
        if(navigationView == null)
            return;
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
                                        }
                                    }
                                };
                                IntentFilter infil = new IntentFilter();
                                infil.addAction(BROADCAST_STOP);
                                registerInternalReceiver(statusReceiver, infil);
                                Downloader.cancelAll(getApplicationContext());

                            }else{
                                //kill application
                                finishAffinity();
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
        MenuItem site = menu.findItem(R.id.action_site_switch);
        if(site != null) {
            boolean ntk = p.isNtkSite();
            site.setIcon(ntk ? R.drawable.ic_site_ntk : R.drawable.ic_site_wfwf);
            site.setTitle(ntk ? "NTK" : "WFWF");
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_search) {
            openSearchTab();
            return true;
        }else if (id == R.id.action_site_switch) {
            toggleSitePreset();
            return true;
        }else if (id == R.id.action_settings) {
            toggleAccountSignIn();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleSitePreset() {
        if(p.isNtkSite())
            switchSitePreset(DEFAULT_COMIC_URL, WEBTOON_URL, "WFWF");
        else
            switchSitePreset(NTK_COMIC_URL, NTK_WEBTOON_URL, "NTK");
    }

    private void switchSitePreset(String comicUrl, String webtoonUrl, String label) {
        p.setSitePreset(comicUrl, webtoonUrl);
        MainApplication.getHttpClient().clearPageCache();
        invalidateOptionsMenu();
        UrlUpdateCallback callback = fragments[0] instanceof MainMain ? ((MainMain) fragments[0]).getCallback() : null;
        if(callback != null)
            callback.callback(true);
        Toast.makeText(context, label + " 사이트로 변경되었습니다.", Toast.LENGTH_SHORT).show();
        if("NTK".equals(label))
            content.post(() -> Utils.verifyNtkAccessAndOpenCaptchaIfNeeded(this, 3, null, p));
    }

    boolean changeFragment(int index){
        if(index < 0)
            return false;
        ensureMainFragment(index);
        boolean res = false;
        if(index>-1 && index != currentTab){
            cancelHiddenHomeWork(index);
            if(index == 1 && fragments[1] instanceof MainSearch)
                ((MainSearch) fragments[1]).enterSearchMode();
            currentTab = index;
            PerformanceMonitor.screen(performanceScreenName(index));
            res = true;
        } else if(index == 1 && fragments[1] instanceof MainSearch) {
            ((MainSearch) fragments[1]).enterSearchMode();
            PerformanceMonitor.screen("search");
        } else if(index == 2 && fragments[2] instanceof MainSearch) {
            ((MainSearch) fragments[2]).enterLibraryMode();
            PerformanceMonitor.screen("library");
        }
        showFragment(index);
        getSupportActionBar().setTitle(getTabTitle(currentTab));
        syncNavigationSelection();
        syncBottomNavigationSelection();
        invalidateOptionsMenu();
        return res;
    }

    private void cancelHiddenHomeWork(int nextIndex) {
        if(currentTab == 0 && nextIndex != 0 && fragments[0] instanceof MainMain)
            ((MainMain) fragments[0]).cancelHomeFetches();
    }

    private String performanceScreenName(int index) {
        if(index == 1)
            return "search";
        if(index == 2)
            return "library";
        return "home";
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
            if(accountManager != null) {
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
        if(resultCode == RESULT_CAPTCHA) {
            invalidateOptionsMenu();
            if(fragments[0] instanceof MainMain) {
                UrlUpdateCallback callback = ((MainMain) fragments[0]).getCallback();
                if(callback != null)
                    callback.callback(true);
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
            restartMainActivity();
        }
        if(resultCode == RESULT_NEED_RESTART){
            recreate();
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
