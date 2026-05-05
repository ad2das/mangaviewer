package ml.melun.mangaview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

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

    public void signIn(Activity activity) {
        if(!isAvailable())
            return;
        activity.startActivityForResult(getSignInClient().getSignInIntent(), RC_GOOGLE_SIGN_IN);
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
                callback.onSignInResult(false, e.getMessage());
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
        return appContext.getString(R.string.default_web_client_id);
    }

    public interface SignInCallback {
        void onSignInResult(boolean success, String message);
    }
}
