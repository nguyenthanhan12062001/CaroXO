package com.game.caroxo.an.data

import android.app.Activity
import android.content.Context
import android.util.Log
import android.util.Patterns
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.game.caroxo.an.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object AuthManager {

    private const val TAG = "Auth"

    fun authStateFlow(auth: FirebaseAuth = FirebaseAuth.getInstance()): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: (FirebaseUser) -> Unit,
        onUnauthenticated: () -> Unit
    ) {
        val existingUser = FirebaseAuth.getInstance().currentUser
        if (existingUser != null) {
            onAuthSuccess(existingUser)
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(context, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val result = FirebaseAuth.getInstance()
                        .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .await()
                    val user = result.user
                    if (user != null) onAuthSuccess(user) else onUnauthenticated()
                } else {
                    onUnauthenticated()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun signInWithGoogle(
        activity: Activity,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: (FirebaseUser) -> Unit,
        onAuthCancelled: () -> Unit,
        onAuthError: (String) -> Unit
    ) {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError(activity.getString(R.string.auth_error_missing_client_id))
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(activity, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val result = FirebaseAuth.getInstance()
                        .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .await()
                    val user = result.user
                    if (user != null) {
                        onAuthSuccess(user)
                    } else {
                        onAuthError(activity.getString(R.string.auth_error_generic))
                    }
                } else {
                    onAuthError(activity.getString(R.string.auth_error_unexpected_credential))
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: activity.getString(R.string.auth_error_generic))
            }
        }
    }

    fun isValidEmail(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
    }

    fun signInWithEmail(
        context: Context,
        email: String,
        password: String,
        scope: CoroutineScope,
        onAuthSuccess: (FirebaseUser) -> Unit,
        onAuthError: (String) -> Unit
    ) {
        val cleanEmail = email.trim()
        if (!isValidEmail(cleanEmail)) {
            onAuthError(context.getString(R.string.auth_error_invalid_email))
            return
        }
        if (password.length < 6) {
            onAuthError(context.getString(R.string.auth_error_short_password))
            return
        }

        scope.launch {
            try {
                val result = FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(cleanEmail, password)
                    .await()
                val user = result.user
                if (user != null) {
                    onAuthSuccess(user)
                } else {
                    onAuthError(context.getString(R.string.auth_error_generic))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Email sign-in failed", e)
                onAuthError(mapFirebaseAuthError(context, e))
            }
        }
    }

    fun registerWithEmail(
        context: Context,
        email: String,
        password: String,
        confirmPassword: String,
        nickname: String,
        scope: CoroutineScope,
        onAuthSuccess: (FirebaseUser) -> Unit,
        onAuthError: (String) -> Unit
    ) {
        val cleanEmail = email.trim()
        val cleanNick = nickname.trim()
        if (cleanNick.isEmpty() || cleanNick.length > 24) {
            onAuthError(context.getString(R.string.auth_error_nickname_required))
            return
        }
        if (!isValidEmail(cleanEmail)) {
            onAuthError(context.getString(R.string.auth_error_invalid_email))
            return
        }
        if (password.length < 6) {
            onAuthError(context.getString(R.string.auth_error_short_password))
            return
        }
        if (password != confirmPassword) {
            onAuthError(context.getString(R.string.auth_error_password_mismatch))
            return
        }

        scope.launch {
            try {
                val result = FirebaseAuth.getInstance()
                    .createUserWithEmailAndPassword(cleanEmail, password)
                    .await()
                val user = result.user
                if (user != null) {
                    try {
                        val profileUpdate = UserProfileChangeRequest.Builder()
                            .setDisplayName(cleanNick)
                            .build()
                        user.updateProfile(profileUpdate).await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update display name after registration: ${e.message}")
                    }
                    onAuthSuccess(user)
                } else {
                    onAuthError(context.getString(R.string.auth_error_generic))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Email registration failed", e)
                onAuthError(mapFirebaseAuthError(context, e))
            }
        }
    }

    fun sendPasswordReset(
        context: Context,
        email: String,
        scope: CoroutineScope,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cleanEmail = email.trim()
        if (!isValidEmail(cleanEmail)) {
            onError(context.getString(R.string.auth_error_invalid_email))
            return
        }

        scope.launch {
            try {
                FirebaseAuth.getInstance()
                    .sendPasswordResetEmail(cleanEmail)
                    .await()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Password reset failed", e)
                onError(mapFirebaseAuthError(context, e, fallback = context.getString(R.string.auth_error_reset_failed)))
            }
        }
    }

    private fun mapFirebaseAuthError(
        context: Context,
        error: Exception,
        fallback: String = context.getString(R.string.auth_error_generic)
    ): String = when (error) {
        is FirebaseAuthInvalidUserException -> context.getString(R.string.auth_error_user_not_found)
        is FirebaseAuthWeakPasswordException -> context.getString(R.string.auth_error_weak_password)
        is FirebaseAuthInvalidCredentialsException -> context.getString(R.string.auth_error_invalid_credentials)
        is FirebaseAuthUserCollisionException -> context.getString(R.string.auth_error_email_in_use)
        is FirebaseNetworkException -> context.getString(R.string.auth_error_network)
        is FirebaseAuthException -> {
            if (error.errorCode == "ERROR_OPERATION_NOT_ALLOWED") {
                context.getString(R.string.auth_error_email_provider_disabled)
            } else {
                error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
            }
        }
        else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    FirebaseAuth.getInstance().signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
