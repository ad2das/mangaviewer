package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import ml.melun.mangaview.Preference;
import ml.melun.mangaview.NtkDeviceIdentityManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.readPreferenceFromFile;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.Utils.showStringInputPopup;
import static ml.melun.mangaview.Utils.showYesNoPopup;
import static ml.melun.mangaview.Utils.writePreferenceToFile;
import static ml.melun.mangaview.activity.FolderSelectActivity.MODE_FILE_SAVE;
import static ml.melun.mangaview.activity.FolderSelectActivity.MODE_FILE_SELECT;
import static ml.melun.mangaview.activity.FolderSelectActivity.MODE_FOLDER_SELECT;
import static ml.melun.mangaview.mangaview.CustomHttpClient.DEFAULT_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_WEBTOON_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;

public class SettingsActivity extends AppCompatActivity {

    //다운로드 위치 설정
    //데이터 절약 모드 : 외부 이미지 로드 안함
    //
    Context context;
    ConstraintLayout s_setHomeDir, s_resetHistory, s_dark, s_viewer, s_reverse, s_pageRtl, s_dataSave, s_tab, s_stretch, s_double, s_double_reverse;
    Spinner s_tab_spinner, s_viewer_spinner;
    Switch s_dark_switch, s_reverse_switch, s_pageRtl_switch, s_dataSave_switch, s_stretch_switch, s_double_switch, s_double_reverse_switch;
    Boolean dark;
    public static final String prefExtension = ".mvpref";
    public static final int RESULT_NEED_RESTART = 7;
    private static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";

    View.OnClickListener pbtnClear, nbtnClear, pbtnSet, nbtnSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = p.getDarkTheme();

        if(dark) setTheme(R.style.AppThemeDark);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, dark ? R.color.colorDarkWindowBackground : R.color.appSurface));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, dark ? R.color.colorDarkWindowBackground : android.R.color.white));
        styleSettingsScreen();
        context = this;
        s_setHomeDir = this.findViewById(R.id.setting_dir);
        s_setHomeDir.setOnClickListener(v -> {
            {
                // Choose a directory using the system's file picker.
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                Uri uri = Uri.parse(p.getHomeDir());
                intent.putExtra(EXTRA_INITIAL_URI, uri);
                Toast.makeText(context, "다운로드 위치를 선택해 주세요", Toast.LENGTH_SHORT).show();
                startActivityForResult(intent, MODE_FOLDER_SELECT);
            }
        });
        if(getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
//        s_getSd = this.findViewById(R.id.setting_externalSd);
//        s_getSd.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                p.setHomeDir("/sdcard");
//            }
//        });
        this.findViewById(R.id.setting_change_device).setOnClickListener(v -> {
            DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        NtkDeviceIdentityManager.changeDeviceInfo(context, false);
                        Toast.makeText(context, "기기정보가 변경되었습니다. 앱을 재시작합니다.", Toast.LENGTH_LONG).show();
                        restartAppAfterDeviceChange();
                        break;
                    case DialogInterface.BUTTON_NEGATIVE:
                        break;
                }
            };
            AlertDialog.Builder builder;
            if(dark) builder = new AlertDialog.Builder(context, R.style.darkDialog);
            else builder = new AlertDialog.Builder(context);
            builder.setMessage("기기정보를 변경하면 서버 차단이 해제될 수 있습니다.\nWebView 쿠키/캐시/저장 데이터도 모두 삭제됩니다.\n계속 하시겠습니까?")
                    .setPositiveButton("네", dialogClickListener)
                    .setNegativeButton("아니오", dialogClickListener)
                    .show();
        });

        s_resetHistory = this.findViewById(R.id.setting_reset);
        s_resetHistory.setOnClickListener(v -> {
            DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        //Yes button clicked
                        p.resetBookmark();
                        p.resetViewerBookmark();
                        p.resetRecent();
                        Toast.makeText(context, "초기화 되었습니다.", Toast.LENGTH_LONG).show();
                        setResult(RESULT_NEED_RESTART);
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        //No button clicked
                        break;
                }
            };
            AlertDialog.Builder builder;
            if(dark) builder = new AlertDialog.Builder(context, R.style.darkDialog);
            else builder = new AlertDialog.Builder(context);
            builder.setMessage("최근 본 만화, 북마크 및 모든 만화 열람 기록이 사라집니다. 계속 하시겠습니까?\n(좋아요, 저장한 만화 제외)").setPositiveButton("네", dialogClickListener)
                    .setNegativeButton("아니오", dialogClickListener).show();
        });
        this.findViewById(R.id.setting_key).setOnClickListener(new View.OnClickListener() {
            int prevKeyCode;
            int nextKeyCode;
            InputCallback inputCallback = null;
            @Override
            public void onClick(View view) {
                prevKeyCode = p.getPrevPageKey();
                nextKeyCode = p.getNextPageKey();

                View v = getLayoutInflater().inflate(R.layout.content_key_set_popup, null);
                Button pbtn = v.findViewById(R.id.key_prev);
                Button nbtn = v.findViewById(R.id.key_next);
                TextView ptext = v.findViewById(R.id.key_prev_text);
                TextView ntext = v.findViewById(R.id.key_next_text);

                if(prevKeyCode == -1)
                    ptext.setText("-");
                else
                    ptext.setText(KeyEvent.keyCodeToString(prevKeyCode));
                if(nextKeyCode == -1)
                    ntext.setText("-");
                else
                    ntext.setText(KeyEvent.keyCodeToString(nextKeyCode));

                pbtnClear = view14 -> {
                    if(prevKeyCode == -1)
                        ptext.setText("-");
                    else
                        ptext.setText(KeyEvent.keyCodeToString(prevKeyCode));
                    inputCallback = null;
                    view14.setOnClickListener(pbtnSet);
                };
                nbtnClear = view13 -> {
                    if(nextKeyCode == -1)
                        ntext.setText("-");
                    else
                        ntext.setText(KeyEvent.keyCodeToString(nextKeyCode));
                    inputCallback = null;
                    view13.setOnClickListener(nbtnSet);
                };
                pbtnSet = view12 -> {
                    if(inputCallback == null) {
                        view12.setOnClickListener(pbtnClear);
                        ptext.setText("키를 입력해 주세요");
                        inputCallback = event -> {
                            prevKeyCode = event.getKeyCode();
                            ptext.setText(KeyEvent.keyCodeToString(prevKeyCode));
                            view12.setEnabled(true);
                            view12.setOnClickListener(pbtnSet);
                        };
                    }
                };
                nbtnSet = view1 -> {
                    if(inputCallback == null) {
                        view1.setOnClickListener(nbtnClear);
                        ntext.setText("키를 입력해 주세요");
                        inputCallback = event -> {
                            nextKeyCode = event.getKeyCode();
                            ntext.setText(KeyEvent.keyCodeToString(nextKeyCode));
                            view1.setEnabled(true);
                            view1.setOnClickListener(nbtnSet);
                        };
                    }
                };

                pbtn.setOnClickListener(pbtnSet);
                nbtn.setOnClickListener(nbtnSet);

                AlertDialog.Builder builder;
                if(dark) builder = new AlertDialog.Builder(context,R.style.darkDialog);
                else builder = new AlertDialog.Builder(context);
                builder.setTitle("단축키 설정")
                        .setView(v)
                        .setOnKeyListener((dialogInterface, i, keyEvent) -> {
                            if(inputCallback != null){
                                if(keyEvent.getAction() == KeyEvent.ACTION_DOWN){
                                    inputCallback.onKeyEvent(keyEvent);
                                    inputCallback = null;
                                }
                                return true;
                            }
                            return false;
                        })
                        .setNeutralButton("초기화", (dialogInterface, i) -> {
                            p.setPrevPageKey(-1);
                            p.setNextPageKey(-1);
                            inputCallback = null;
                        })
                        .setNegativeButton("취소", (dialogInterface, i) -> inputCallback = null)
                        .setPositiveButton("적용", (dialogInterface, i) -> {
                            inputCallback = null;
                            p.setNextPageKey(nextKeyCode);
                            p.setPrevPageKey(prevKeyCode);
                        })
                        .setOnCancelListener(dialogInterface -> inputCallback = null)
                        .show();
            }
        });

        s_dark = this.findViewById(R.id.setting_dark);
        s_dark_switch = this.findViewById(R.id.setting_dark_switch);
        s_dark_switch.setChecked(p.getDarkTheme());
        s_dark.setOnClickListener(v -> s_dark_switch.toggle());
        s_dark_switch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            p.setDarkTheme(isChecked);
            if(isChecked != dark) setResult(RESULT_NEED_RESTART);
            else setResult(RESULT_CANCELED);
        });

        s_viewer = this.findViewById(R.id.setting_viewer);
        s_viewer_spinner = this.findViewById(R.id.setting_viewer_spinner);
        if(dark) s_viewer_spinner.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
        s_viewer_spinner.setSelection(p.getViewerType());
        s_viewer_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                p.setViewerType(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                //
            }
        });

        s_reverse = this.findViewById(R.id.setting_reverse);
        s_reverse_switch = this.findViewById(R.id.setting_reverse_switch);
        s_reverse_switch.setChecked(p.getReverse());
        s_reverse.setOnClickListener(v -> s_reverse_switch.toggle());
        s_reverse_switch.setOnCheckedChangeListener((buttonView, isChecked) -> p.setReverse(isChecked));
        s_pageRtl = this.findViewById(R.id.setting_pageRtl);
        s_pageRtl_switch = this.findViewById(R.id.setting_pageRtl_switch);
        s_pageRtl_switch.setChecked(p.getPageRtl());
        s_pageRtl.setOnClickListener(v -> s_pageRtl_switch.toggle());
        s_pageRtl_switch.setOnCheckedChangeListener((buttonView, isChecked) -> p.setPageRtl(isChecked));

        s_dataSave = this.findViewById(R.id.setting_dataSave);
        s_dataSave_switch = this.findViewById(R.id.setting_dataSave_switch);
        s_dataSave_switch.setChecked(p.getDataSave());
        s_dataSave.setOnClickListener(v -> s_dataSave_switch.toggle());
        s_dataSave_switch.setOnCheckedChangeListener((buttonView, isChecked) -> p.setDataSave(isChecked));

        s_tab = this.findViewById(R.id.setting_startTab);
        s_tab_spinner = this.findViewById(R.id.setting_startTab_spinner);
        if(dark) s_tab_spinner.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
        s_tab_spinner.setSelection(p.getStartTab());
        s_tab_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                p.setStartTab(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                //
            }
        });


        this.findViewById(R.id.setting_license).setOnClickListener(v -> {
            Intent l = new Intent(context,LicenseActivity.class);
            startActivity(l);
        });


        this.findViewById(R.id.setting_url).setOnClickListener(v -> urlSettingPopup(context, p));
        updateSiteToggleText();
        this.findViewById(R.id.setting_site_toggle).setOnClickListener(v -> {
            if(p.isNtkSite()) {
                switchSite(DEFAULT_COMIC_URL, WEBTOON_URL, "WFWF");
            } else {
                switchSite(NTK_COMIC_URL, NTK_WEBTOON_URL, "NTK");
                verifyNtkAfterSiteSwitch("NTK");
            }
        });
        this.findViewById(R.id.setting_ntk_diagnostics).setOnClickListener(v -> runNtkNetworkDiagnostics());

        s_stretch = this.findViewById(R.id.setting_stretch);
        s_stretch_switch = this.findViewById(R.id.setting_stretch_switch);
        s_stretch_switch.setChecked(p.getStretch());
        s_stretch.setOnClickListener(v -> s_stretch_switch.toggle());
        s_stretch_switch.setOnCheckedChangeListener((buttonView, isChecked) -> p.setStretch(isChecked));

        this.findViewById(R.id.setting_buttonLayout).setOnClickListener(view -> startActivity(new Intent(context, LayoutEditActivity.class)));

        this.findViewById(R.id.setting_dataExport).setOnClickListener(view -> {
             {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                Uri uri = Uri.parse(p.getHomeDir());
                intent.putExtra(EXTRA_INITIAL_URI, uri);
                Toast.makeText(context, "백업 파일을 저장할 폴더를 선택해 주세요", Toast.LENGTH_SHORT).show();
                startActivityForResult(intent, MODE_FILE_SAVE);
            }
        });

        this.findViewById(R.id.setting_dataImport).setOnClickListener(view -> {
             {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                Uri uri = Uri.parse(p.getHomeDir());
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/*");
                intent.putExtra(EXTRA_INITIAL_URI, uri);
                Toast.makeText(context, "백업 파일 선택", Toast.LENGTH_SHORT).show();
                startActivityForResult(intent, MODE_FILE_SELECT);
            }
        });

        s_double = this.findViewById(R.id.setting_double);
        s_double_switch = this.findViewById(R.id.setting_double_switch);
        s_double_switch.setChecked(p.getDoublep());
        s_double.setOnClickListener(v -> s_double_switch.toggle());
        s_double_switch.setOnCheckedChangeListener((buttonView, isChecked) -> p.setDoublep(isChecked));

        s_double_reverse = this.findViewById(R.id.setting_double_leftright);
        s_double_reverse_switch = this.findViewById(R.id.setting_double_leftright_switch);
        s_double_reverse_switch.setChecked(p.getDoublepReverse());
        s_double_reverse.setOnClickListener(v -> s_double_reverse_switch.toggle());
        s_double_reverse_switch.setOnCheckedChangeListener((buttonView, isChecked) -> p.setDoublepReverse(isChecked));

    }

    private void styleSettingsScreen() {
        SettingsScreenStyler.style(this, dark);
    }

    private void restartAppAfterDeviceChange() {
        View content = findViewById(android.R.id.content);
        Runnable restart = () -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if(intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        };
        if(content != null)
            content.postDelayed(restart, 900L);
        else
            new android.os.Handler().postDelayed(restart, 900L);
    }

    private int dp(int value) {
        return SettingsScreenStyler.dp(this, value);
    }

    private String generateRandomUserAgent() {
        String[] models = {"SM-G981B","SM-S928N","SM-G998N","SM-F946N","SM-X910","SM-A546N","SM-N986N","SM-M546B","SM-G975N","SM-N971N","SM-A736B"};
        String model = models[(int)(Math.random() * models.length)];
        try {
            android.webkit.WebView webView = new android.webkit.WebView(context);
            String defaultUA = webView.getSettings().getUserAgentString();
            webView.destroy();
            if(defaultUA != null && defaultUA.length() > 0) {
                String cleaned = defaultUA
                        .replace("; wv", "")
                        .replace(" wv", "")
                        .replace("Version/4.0 ", "");
                // Replace the device model part (between "Android X; " and ")")
                int androidIdx = cleaned.indexOf("Android ");
                int modelEnd = cleaned.indexOf(")", androidIdx);
                if(androidIdx >= 0 && modelEnd > androidIdx) {
                    int modelStart = cleaned.lastIndexOf(";", modelEnd);
                    if(modelStart > androidIdx) {
                        return cleaned.substring(0, modelStart + 2) + model + cleaned.substring(modelEnd);
                    }
                }
                return cleaned;
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        // Fallback to generated UA matching actual WebView version
        try {
            android.webkit.WebView webView = new android.webkit.WebView(context);
            String defaultUA = webView.getSettings().getUserAgentString();
            webView.destroy();
            if(defaultUA != null && defaultUA.length() > 0) {
                return defaultUA.replace("; wv", "").replace(" wv", "").replace("Version/4.0 ", "");
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return "Mozilla/5.0 (Linux; Android 13; " + model + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";
    }

    private void updateSiteToggleText() {
        TextView current = findViewById(R.id.setting_site_current);
        if(current != null)
            current.setText(p.isNtkSite() ? "NTK" : "WFWF");
    }

    private void switchSite(String comicUrl, String webtoonUrl, String label) {
        p.setSitePreset(comicUrl, webtoonUrl);
        getHttpClient().syncCookiesFromWebView(p.getWebtoonUrl(), true);
        getHttpClient().syncCookiesFromWebView(p.getUrl(), true);
        getHttpClient().clearPageCache();
        updateSiteToggleText();
        Toast.makeText(context, label + " 사이트로 변경되었습니다.", Toast.LENGTH_SHORT).show();
    }

    private void verifyNtkAfterSiteSwitch(String label) {
        if(!"NTK".equals(label))
            return;
        findViewById(android.R.id.content).post(() ->
                Utils.verifyNtkAccessAndOpenCaptchaIfNeeded(this, CaptchaActivity.RESULT_CAPTCHA, null, p));
    }

    private void runNtkNetworkDiagnostics() {
        AlertDialog.Builder builder = dark ? new AlertDialog.Builder(context, R.style.darkDialog) : new AlertDialog.Builder(context);
        AlertDialog progress = builder
                .setTitle("Site network diagnostics")
                .setMessage("Running DNS and route checks...")
                .setCancelable(false)
                .create();
        progress.show();
        String network = currentNetworkSummary();
        AppDispatchers.runIo(() -> {
            String report = getHttpClient().buildNtkNetworkDiagnosticReport(network);
            AppDispatchers.runOnMain(() -> {
                if(isFinishing() || isDestroyed())
                    return;
                if(progress.isShowing())
                    progress.dismiss();
                showNtkDiagnosticResult(report);
            });
        });
    }

    private void showNtkDiagnosticResult(String report) {
        ScrollView scrollView = new ScrollView(context);
        int padding = dp(16);
        scrollView.setPadding(padding, padding, padding, padding);
        TextView output = new TextView(context);
        output.setText(report);
        output.setTextIsSelectable(true);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextColor(ContextCompat.getColor(this, dark ? R.color.colorDarkText : R.color.appText));
        scrollView.addView(output, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = dark ? new AlertDialog.Builder(context, R.style.darkDialog) : new AlertDialog.Builder(context);
        builder.setTitle("Site network diagnostics")
                .setView(scrollView)
                .setPositiveButton("Copy", (dialog, which) -> copyDiagnosticReport(report))
                .setNegativeButton("Close", null);
        if(p != null && p.isNtkSite())
            builder.setNeutralButton("Open captcha", (dialog, which) -> openNtkCaptchaFromSettings());
        builder.show();
    }

    private void openNtkCaptchaFromSettings() {
        getHttpClient().clearCloudflareWebViewCookies(p.getWebtoonUrl(), p.getUrl(), getHttpClient().getLastCloudflareChallengeUrl());
        Intent intent = new Intent(context, CaptchaActivity.class);
        String challengeUrl = getHttpClient().getLastCloudflareChallengeUrl();
        if(challengeUrl != null && challengeUrl.length() > 0)
            intent.putExtra("url", challengeUrl);
        startActivityForResult(intent, CaptchaActivity.RESULT_CAPTCHA);
    }

    private void copyDiagnosticReport(String report) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Site network diagnostics", report));
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private String currentNetworkSummary() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if(manager == null)
                return "unknown";
            Network network = manager.getActiveNetwork();
            if(network == null)
                return "none";
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if(capabilities == null)
                return "unknown";
            StringBuilder builder = new StringBuilder();
            appendTransport(builder, capabilities, NetworkCapabilities.TRANSPORT_WIFI, "wifi");
            appendTransport(builder, capabilities, NetworkCapabilities.TRANSPORT_CELLULAR, "cellular");
            appendTransport(builder, capabilities, NetworkCapabilities.TRANSPORT_VPN, "vpn");
            appendTransport(builder, capabilities, NetworkCapabilities.TRANSPORT_ETHERNET, "ethernet");
            if(builder.length() == 0)
                builder.append("other");
            builder.append(",validated=").append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            builder.append(",internet=").append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            return builder.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void appendTransport(StringBuilder builder, NetworkCapabilities capabilities, int transport, String label) {
        if(!capabilities.hasTransport(transport))
            return;
        if(builder.length() > 0)
            builder.append('+');
        builder.append(label);
    }

    public static void urlSettingPopup(Context context, Preference p){
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final EditText definput = new EditText(context);
        final EditText webtoonInput = new EditText(context);
        final TextView inputtext = new TextView(context);
        final TextView webtoonText = new TextView(context);

        inputtext.setText("사이트 URL:");

        inputtext.setText("만화책 URL:");
        layout.addView(inputtext);
        definput.setText(p.getDefUrl());
        definput.setHint(p.getDefUrl());
        layout.addView(definput);
        webtoonText.setText("웹툰 URL:");
        layout.addView(webtoonText);
        webtoonInput.setText(p.getWebtoonUrl());
        webtoonInput.setHint(p.getWebtoonUrl());
        layout.addView(webtoonInput);

        final LinearLayout siteButtons = new LinearLayout(context);
        siteButtons.setOrientation(LinearLayout.HORIZONTAL);
        final Button wfwfButton = new Button(context);
        final Button ntkButton = new Button(context);
        wfwfButton.setText("WFWF");
        ntkButton.setText("NTK");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttonParams.setMargins(0, 12, 6, 0);
        siteButtons.addView(wfwfButton, buttonParams);
        LinearLayout.LayoutParams buttonParamsEnd = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttonParamsEnd.setMargins(6, 12, 0, 0);
        siteButtons.addView(ntkButton, buttonParamsEnd);
        layout.addView(siteButtons);

        wfwfButton.setOnClickListener(v -> {
            definput.setText(DEFAULT_COMIC_URL);
            webtoonInput.setText(WEBTOON_URL);
        });
        ntkButton.setOnClickListener(v -> {
            definput.setText(NTK_COMIC_URL);
            webtoonInput.setText(NTK_WEBTOON_URL);
        });

        AlertDialog.Builder builder;
        if(p.getDarkTheme()) builder = new AlertDialog.Builder(context,R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle("URL 설정")
                .setView(layout)
                .setPositiveButton("설정", (dialog, button) -> {
                    String url = definput.getText().length() > 0 ? definput.getText().toString() : definput.getHint().toString();
                    String webtoonUrl = webtoonInput.getText().length() > 0 ? webtoonInput.getText().toString() : webtoonInput.getHint().toString();
                    p.setSitePreset(url, webtoonUrl);
                    Toast.makeText(context, "사이트 설정이 변경되었습니다.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", (dialog, button) -> {
                    //do nothing
                })
                .show();
    }


    public boolean onOptionsItemSelected(MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

         {
            if (resultCode == Activity.RESULT_OK && data != null) {
                final Uri uri = data.getData();
                switch (requestCode) {
                    case MODE_FOLDER_SELECT:
                        getContentResolver().takePersistableUriPermission(uri, (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                        p.setHomeDir(uri.toString());
                        break;
                    case MODE_FILE_SAVE:
                        showStringInputPopup(context, "백업 파일 이름", s -> {
                            DocumentFile d = DocumentFile.fromTreeUri(context, uri);
                            if(!s.endsWith(".mvpref")) s += ".mvpref";

                            final DocumentFile target = d.findFile(s);
                            if(target != null){
                                String finalS = s;
                                showYesNoPopup(context, "파일이 이미 존재합니다.", "덮어 쓸까요?", (dialogInterface, i) -> {
                                    target.delete();
                                    if (writePreferenceToFile(context, d.createFile("application", finalS).getUri()))
                                        Toast.makeText(context, "내보내기 완료!", Toast.LENGTH_LONG).show();
                                    else
                                        Toast.makeText(context, "내보내기 실패", Toast.LENGTH_LONG).show();
                                }, null, null);
                            } else {
                                if (writePreferenceToFile(context, d.createFile("application", s).getUri()))
                                    Toast.makeText(context, "내보내기 완료!", Toast.LENGTH_LONG).show();
                                else
                                    Toast.makeText(context, "내보내기 실패", Toast.LENGTH_LONG).show();
                            }
                        }, p.getDarkTheme());
                        break;
                    case MODE_FILE_SELECT:
                        showYesNoPopup(context, "데이터 불러오기", "이 작업은 되돌릴 수 없습니다.\n복원을 진행 하시겠습니까?", (dialogInterface, i) -> {
                            if (readPreferenceFromFile(p, context, uri)) {
                                setResult(RESULT_NEED_RESTART);
                                showPopup(context, "데이터 불러오기", "데이터 불러오기를 성공했습니다. 변경사항을 적용하기 위해 앱을 재시작 합니다.", (dialogInterface12, i1) -> finish(), dialogInterface1 -> finish());
                            } else
                                Toast.makeText(context, "불러오기 실패", Toast.LENGTH_LONG).show();

                        }, (dialogInterface, i) -> Toast.makeText(context,"취소되었습니다", Toast.LENGTH_SHORT).show(), dialogInterface -> Toast.makeText(context,"취소되었습니다", Toast.LENGTH_SHORT).show());
                        break;
                }
            }
        }

    }

    private interface InputCallback{
        void onKeyEvent(KeyEvent event);
    }
}

