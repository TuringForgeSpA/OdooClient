package cl.turingforge.odooclient

import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cl.turingforge.odooclient.data.OdooServer
import cl.turingforge.odooclient.data.ServerRepository
import cl.turingforge.odooclient.databinding.ActivitySetupBinding
import cl.turingforge.odooclient.util.OdooServerProbe
import cl.turingforge.odooclient.util.OdooUrl
import cl.turingforge.odooclient.util.ServerShortcuts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Agregar o editar un servidor (nombre + URL). Valida contra /web/webclient/version_info.
 *  - Agregar: guarda y conecta de inmediato.
 *  - Editar (EXTRA_SERVER_ID): guarda y vuelve al selector.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var repo: ServerRepository
    private var editing: OdooServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        repo = ServerRepository(this)
        editing = intent.getStringExtra(EXTRA_SERVER_ID)?.let(repo::find)

        editing?.let {
            binding.title.setText(R.string.setup_title_edit)
            binding.btnConnect.setText(R.string.setup_save)
            binding.inputName.setText(it.name)
            binding.inputUrl.setText(it.url)
        }

        binding.inputUrl.doAfterTextChanged { binding.urlLayout.error = null }
        binding.inputUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { onConnect(); true } else false
        }
        binding.btnConnect.setOnClickListener { onConnect() }
    }

    private fun onConnect() {
        val url = OdooUrl.normalize(binding.inputUrl.text?.toString().orEmpty())
        if (url == null) {
            binding.urlLayout.error = getString(R.string.error_invalid_url)
            return
        }
        val duplicate = repo.findByUrl(url)
        if (duplicate != null && duplicate.id != editing?.id) {
            binding.urlLayout.error = getString(R.string.error_duplicate, duplicate.name)
            return
        }
        // Sin nombre → el host ("comercialsur.odoo.com").
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
            .ifEmpty { Uri.parse(url).host ?: url }

        hideKeyboard()
        setLoading(true)

        lifecycleScope.launch {
            val version = OdooServerProbe.fetchVersion(url)
            setLoading(false)
            val major = version?.let(OdooServerProbe::majorVersion)
            when {
                version == null -> confirm(R.string.warn_not_verified_title, getString(R.string.warn_not_verified)) { save(name, url) }
                major != 18 -> confirm(R.string.warn_version_title, getString(R.string.warn_version, version)) { save(name, url) }
                else -> save(name, url)
            }
        }
    }

    private fun save(name: String, url: String) {
        val current = editing
        if (current != null) {
            repo.update(current.id, name, url)
            ServerShortcuts.publish(this, repo.servers())
            finish()
        } else {
            val server = repo.add(name, url)
            ServerShortcuts.publish(this, repo.servers())
            startActivity(MainActivity.intentFor(this, server.id))
            finish()
        }
    }

    private fun confirm(titleRes: Int, message: String, onContinue: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue) { _, _ -> onContinue() }
            .show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnConnect.isEnabled = !loading
        binding.inputUrl.isEnabled = !loading
        binding.inputName.isEnabled = !loading
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(binding.inputUrl.windowToken, 0)
    }

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
    }
}
