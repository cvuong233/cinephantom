package com.cvuong233.cinephantom.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.data.TMDBPersonCredit
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader

class CastDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON_ID = "extra_person_id"
        const val EXTRA_PERSON_NAME = "extra_person_name"
        const val EXTRA_PERSON_PHOTO = "extra_person_photo"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast_detail)
        window.statusBarColor = 0xFF0D0011.toInt()

        val personId = intent.getIntExtra(EXTRA_PERSON_ID, 0)
        val fallbackName = intent.getStringExtra(EXTRA_PERSON_NAME).orEmpty()
        val fallbackPhoto = intent.getStringExtra(EXTRA_PERSON_PHOTO).orEmpty()
        val transitionName = intent.getStringExtra(EXTRA_TRANSITION_NAME).orEmpty()

        val backBtn = findViewById<View>(R.id.toolbar_back)
        findViewById<TextView>(R.id.toolbar_title)?.text = "Cast Details"
        val headerView = findViewById<View>(R.id.cast_detail_header)
        val photoCard = findViewById<View>(R.id.cast_detail_photo_card)
        val photoView = findViewById<ImageView>(R.id.cast_detail_photo)
        val nameView = findViewById<TextView>(R.id.cast_detail_name)
        val deptView = findViewById<TextView>(R.id.cast_detail_department)
        val birthView = findViewById<TextView>(R.id.cast_detail_birth)
        val placeView = findViewById<TextView>(R.id.cast_detail_place)
        val bioLabel = findViewById<TextView>(R.id.cast_detail_bio_label)
        val bioView = findViewById<TextView>(R.id.cast_detail_bio)
        val knownForLabel = findViewById<TextView>(R.id.cast_detail_known_for_label)

        val hasSharedTransition = transitionName.isNotBlank()
        if (hasSharedTransition) {
            ViewCompat.setTransitionName(photoView, transitionName)
        }

        if (!hasSharedTransition) {
            backBtn.alpha = 0f
            headerView.translationY = 44f
            photoCard.scaleX = 0.86f
            photoCard.scaleY = 0.86f
            photoCard.rotation = -4f
            nameView.alpha = 0f
            nameView.translationX = 24f
            deptView.alpha = 0f
            birthView.alpha = 0f
            placeView.alpha = 0f
            bioLabel.alpha = 0f
            bioView.alpha = 0f
            knownForLabel.alpha = 0f
        }

        nameView.text = fallbackName

        if (fallbackPhoto.isNotBlank()) {
            val photoUrl = if (fallbackPhoto.startsWith("http")) fallbackPhoto
                           else TMDBApi.PROFILE_IMAGE_LARGE_BASE + fallbackPhoto
            SimpleImageLoader.load(url = photoUrl, imageView = photoView,
                onSuccess = { photoView.visibility = View.VISIBLE }
            )
        }

        backBtn.setOnClickListener {
            if (hasSharedTransition) finishAfterTransition() else finish()
        }

        if (personId > 0) {
            Thread {
                val person = TMDBApi().fetchPersonDetails(personId)
                runOnUiThread {
                    if (person != null) {
                        nameView.text = person.name.ifBlank { fallbackName }

                        if (!person.knownForDepartment.isNullOrBlank()) {
                            deptView.text = person.knownForDepartment
                            deptView.visibility = View.VISIBLE
                        }

                        val bday = person.birthday.orEmpty()
                        val dday = person.deathday.orEmpty()
                        val bdayText = when {
                            bday.isNotBlank() && dday.isNotBlank() -> "Born: $bday  •  Died: $dday"
                            bday.isNotBlank() -> "Born: $bday"
                            else -> ""
                        }
                        if (bdayText.isNotBlank()) {
                            birthView.text = bdayText
                            birthView.visibility = View.VISIBLE
                        }

                        if (!person.placeOfBirth.isNullOrBlank()) {
                            placeView.text = person.placeOfBirth
                            placeView.visibility = View.VISIBLE
                        }

                        if (!person.biography.isNullOrBlank()) {
                            bioLabel.visibility = View.VISIBLE
                            bioView.text = person.biography
                            bioView.visibility = View.VISIBLE
                        }

                        if (person.knownFor.isNotEmpty()) {
                            knownForLabel.visibility = View.VISIBLE
                            buildKnownForSection(person.knownFor)
                        }

                        if (!person.profilePath.isNullOrBlank()) {
                            SimpleImageLoader.load(
                                url = TMDBApi.PROFILE_IMAGE_LARGE_BASE + person.profilePath,
                                imageView = photoView,
                                onSuccess = { photoView.visibility = View.VISIBLE }
                            )
                        }
                    }
                    if (!hasSharedTransition) startCreativeEntrance()
                }
                // Resolve imdbIds lazily in background so tapping a credit later works
                person?.knownFor?.forEach { credit ->
                    if (credit.imdbId.isNullOrBlank()) {
                        credit.imdbId = TMDBApi().fetchImdbIdForTitle(credit.id, credit.mediaType)
                    }
                }
            }.start()
        } else if (!hasSharedTransition) {
            startCreativeEntrance()
        }
    }

    private fun buildKnownForSection(credits: List<TMDBPersonCredit>) {
        val container = findViewById<LinearLayout>(R.id.cast_detail_known_for_container)
        container.removeAllViews()
        credits.forEach { credit ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_cast_known_for, container, false)
            val poster = itemView.findViewById<ImageView>(R.id.known_for_poster)
            val titleView = itemView.findViewById<TextView>(R.id.known_for_title)
            val yearView = itemView.findViewById<TextView>(R.id.known_for_year)

            titleView.text = credit.title

            val yearText = credit.releaseDate?.take(4).orEmpty()
            if (yearText.isNotBlank()) {
                yearView.text = yearText
                yearView.visibility = View.VISIBLE
            } else {
                yearView.visibility = View.GONE
            }

            val posterPath = credit.posterPath.orEmpty()
            val transName = "known_for_${credit.id}"
            ViewCompat.setTransitionName(poster, transName)
            if (posterPath.isNotBlank()) {
                SimpleImageLoader.load(
                    url = "https://image.tmdb.org/t/p/w185$posterPath",
                    imageView = poster,
                    onSuccess = { poster.visibility = View.VISIBLE }
                )
            }

            itemView.setOnClickListener {
                val imdbId = credit.imdbId
                if (imdbId.isNullOrBlank()) {
                    Toast.makeText(this, "Still preparing this title — try again in a moment.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val typeLabel = if (credit.mediaType == "tv") "TV Series" else "Movie"
                val posterUrl = posterPath.ifBlank { "" }.let {
                    if (it.isNotBlank()) "https://image.tmdb.org/t/p/w185$it" else ""
                }
                val intent = Intent(this, DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_IMDB_ID, imdbId)
                    putExtra(DetailActivity.EXTRA_TITLE, credit.title)
                    putExtra(DetailActivity.EXTRA_IMAGE_URL, posterUrl)
                    putExtra(DetailActivity.EXTRA_YEAR, yearText)
                    putExtra(DetailActivity.EXTRA_TYPE, typeLabel)
                    putExtra(DetailActivity.EXTRA_TRANSITION_NAME, transName)
                    // Known For cards are vertical posters, not landscape backdrops — land the
                    // shared-element transition on DetailActivity's poster, not its backdrop.
                    putExtra(DetailActivity.EXTRA_TRANSITION_TARGET, DetailActivity.TRANSITION_TARGET_POSTER)
                }
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(this, poster, transName)
                startActivity(intent, options.toBundle())
            }

            container.addView(itemView)
        }
    }

    private fun startCreativeEntrance() {
        val backBtn = findViewById<View>(R.id.toolbar_back) ?: return
        val headerView = findViewById<View>(R.id.cast_detail_header) ?: return
        val photoCard = findViewById<View>(R.id.cast_detail_photo_card) ?: return
        val nameView = findViewById<TextView>(R.id.cast_detail_name) ?: return
        val deptView = findViewById<TextView>(R.id.cast_detail_department) ?: return
        val birthView = findViewById<TextView>(R.id.cast_detail_birth) ?: return
        val placeView = findViewById<TextView>(R.id.cast_detail_place) ?: return
        val bioLabel = findViewById<TextView>(R.id.cast_detail_bio_label) ?: return
        val bioView = findViewById<TextView>(R.id.cast_detail_bio) ?: return
        val knownForLabel = findViewById<TextView>(R.id.cast_detail_known_for_label) ?: return

        backBtn.animate().alpha(1f).setStartDelay(90L).setDuration(220L).start()
        headerView.animate().translationY(0f).setDuration(520L)
            .setInterpolator(DecelerateInterpolator(1.25f)).start()
        photoCard.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(640L)
            .setInterpolator(OvershootInterpolator(1.18f)).start()
        nameView.animate().translationX(0f).alpha(1f).setStartDelay(120L).setDuration(360L)
            .setInterpolator(DecelerateInterpolator()).start()
        deptView.animate().alpha(1f).setStartDelay(220L).setDuration(240L).start()
        birthView.animate().alpha(1f).setStartDelay(260L).setDuration(240L).start()
        placeView.animate().alpha(1f).setStartDelay(300L).setDuration(240L).start()
        bioLabel.animate().alpha(1f).setStartDelay(340L).setDuration(240L).start()
        bioView.animate().alpha(1f).setStartDelay(390L).setDuration(320L).start()
        knownForLabel.animate().alpha(1f).setStartDelay(440L).setDuration(240L).start()
    }
}
