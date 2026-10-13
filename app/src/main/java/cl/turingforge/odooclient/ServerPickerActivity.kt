package cl.turingforge.odooclient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import cl.turingforge.odooclient.data.OdooServer
import cl.turingforge.odooclient.data.ServerRepository
import cl.turingforge.odooclient.databinding.ActivityServerPickerBinding
import cl.turingforge.odooclient.databinding.ItemServerBinding
import cl.turingforge.odooclient.util.ServerShortcuts
import cl.turingforge.odooclient.web.WebProfiles
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Selector de servidor: conectar, agregar, editar y eliminar. */
class ServerPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerPickerBinding
    private lateinit var repo: ServerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityServerPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        repo = ServerRepository(this)
        binding.btnAdd.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        binding.switchAsk.isChecked = repo.askOnLaunch
        binding.switchAsk.setOnCheckedChangeListener { _, checked -> repo.askOnLaunch = checked }
    }

    override fun onResume() {
        super.onResume()
        WebProfiles.purgePending(repo)
        render()
    }

    private fun render() {
        val servers = repo.servers()
        if (servers.isEmpty()) {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }

        binding.serverList.removeAllViews()
        servers.forEach { server ->
            val item = ItemServerBinding.inflate(layoutInflater, binding.serverList, false)
            item.avatar.text = initials(server.name)
            item.name.text = server.name
            item.url.text = server.url.removePrefix("https://")
            item.root.setOnClickListener { connect(server) }
            item.btnMore.setOnClickListener { showMenu(it, server) }
            binding.serverList.addView(item.root)
        }
        // La preferencia solo tiene sentido con más de un servidor.
        binding.switchAsk.isVisible = servers.size > 1
    }

    private fun connect(server: OdooServer) {
        startActivity(MainActivity.intentFor(this, server.id))
        finish()
    }

    private fun showMenu(anchor: View, server: OdooServer) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EDIT, 0, R.string.edit)
            menu.add(0, MENU_DELETE, 1, R.string.delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_EDIT -> startActivity(
                        Intent(this@ServerPickerActivity, SetupActivity::class.java)
                            .putExtra(SetupActivity.EXTRA_SERVER_ID, server.id)
                    )
                    MENU_DELETE -> confirmDelete(server)
                }
                true
            }
            show()
        }
    }

    private fun confirmDelete(server: OdooServer) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_title, server.name))
            .setMessage(R.string.delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> delete(server) }
            .show()
    }

    private fun delete(server: OdooServer) {
        repo.remove(server.id)
        ServerShortcuts.onServerRemoved(this, server.id)
        ServerShortcuts.publish(this, repo.servers())

        if (!isTaskRoot) {
            // Abierto desde MainActivity: ese WebView puede estar usando el servidor eliminado.
            // Reiniciamos la tarea con el selector como raíz para cerrarlo (y liberar su perfil).
            startActivity(
                Intent(this, ServerPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        } else {
            WebProfiles.purgePending(repo)
            render()
        }
    }

    private fun initials(name: String): String =
        name.split(' ', '-', '.', '_').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { Uri.parse(name).host?.take(1)?.uppercase() ?: "O" }

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
    }
}
