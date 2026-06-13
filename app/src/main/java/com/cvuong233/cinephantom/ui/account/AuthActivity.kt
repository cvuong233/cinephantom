package com.cvuong233.cinephantom.ui.account

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cvuong233.cinephantom.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class AuthActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            setStatus("Signing in with Google…", isError = false)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { finish() }
                .addOnFailureListener { e ->
                    setStatus(e.message ?: "Google sign-in failed", isError = true)
                    resetGoogleBtn()
                }
        } catch (e: ApiException) {
            if (e.statusCode != 12501) { // 12501 = user cancelled
                setStatus("Google sign-in failed (code ${e.statusCode})", isError = true)
            } else {
                clearStatus()
            }
            resetGoogleBtn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        if (auth.currentUser != null) { finish(); return }

        val backBtn = findViewById<ImageView>(R.id.auth_back)
        val googleBtn = findViewById<TextView>(R.id.auth_google_btn)

        backBtn.setOnClickListener { finish() }

        val webClientId = getString(R.string.default_web_client_id)
        if (webClientId.isBlank()) {
            googleBtn.alpha = 0.35f
            googleBtn.isClickable = false
            googleBtn.text = "Google Sign-In unavailable"
        } else {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            val googleClient = GoogleSignIn.getClient(this, gso)

            googleBtn.setOnClickListener {
                clearStatus()
                googleBtn.text = "Opening Google…"
                googleBtn.isClickable = false
                googleClient.signOut().addOnCompleteListener {
                    googleSignInLauncher.launch(googleClient.signInIntent)
                }
            }
        }
    }

    private fun resetGoogleBtn() {
        val googleBtn = findViewById<TextView>(R.id.auth_google_btn)
        googleBtn.text = "Continue with Google"
        googleBtn.isClickable = true
    }

    private fun setStatus(msg: String, isError: Boolean) {
        val v = findViewById<TextView>(R.id.auth_status)
        v.text = msg
        v.setTextColor(
            if (isError) android.graphics.Color.parseColor("#FF6B6B")
            else getColor(R.color.text_muted)
        )
        v.visibility = View.VISIBLE
    }

    private fun clearStatus() {
        findViewById<TextView>(R.id.auth_status).visibility = View.GONE
    }
}
