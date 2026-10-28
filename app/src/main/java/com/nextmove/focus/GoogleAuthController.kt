package com.nextmove.focus

import android.app.Activity
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.core.content.ContextCompat
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

data class GoogleAuthOutcome(
    val signedIn: Boolean,
    val message: String? = null
)

/**
 * Google sign-in through Android Credential Manager, then Firebase Auth.
 * Firestore creates the social profile after authentication, so this works on
 * Firebase's no-cost Spark plan without a Cloud Functions deployment.
 */
class GoogleAuthController {
    fun isConfigured(activity: Activity): Boolean = firebaseApp(activity) != null && webClientId(activity) != null

    fun isSignedIn(activity: Activity): Boolean = firebaseApp(activity)
        ?.let { FirebaseAuth.getInstance(it).currentUser?.isAnonymous == false }
        ?: false

    fun accountLabel(activity: Activity): String? = firebaseApp(activity)
        ?.let { FirebaseAuth.getInstance(it).currentUser?.email ?: FirebaseAuth.getInstance(it).currentUser?.displayName }

    fun signIn(activity: Activity, onDone: (GoogleAuthOutcome) -> Unit) {
        val app = firebaseApp(activity) ?: run {
            onDone(GoogleAuthOutcome(false, "Firebase config is missing in this build."))
            return
        }
        val clientId = webClientId(activity) ?: run {
            onDone(GoogleAuthOutcome(false, "Google OAuth client ID is missing. Download the updated google-services.json."))
            return
        }
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(activity).getCredentialAsync(
            MutableContextWrapper(activity),
            request,
            null,
            ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val custom = result.credential as? CustomCredential
                    if (custom?.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        onDone(GoogleAuthOutcome(false, "Google did not return a usable account."))
                        return
                    }
                    val token = runCatching { GoogleIdTokenCredential.createFrom(custom.data).idToken }
                        .getOrElse {
                            onDone(GoogleAuthOutcome(false, "That Google sign-in response could not be read."))
                            return
                        }
                    val auth = FirebaseAuth.getInstance(app)
                    val credential = GoogleAuthProvider.getCredential(token, null)
                    val existingUser = auth.currentUser
                    val task = if (existingUser?.isAnonymous == true) {
                        existingUser.linkWithCredential(credential)
                    } else {
                        auth.signInWithCredential(credential)
                    }
                    task.addOnCompleteListener { authResult ->
                        if (!authResult.isSuccessful) {
                            onDone(GoogleAuthOutcome(false, authResult.exception?.localizedMessage ?: "Google sign-in failed."))
                            return@addOnCompleteListener
                        }
                        onDone(GoogleAuthOutcome(true))
                    }
                }

                override fun onError(e: GetCredentialException) {
                    onDone(GoogleAuthOutcome(false, e.localizedMessage ?: "Google sign-in was cancelled."))
                }
            }
        )
    }

    fun signOut(activity: Activity, onDone: () -> Unit) {
        firebaseApp(activity)?.let { FirebaseAuth.getInstance(it).signOut() }
        CredentialManager.create(activity).clearCredentialStateAsync(
            ClearCredentialStateRequest(),
            null,
            ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<Void?, ClearCredentialException> {
                override fun onResult(result: Void?) = onDone()
                override fun onError(e: ClearCredentialException) = onDone()
            }
        )
    }

    private fun firebaseApp(activity: Activity): FirebaseApp? = runCatching { FirebaseApp.getInstance() }.getOrNull()

    private fun webClientId(activity: Activity): String? {
        val resourceId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (resourceId == 0) return null
        return activity.getString(resourceId).takeIf { it.isNotBlank() }
    }
}
