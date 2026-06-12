package io.github.june690602_blip.cleanpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleanpdf.store.RecentFilesStore
import java.io.File

/**
 * Launcher home screen (mirrors the CleanCAD viewer's home): "PDF 열기" + a recent-files list.
 * Picking a file opens it in [MainActivity] (the viewer). Incoming 카톡 VIEW/SEND intents still go
 * straight to [MainActivity] via its manifest filters, bypassing this screen.
 */
class HomeActivity : AppCompatActivity() {
    private val recents by lazy { RecentFilesStore(this) }
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openInViewer(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        findViewById<Button>(R.id.btn_open).setOnClickListener {
            openDoc.launch(arrayOf("application/pdf", "application/x-pdf", "application/octet-stream"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()  // reflect newly-opened files when returning from the viewer
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ABOUT, 0, R.string.about)
        menu.add(0, MENU_PRIVACY, 1, R.string.privacy_policy)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ABOUT -> { showAbout(); true }
        MENU_PRIVACY -> { showPrivacy(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshRecent() {
        val container = findViewById<LinearLayout>(R.id.container_recent)
        val empty = findViewById<TextView>(R.id.tv_empty)
        container.removeAllViews()
        val files = recents.list().filter { File(it.path).exists() }  // drop entries the cache lost
        if (files.isEmpty()) { empty.visibility = View.VISIBLE; return }
        empty.visibility = View.GONE
        files.forEach { rf ->
            container.addView(Button(this).apply {
                text = rf.name
                isAllCaps = false
                setOnClickListener { openInViewer(File(rf.path), rf.name) }
                setOnLongClickListener { confirmDelete(rf.path, rf.name); true }
            })
        }
    }

    private fun confirmDelete(path: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(R.string.recent_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> recents.remove(path); refreshRecent() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** SAF content Uri → viewer (grant read so MainActivity can copy it to cache). */
    private fun openInViewer(uri: Uri) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    /** A recent (already-cached) file → viewer via an explicit path extra (no file:// exposure). */
    private fun openInViewer(file: File, name: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LOCAL_PATH, file.absolutePath)
            putExtra(MainActivity.EXTRA_NAME, name)
        })
    }

    private fun showAbout() =
        showInfoDialog(R.string.app_name, R.string.about_text, Linkify.WEB_URLS)

    private fun showPrivacy() =
        showInfoDialog(R.string.privacy_policy, R.string.privacy_text, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)

    /** Shared info dialog: titled message with linkified URLs (and emails per [linkMask]). */
    private fun showInfoDialog(titleRes: Int, messageRes: Int, linkMask: Int) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.let {
            Linkify.addLinks(it, linkMask)
            it.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private companion object {
        const val MENU_ABOUT = 1
        const val MENU_PRIVACY = 2
    }
}
