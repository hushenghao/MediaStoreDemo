package com.dede.mediastoredemo

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val launcherCompat =
        ActivityResultLauncherCompat(this, ActivityResultContracts.RequestMultiplePermissions())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val image = findViewById<ImageView>(R.id.image)

        val options = BitmapFactory.Options()
        options.inSampleSize = 2
        val stream = assets.open("wallhaven_rdyyjm.jpg")
        val bitmap = BitmapFactory.decodeStream(stream, null, options)
        image.setImageBitmap(bitmap)
    }

    private fun saveImageInternal() {
        val uri = assets.open("wallhaven_rdyyjm.jpg").use {
            it.saveToAlbum(this, fileName = "save_wallhaven_rdyyjm.jpg", null)
        } ?: return

        Toast.makeText(this, uri.toString(), Toast.LENGTH_SHORT).show()
    }

    fun saveImage(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageInternal()
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            launcherCompat.launch(permissions) {
                saveImageInternal()
            }
        }
    }

    private fun shareImageInternal() {
        val uri = assets.open("wallhaven_rdyyjm.jpg").use {
            it.saveToAlbum(this, fileName = "save_wallhaven_rdyyjm.jpg", null)
        } ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .setType("image/*")
        startActivity(Intent.createChooser(intent, null))
    }

    fun shareImage(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shareImageInternal()
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            launcherCompat.launch(permissions) {
                shareImageInternal()
            }
        }
    }
}