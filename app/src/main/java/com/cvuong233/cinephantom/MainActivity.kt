package com.cvuong233.cinephantom

import android.os.Bundle
import android.content.Intent
import android.app.SearchManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.cvuong233.cinephantom.databinding.ActivityMainBinding
import com.cvuong233.cinephantom.ui.search.SearchFragment
import com.cvuong233.cinephantom.ui.discover.DiscoverFragment

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_OPEN_DISCOVER_TITLE = "cinephantom.intent.action.OPEN_DISCOVER_TITLE"
        const val EXTRA_DISCOVER_IMDB_ID = "extra_discover_imdb_id"
        const val EXTRA_DISCOVER_TYPE = "extra_discover_type"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentTabTag: String = "search"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(SearchFragment(), "search")
        } else {
            currentTabTag = supportFragmentManager.fragments.firstOrNull { !it.isHidden }?.tag ?: "search"
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> showFragment(SearchFragment(), "search")
                R.id.nav_discover -> showFragment(DiscoverFragment(), "discover")
            }
            true
        }

        handleIntent(intent)
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
        if (tag == "discover") clearSearchFocusAndKeyboard()

        val movingForward = when {
            currentTabTag == tag -> true
            currentTabTag == "search" && tag == "discover" -> true
            else -> false
        }

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
