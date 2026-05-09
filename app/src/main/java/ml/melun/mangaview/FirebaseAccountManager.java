package ml.melun.mangaview;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.security.MessageDigest;

public class FirebaseAccountManager {
    public static final int RC_GOOGLE_SIGN_IN = 9010;

    private final Context appContext;
    private final FirebaseAuth auth;
    private GoogleSignInClient signInClient;

    public FirebaseAccountManager(Context context) {
        appContext = context.getApplicationContext();
        FirebaseAuth firebaseAuth = null;
        try {
            FirebaseApp app;
            if(FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseOptions options = FirebaseOptions.fromResource(appContext);
                app = options == null ? null : FirebaseApp.initializeApp(appContext, options);
            } else {
                app = FirebaseApp.getInstance();
            }
            if(app != null)
                firebaseAuth = FirebaseAuth.getInstance(app);
        } catch (Exception e) {
            firebaseAuth = null;
        }
        auth = firebaseAuth;
    }

    public boolean isAvailable() {
        return auth != null && getDefaultWebClientId().length() > 0;
    }

    public boolean hasFirebaseConfig() {
        return auth != null;
    }

    public FirebaseUser getUser() {
        return auth == null ? null : auth.getCurrentUser();
    }

    public boolean signIn(Activity activity, SignInCallback callback) {
        if(!isAvailable()) {
            if(callback != null)
                callback.onSignInResult(false, hasFirebaseConfig()
                        ? "Google 로그인 설정이 없습니다."
                        : "Firebase 설정이 필요합니다.");
            return false;
        }
        int playServicesStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity);
        if(playServicesStatus != ConnectionResult.SUCCESS) {
            if(callback != null)
                callback.onSignInResult(false, "Google Play 서비스가 필요합니다: " + playServicesStatus);
            return false;
        }
        try {
            signInClient = null;
            activity.startActivityForResult(getSignInClient().getSignInIntent(), RC_GOOGLE_SIGN_IN);
            return true;
        } catch (ActivityNotFoundException e) {
            if(callback != null)
                callback.onSignInResult(false, "Google 로그인 화면을 열 수 없습니다.");
            return false;
        } catch (Exception e) {
            if(callback != null)
                callback.onSignInResult(false, e.getMessage() == null ? "Google 로그인 시작 실패" : e.getMessage());
            return false;
        }
    }

    public void signIn(Activity activity) {
        if(!isAvailable())
            return;
        signIn(activity, null);
    }

    public void signOut(Runnable afterSignOut) {
        if(auth == null) {
            if(afterSignOut != null)
                afterSignOut.run();
            return;
        }
        auth.signOut();
        if(signInClient != null) {
            signInClient.signOut().addOnCompleteListener(task -> {
                if(afterSignOut != null)
                    afterSignOut.run();
            });
        } else if(afterSignOut != null) {
            afterSignOut.run();
        }
    }

    public void handleActivityResult(Intent data, SignInCallback callback) {
        if(!isAvailable()) {
            if(callback != null)
                callback.onSignInResult(false, "Firebase 설정이 필요합니다.");
            return;
        }
        if(data == null) {
            if(callback != null)
                callback.onSignInResult(false, "Google sign-in returned no result data.");
            return;
        }
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            if(account == null || account.getIdToken() == null) {
                if(callback != null)
                    callback.onSignInResult(false, "Google 로그인 토큰을 받지 못했습니다.");
                return;
            }
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            auth.signInWithCredential(credential)
                    .addOnCompleteListener(authTask -> {
                        if(callback != null) {
                            if(authTask.isSuccessful())
                                callback.onSignInResult(true, null);
                            else
                                callback.onSignInResult(false, authTask.getException() == null ? "로그인 실패" : authTask.getException().getMessage());
                        }
                    });
        } catch (ApiException e) {
            if(callback != null)
                callback.onSignInResult(false, describeGoogleSignInError(e));
        }
    }

    private GoogleSignInClient getSignInClient() {
        if(signInClient == null) {
            GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(getDefaultWebClientId())
                    .requestEmail()
                    .build();
            signInClient = GoogleSignIn.getClient(appContext, options);
        }
        return signInClient;
    }

    private String getDefaultWebClientId() {
        return getStringResource("default_web_client_id");
    }

    private String getStringResource(String name) {
        int id = appContext.getResources().getIdentifier(name, "string", appContext.getPackageName());
        if(id == 0)
            return "";
        try {
            return appContext.getString(id);
        } catch (Exception e) {
            return "";
        }
    }

    private String describeGoogleSignInError(ApiException e) {
        int code = e.getStatusCode();
        String label = GoogleSignInStatusCodes.getStatusCodeString(code);
        String message = e.getMessage();
        String signature = getAppSignatureSha1();
        String hint = code == 10 && signature.length() > 0
                ? " Add this app SHA-1 in Firebase: " + signature
                : "";
        if(message == null || message.length() == 0)
            return "Google sign-in failed: " + label + " (" + code + ")." + hint;
        return "Google sign-in failed: " + label + " (" + code + ") - " + message + hint;
    }

    private String getAppSignatureSha1() {
        try {
            PackageManager manager = appContext.getPackageManager();
            PackageInfo info;
            Signature[] signatures;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = manager.getPackageInfo(appContext.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                info = manager.getPackageInfo(appContext.getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if(signatures == null || signatures.length == 0)
                return "";
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] sha1 = digest.digest(signatures[0].toByteArray());
            StringBuilder builder = new StringBuilder();
            for(int i = 0; i < sha1.length; i++) {
                if(i > 0)
                    builder.append(':');
                builder.append(String.format("%02X", sha1[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public interface SignInCallback {
        void onSignInResult(boolean success, String message);
    }
}
