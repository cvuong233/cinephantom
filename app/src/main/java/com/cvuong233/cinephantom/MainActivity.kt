package com.cvuong233.cinephantom

import android.os.Bundle
import android.content.Intent
import android.app.SearchManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cvuong233.cinephantom.databinding.ActivityMainBinding
import com.cvuong233.cinephantom.ui.search.SearchFragment
import com.cvuong233.cinephantom.ui.discover.DiscoverFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(SearchFragment(), "search")
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> showFragment(SearchFragment(), "search")
                R.id.nav_discover -> showFragment(DiscoverFragment(), "discover")
            }
            true
        }

        // Handle incoming search intents
        if (Intent.ACTION_SEARCH == intent?.action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            binding.bottomNav.selectedItemId = R.id.nav_search
            // The SearchFragment will read this via its arguments or a callback
            intent.removeExtra(SearchManager.QUERY)
        }
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.forEach {
                if (it.tag != tag && !it.isHidden) hide(it)
            }
            if (existing != null) {
                show(existing)
            } else {
                add(R.id.main_fragment_container, fragment, tag)
            }
            commit()
        }
    }
}
