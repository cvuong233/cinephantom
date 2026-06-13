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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchResultsAdapter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {

    private val auth = FirebaseAuth.getInstance()
    private var favoritesAdapter: SearchResultsAdapter? = null

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

        val recycler = view.findViewById<RecyclerView>(R.id.favorites_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.isNestedScrollingEnabled = false
        favoritesAdapter = SearchResultsAdapter { _, title -> openTitle(title) }.apply {
            onStremioClick = { /* no-op */ }
            onFavoriteClick = {
                FavoritesRepository.toggle(it)
                notifyFavoriteChanged(it.id)
            }
        }
        recycler.adapter = favoritesAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FavoritesRepository.favorites.collect { titles ->
                    val emptyText = view.findViewById<TextView>(R.id.favorites_empty_text) ?: return@collect
                    if (auth.currentUser == null) return@collect
                    if (titles.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                    } else {
                        emptyText.visibility = View.GONE
                        recycler.visibility = View.VISIBLE
                        favoritesAdapter?.submitList(titles)
                        favoritesAdapter?.hideLoading()
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
        val user = auth.currentUser
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
        Toast.makeText(requireContext(), "Signed out", Toast.LENGTH_SHORT).show()
        refreshUi(view)
    }

    private fun openTitle(title: ImdbTitle) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            title.tmdbId?.takeIf { it > 0 }?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
        }
        startActivity(intent)
    }

    private fun fadeIn(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = 20f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
