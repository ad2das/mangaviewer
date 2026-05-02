package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

import ml.melun.mangaview.R;
import ml.melun.mangaview.report.CrashReporter;

public class CrashReportActivity extends Activity {
    private static final String ISSUE_URL = "https://github.com/ad2das/mangaviewer/issues/new";
    private static final int MAX_BODY_LENGTH = 7500;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = readCrashReport();
        showReportDialog(report);
    }

    private void showReportDialog(String report) {
        new AlertDialog.Builder(this)
                .setTitle("MangaView")
                .setMessage(getString(R.string.acra_dialog_text))
                .setPositiveButton("GitHub 열기", (dialog, which) -> {
                    openGitHubIssue(report);
                    finish();
                })
                .setNeutralButton("복사", (dialog, which) -> {
                    copyReport(report);
                    finish();
                })
                .setNegativeButton("닫기", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private String readCrashReport() {
        try {
            File file = new File(getFilesDir(), CrashReporter.CRASH_REPORT_FILE);
            if(!file.exists())
                return "No crash report file found.";
            try(FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while((read = input.read(buffer)) != -1)
                    output.write(buffer, 0, read);
                return output.toString("UTF-8");
            }
        } catch (Exception e) {
            return "Failed to read crash report.\n" + CrashReporter.stackTrace(e);
        }
    }

    private void openGitHubIssue(String report) {
        String title = "[Crash] " + firstCrashLine(report);
        String body = "## Crash report\n\n```text\n" + limit(report, MAX_BODY_LENGTH) + "\n```\n\n"
                + "## Notes\n\n오류 직전에 한 동작을 여기에 추가로 적어주세요.\n";
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_URL).buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", body)
                .build());
        startActivity(intent);
    }

    private void copyReport(String report) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("mangaview_crash_report", report));
        Toast.makeText(this, "오류 리포트를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }

    private String firstCrashLine(String report) {
        String[] lines = report.split("\\r?\\n");
        for(String line : lines) {
            String trimmed = line.trim();
            if(trimmed.contains("Exception") || trimmed.contains("Error"))
                return limit(trimmed, 120);
        }
        return "Unknown crash";
    }

    private String limit(String value, int maxLength) {
        if(value == null)
            return "";
        if(value.length() <= maxLength)
            return value;
        return value.substring(0, maxLength) + "\n... truncated ...";
    }
}
