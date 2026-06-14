package com.cvuong233.cinephantom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.SearchManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.databinding.ActivityMainBinding
import com.cvuong233.cinephantom.notifications.WishlistNotificationScheduler
import com.cvuong233.cinephantom.notifications.WishlistRefreshWorker
import com.cvuong233.cinephantom.ui.account.AccountFragment
import com.cvuong233.cinephantom.ui.discover.DiscoverFragment
import com.cvuong233.cinephantom.ui.kdrama.KDramaFragment
import com.cvuong233.cinephantom.ui.search.SearchFragment
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_OPEN_DISCOVER_TITLE = "cinephantom.intent.action.OPEN_DISCOVER_TITLE"
        const val EXTRA_DISCOVER_IMDB_ID = "extra_discover_imdb_id"
        const val EXTRA_DISCOVER_TYPE = "extra_discover_type"
        private const val WORK_WISHLIST_REFRESH = "wishlist_refresh"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentTabTag: String = "search"

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — notifications will work/not work accordingly */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FavoritesRepository.init()
        WishlistNotificationScheduler.createChannel(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        enqueueWishlistRefresh()

        if (savedInstanceState == null) {
            // Skip SearchFragment creation if we're routing directly to another tab.
            // Fragment commits are async: if SearchFragment is added and immediately
            // hidden in the next commit, it may not be in fragmentManager.fragments yet
            // during that second commit, so it never gets hidden — and its 300ms
            // keyboard postDelayed then fires with isHidden == false.
            if (intent?.action != ACTION_OPEN_DISCOVER_TITLE) {
                showFragment(SearchFragment(), "search")
            }
        } else {
            currentTabTag = supportFragmentManager.fragments.firstOrNull { !it.isHidden }?.tag ?: "search"
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> showFragment(SearchFragment(), "search")
                R.id.nav_discover -> showFragment(DiscoverFragment.INSTANCE.newInstance(), "discover")
                R.id.nav_kdrama -> showFragment(KDramaFragment(), "kdrama")
                R.id.nav_account -> showFragment(AccountFragment(), "account")
            }
            true
        }

        handleIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun enqueueWishlistRefresh() {
        val request = PeriodicWorkRequestBuilder<WishlistRefreshWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_WISHLIST_REFRESH,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEARCH -> {
                intent.getStringExtra(SearchManager.QUERY)
                binding.bottomNav.selectedItemId = R.id.nav_search
                intent.removeExtra(SearchManager.QUERY)
            }
            ACTION_OPEN_DISCOVER_TITLE -> {
                val imdbId = intent.getStringExtra(EXTRA_DISCOVER_IMDB_ID).orEmpty()
                val type = intent.getStringExtra(EXTRA_DISCOVER_TYPE).orEmpty()
                if (imdbId.isNotBlank()) {
                    clearSearchFocusAndKeyboard()
                    binding.bottomNav.selectedItemId = R.id.nav_discover
                    supportFragmentManager.executePendingTransactions()
                    binding.root.post {
                        clearSearchFocusAndKeyboard()
                        (supportFragmentManager.findFragmentByTag("discover") as? DiscoverFragment)
                            ?.focusOnTitle(imdbId, type)
                        binding.root.postDelayed({ clearSearchFocusAndKeyboard() }, 120)
                    }
                }
            }
        }
    }

    private fun clearSearchFocusAndKeyboard() {
        currentFocus?.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        (supportFragmentManager.findFragmentByTag("search") as? SearchFragment)?.clearSearchFocus()
        supportFragmentManager.findFragmentByTag("search")?.view?.findFocus()?.clearFocus()
        binding.root.requestFocus()
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (tag != "search") clearSearchFocusAndKeyboard()

        val tabOrder = listOf("search", "discover", "kdrama", "account")
        val fromIndex = tabOrder.indexOf(currentTabTag)
        val toIndex = tabOrder.indexOf(tag)
        val movingForward = toIndex >= fromIndex

        supportFragmentManager.commit {
            setCustomAnimations(
                if (movingForward) R.anim.fragment_slide_in_right else R.anim.fragment_slide_in_left,
                if (movingForward) R.anim.fragment_slide_out_left else R.anim.fragment_slide_out_right,
                if (movingForward) R.anim.fragment_slide_in_left else R.anim.fragment_slide_in_right,
                if (movingForward) R.anim.fragment_slide_out_right else R.anim.fragment_slide_out_left
            )
            supportFragmentManager.fragments.forEach {
                if (it.tag != tag && !it.isHidden) hide(it)
            }
            if (existing != null) {
                show(existing)
            } else {
                add(R.id.main_fragment_container, fragment, tag)
            }
        }
        currentTabTag = tag
    }
}
