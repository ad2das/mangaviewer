package ml.melun.mangaview.account

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

internal data class AccountState(
    val signedIn: Boolean = false,
    val displayName: String = "",
    val message: String = "로그인하면 기록을 자동 저장합니다",
    val busy: Boolean = false,
)

/** One application-owned worker serializes restore, local edits, and cloud notifications. */
internal class AccountSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val local: LocalCloudLibrary,
    private val episodes: suspend (ml.melun.mangaview.core.SeriesId) -> List<ml.melun.mangaview.source.SourceEpisode>,
) {
    private val mutableState = MutableStateFlow(AccountState())
    val state = mutableState.asStateFlow()
    private val sessions = MutableStateFlow<FirebaseUser?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val checkpoint = AccountCheckpointStore(File(context.applicationInfo.dataDir, "files/account-library-v2.json"))
    private val initialized = scope.async(io, start = CoroutineStart.LAZY) {
        val app = FirebaseApp.initializeApp(context) ?: error("Google account configuration is missing")
        FirebaseAuth.getInstance(app).also { auth ->
            auth.addAuthStateListener { sessions.value = it.currentUser }
        }
    }

    init {
        scope.launch(io) {
            try {
                val auth = initialized.await()
                sessions.collectLatest { user ->
                    if (user == null) mutableState.value = AccountState()
                    else runSession(auth, user)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = AccountState(message = "계정 연결을 시작하지 못했습니다. 다시 시도해 주세요") }
        }
    }

    suspend fun signIn(activity: Activity) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, message = "Google 계정 연결 중")
        try {
            val auth = initialized.await()
            val clientId = withContext(io) {
                val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                require(id != 0)
                context.getString(id)
            }
            val credential = CredentialManager.create(activity).getCredential(activity,
                GetCredentialRequest.Builder().addCredentialOption(
                    GetSignInWithGoogleOption.Builder(clientId).build()).build()).credential
            check(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            withContext(io) { auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await() }
        } catch (_: GetCredentialCancellationException) { mutableState.value = AccountState() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = AccountState(message = "Google 로그인에 실패했습니다. 다시 시도해 주세요") }
    }

    fun signOut() {
        scope.launch(io) {
            initialized.await().signOut()
            runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        }
    }

    fun retry() { wake.trySend(Unit) }

    private suspend fun runSession(auth: FirebaseAuth, user: FirebaseUser) {
        val name = user.email ?: user.displayName.orEmpty()
        val remote = FirebaseLibraryRemote(auth, FirebaseFirestore.getInstance(auth.app), user.uid, episodes)
        AccountSyncSession(user.uid, local, checkpoint, remote, wake,
            isCurrent = { auth.currentUser?.uid == user.uid },
            status = { message, busy -> mutableState.value = AccountState(true, name, message, busy) },
        ).run()
    }
}
