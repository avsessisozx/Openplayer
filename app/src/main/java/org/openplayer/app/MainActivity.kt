package org.openplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Geçici ana ekran: video seçip oynatıcıyı açar. Dosya listesi sonraki adımda gelecek. */
class MainActivity : AppCompatActivity() {

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply { data = uri }
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply {
            text = "Video seç"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    button,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
        )
    }
}
