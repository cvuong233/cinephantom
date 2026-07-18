package com.cvuong233.cinephantom.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.notifications.NotificationHistoryActivity
import com.cvuong233.cinephantom.notifications.NotificationSettingsActivity
import com.cvuong233.cinephantom.ui.settings.StreamingPlatformsSettingsActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {

    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_account, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.account_sign_in_btn).setOnClickListener {
            startActivity(Intent(requireContext(), AuthActivity::class.java))
        }
        view.findViewById<TextView>(R.id.account_sign_out_btn).setOnClickListener {
            signOut(view)
        }
        view.findViewById<LinearLayout>(R.id.account_watchlist_row).setOnClickListener {
            startActivity(Intent(requireContext(), WatchlistActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.account_notif_settings_row).setOnClickListener {
            startActivity(Intent(requireContext(), NotificationSettingsActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.account_notif_history_row).setOnClickListener {
            startActivity(Intent(requireContext(), NotificationHistoryActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.account_streaming_platforms_row).setOnClickListener {
            startActivity(Intent(requireContext(), StreamingPlatformsSettingsActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FavoritesRepository.favorites.collect { titles ->
                    val countView = view.findViewById<TextView>(R.id.account_watchlist_count) ?: return@collect
                    if (titles.isEmpty()) {
                        countView.visibility = View.GONE
                    } else {
                        countView.text = "${titles.size}"
                        countView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi(view ?: return)
    }

    private fun refreshUi(view: View) {
        // MainActivity signs everyone in anonymously on launch, so currentUser is never null —
        // isAnonymous is what actually distinguishes "never signed in" from a real account.
        val user = auth.currentUser?.takeUnless { it.isAnonymous }
        val signedOut = view.findViewById<LinearLayout>(R.id.account_signed_out)
        val signedIn = view.findViewById<LinearLayout>(R.id.account_signed_in)

        if (user == null) {
            signedIn.visibility = View.GONE
            fadeIn(signedOut)
        } else {
            signedOut.visibility = View.GONE
            populateSignedIn(view, user)
            fadeIn(signedIn)
        }
    }

    private fun populateSignedIn(view: View, user: FirebaseUser) {
        val avatarView = view.findViewById<TextView>(R.id.account_avatar)
        val nameView = view.findViewById<TextView>(R.id.account_display_name)
        val emailView = view.findViewById<TextView>(R.id.account_email)
        val badgeView = view.findViewById<TextView>(R.id.account_provider_badge)

        val initial = (user.displayName?.firstOrNull() ?: user.email?.firstOrNull() ?: '?').uppercaseChar()
        avatarView.text = initial.toString()

        nameView.text = user.displayName?.takeIf { it.isNotBlank() } ?: "CinePhantom User"
        emailView.text = user.email.orEmpty()

        val providerLabel = user.providerData
            .mapNotNull { it.providerId }
            .firstOrNull { it != "firebase" }
            ?.let { id ->
                when {
                    id.contains("google") -> "via Google"
                    id.contains("password") -> "via Email"
                    else -> id
                }
            } ?: "via Email"
        badgeView.text = providerLabel
    }

    private fun signOut(view: View) {
        auth.signOut()
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(requireContext(), gso).signOut()
        } catch (_: Exception) {}
        // Without this, search history / watch-provider writes would silently no-op again until
        // the app is restarted (only MainActivity.onCreate re-establishes an anonymous uid).
        auth.signInAnonymously()
        Toast.makeText(requireContext(), "Signed out", Toast.LENGTH_SHORT).show()
        refreshUi(view)
    }

    private fun fadeIn(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = 24f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
    }
}
