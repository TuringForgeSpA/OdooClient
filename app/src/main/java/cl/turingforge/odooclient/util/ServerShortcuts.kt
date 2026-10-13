package cl.turingforge.odooclient.util

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cl.turingforge.odooclient.MainActivity
import cl.turingforge.odooclient.R
import cl.turingforge.odooclient.data.OdooServer

/** Accesos directos dinámicos: mantener presionado el ícono → abrir una empresa directamente. */
object ServerShortcuts {

    private const val MAX_DYNAMIC = 3 // + el estático "Servidores" = 4, lo que muestran la mayoría de launchers

    fun publish(context: Context, servers: List<OdooServer>) {
        runCatching {
            if (servers.size < 2) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }
            val shortcuts = servers.take(MAX_DYNAMIC).mapIndexed { rank, server ->
                ShortcutInfoCompat.Builder(context, shortcutId(server.id))
                    .setShortLabel(server.name.take(25))
                    .setLongLabel(server.name)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(MainActivity.intentFor(context, server.id))
                    .setRank(rank)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    /** Si el usuario había anclado el acceso de ese servidor a la pantalla de inicio, se desactiva. */
    fun onServerRemoved(context: Context, serverId: String) {
        runCatching {
            ShortcutManagerCompat.disableShortcuts(
                context, listOf(shortcutId(serverId)), context.getString(R.string.shortcut_disabled)
            )
        }
    }

    private fun shortcutId(serverId: String) = "server_$serverId"
}
