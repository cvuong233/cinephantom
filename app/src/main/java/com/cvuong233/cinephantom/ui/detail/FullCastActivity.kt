package com.cvuong233.cinephantom.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader

class FullCastActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_NAMES = "extra_names"
        const val EXTRA_CHARACTERS = "extra_characters"
        const val EXTRA_PROFILES = "extra_profiles"
        const val EXTRA_IDS = "extra_ids"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_cast)
        window.statusBarColor = 0xFF0D0011.toInt()

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val characters = intent.getStringArrayListExtra(EXTRA_CHARACTERS) ?: arrayListOf()
        val profiles = intent.getStringArrayListExtra(EXTRA_PROFILES) ?: arrayListOf()
        val ids = intent.getIntegerArrayListExtra(EXTRA_IDS) ?: arrayListOf()

        val backBtn = findViewById<TextView>(R.id.full_cast_back)
        val titleView = findViewById<TextView>(R.id.full_cast_title)
        val container = findViewById<LinearLayout>(R.id.full_cast_container)

        if (title.isNotBlank()) titleView.text = title
        backBtn.setOnClickListener { finish() }

        names.forEachIndexed { i, name ->
            val character = characters.getOrNull(i).orEmpty()
            val profilePath = profiles.getOrNull(i).orEmpty()
            val personId = ids.getOrNull(i) ?: 0

            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_full_cast_member, container, false)
            val avatarText = itemView.findViewById<TextView>(R.id.full_cast_avatar)
            val photo = itemView.findViewById<ImageView>(R.id.full_cast_photo)
            val nameView = itemView.findViewById<TextView>(R.id.full_cast_name)
            val characterView = itemView.findViewById<TextView>(R.id.full_cast_character)

            nameView.text = name

            val initials = name.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
            avatarText.text = initials

            if (character.isNotBlank()) {
                characterView.text = character
                characterView.visibility = View.VISIBLE
            } else {
                characterView.visibility = View.GONE
            }

            if (profilePath.isNotBlank()) {
                SimpleImageLoader.loadCastPhoto(
                    url = TMDBApi.PROFILE_IMAGE_LARGE_BASE + profilePath,
                    imageView = photo,
                    onSuccess = {
                        photo.visibility = View.VISIBLE
                        avatarText.visibility = View.GONE
                    },
                    onError = {
                        photo.visibility = View.GONE
                        avatarText.visibility = View.VISIBLE
                    }
                )
            }

            if (personId > 0) {
                itemView.setOnClickListener {
                    startActivity(Intent(this, CastDetailActivity::class.java).apply {
                        putExtra(CastDetailActivity.EXTRA_PERSON_ID, personId)
                        putExtra(CastDetailActivity.EXTRA_PERSON_NAME, name)
                        putExtra(CastDetailActivity.EXTRA_PERSON_PHOTO, profilePath)
                    })
                }
            }

            container.addView(itemView)
        }
    }
}
